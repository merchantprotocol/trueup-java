package io.github.merchantprotocol.trueup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.merchantprotocol.trueup.Models.Account;
import io.github.merchantprotocol.trueup.Models.Answers;
import io.github.merchantprotocol.trueup.Models.Model;
import io.github.merchantprotocol.trueup.Models.RunDetail;
import io.github.merchantprotocol.trueup.Models.RunPage;
import io.github.merchantprotocol.trueup.Models.StoredFile;
import io.github.merchantprotocol.trueup.Models.Plan;
import io.github.merchantprotocol.trueup.Models.ReconcileOptions;
import io.github.merchantprotocol.trueup.Models.ReconcileResult;
import io.github.merchantprotocol.trueup.Models.Usage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * TrueUp API client. Thread-safe.
 *
 * <pre>{@code
 * TrueUp trueup = TrueUp.builder().build();            // reads TRUEUP_API_KEY
 * ReconcileResult result = trueup.reconcile(Table.file("statement.csv"), Table.file("receiving.csv"));
 * for (Models.Finding f : result.findings) System.out.println(f.kind + " " + f.subject + " " + f.detail);
 * }</pre>
 */
public final class TrueUp {
    public static final String VERSION = "0.1.0";
    public static final String DEFAULT_BASE_URL = "https://trueup-cloud.merchantprotocol.workers.dev";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final HttpClient http;
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    private TrueUp(Builder b) {
        String key = nonEmpty(b.apiKey) != null ? b.apiKey : nonEmpty(b.env.apply("TRUEUP_API_KEY"));
        if (key == null) {
            throw new TrueUpException.AuthenticationException(
                "No API key: use builder().apiKey(...) or set TRUEUP_API_KEY. Create one in the TrueUp dashboard under API keys.",
                0, "missing_api_key", null);
        }
        this.apiKey = key;
        String base = nonEmpty(b.baseUrl) != null ? b.baseUrl
            : nonEmpty(b.env.apply("TRUEUP_BASE_URL")) != null ? b.env.apply("TRUEUP_BASE_URL") : DEFAULT_BASE_URL;
        this.baseUrl = base.replaceAll("/+$", "");
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
        this.http = b.httpClient != null ? b.httpClient
            : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Client settings. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private Duration timeout = Duration.ofSeconds(300);
        private int maxRetries = 2;
        private HttpClient httpClient;
        private java.util.function.Function<String, String> env = System::getenv;

        /** For tests: where environment variables are read from. */
        Builder environment(java.util.function.Function<String, String> env) { this.env = env; return this; }

        /** Default: the TRUEUP_API_KEY environment variable. */
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        /** Default: TRUEUP_BASE_URL, then the hosted API. */
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        /** Per request. Default 300 s (big ledgers take a while). */
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        /** Retries for rate limits, server errors and dropped connections. Default 2. */
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }
        public TrueUp build() { return new TrueUp(this); }
    }

    public String getBaseUrl() { return baseUrl; }

    /** The team, plan and key behind this client's API key. */
    public Account account() {
        return gson.fromJson(request("GET", "/v1/account", null, null), Account.class);
    }

    /** This month's usage for the key's team. */
    public Usage usage() {
        return gson.fromJson(request("GET", "/v1/usage", null, null), Usage.class);
    }

    /** The plans a team can be on. */
    public List<Plan> plans() {
        JsonObject o = JsonParser.parseString(request("GET", "/v1/plans", null, null)).getAsJsonObject();
        List<Plan> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("plans")) out.add(gson.fromJson(e, Plan.class));
        return out;
    }

    /**
     * Reconcile two tables. {@code left} is the side that bills or claims (a statement, your books), {@code right}
     * the other side (receiving log, bank feed). Counts as one analysis.
     */
    public ReconcileResult reconcile(Table left, Table right) {
        return reconcile(left, right, null);
    }

    /** Reconcile two tables with saved weights and/or answers. */
    public ReconcileResult reconcile(Table left, Table right, ReconcileOptions options) {
        ReconcileOptions o = options != null ? options : new ReconcileOptions();
        if (left.isRows() && right.isRows()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("left", Map.of("name", left.getName(), "rows", left.getRows()));
            body.put("right", Map.of("name", right.getName(), "rows", right.getRows()));
            if (o.weights != null) body.put("weights", o.weights);
            if (o.answers != null) body.put("answers", o.answers);
            String json = gson.toJson(body);
            return gson.fromJson(request("POST", "/v1/reconcile", json.getBytes(StandardCharsets.UTF_8), "application/json"),
                ReconcileResult.class);
        }
        return upload(List.of("left", "right"), List.of(left, right), o);
    }

    /** Send two or more files; TrueUp picks the pair to reconcile and which side is which. One analysis. */
    public ReconcileResult reconcileFiles(List<Table> files, ReconcileOptions options) {
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) fields.add("files");
        return upload(fields, files, options != null ? options : new ReconcileOptions());
    }

    // ---------------------------------------------------------------- stored files, runs, saved models

    /** Upload one or more files to the team. Each comes back with its id, rows, columns and roles. */
    public List<StoredFile> uploadFiles(Table... files) {
        if (files.length == 0) throw new TrueUpException.InvalidRequestException("Pass at least one file to upload.", 0, "invalid_request", null);
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < files.length; i++) fields.add("file");
        String json = new String(multipart("/v1/files", fields, List.of(files), new ReconcileOptions()), StandardCharsets.UTF_8);
        return list(json, "files", StoredFile.class);
    }

    /** The team's stored files. */
    public List<StoredFile> listFiles() {
        return list(request("GET", "/v1/files", null, null), "files", StoredFile.class);
    }

    public StoredFile getFile(String id) {
        return gson.fromJson(obj(request("GET", "/v1/files/" + enc(id), null, null)).get("file"), StoredFile.class);
    }

    /** The file's bytes, exactly as uploaded. */
    public byte[] fileContent(String id) {
        return send("GET", "/v1/files/" + enc(id) + "/content", null, null);
    }

    public void deleteFile(String id) {
        request("DELETE", "/v1/files/" + enc(id), null, null);
    }

    /**
     * Reconcile two files already stored in the team, by id: {@code leftFileId} bills or claims. The run is kept:
     * its id is {@code run_id} in the result. One analysis.
     */
    public ReconcileResult reconcileStored(String leftFileId, String rightFileId) {
        return reconcileStored(Map.<String, Object>of("left_file_id", leftFileId, "right_file_id", rightFileId), null, null);
    }

    /** Reconcile stored files by id; TrueUp picks the pair. {@code model} (a saved model id) and {@code answers} may be null. */
    public ReconcileResult reconcileStored(List<String> fileIds, String model, Answers answers) {
        return reconcileStored(Map.<String, Object>of("file_ids", fileIds), model, answers);
    }

    private ReconcileResult reconcileStored(Map<String, Object> ids, String model, Answers answers) {
        Map<String, Object> body = new LinkedHashMap<>(ids);
        if (model != null) body.put("model", model);
        if (answers != null) body.put("answers", answers);
        return gson.fromJson(request("POST", "/v1/reconcile", gson.toJson(body).getBytes(StandardCharsets.UTF_8), "application/json"),
            ReconcileResult.class);
    }

    /** One page of runs on stored files, newest first. {@code limit} 1-100 (null for 100); {@code before} a run id or null. */
    public RunPage listRuns(Integer limit, String before) {
        List<String> q = new ArrayList<>();
        if (limit != null) q.add("limit=" + limit);
        if (before != null) q.add("before=" + enc(before));
        return gson.fromJson(request("GET", "/v1/runs" + (q.isEmpty() ? "" : "?" + String.join("&", q)), null, null), RunPage.class);
    }

    /** Every run, newest first, fetching page after page. */
    public void forEachRun(Consumer<Models.Run> action) {
        String before = null;
        while (true) {
            RunPage page = listRuns(100, before);
            page.runs.forEach(action);
            if (!page.has_more || page.runs.isEmpty()) return;
            before = page.runs.get(page.runs.size() - 1).id;
        }
    }

    /** One run and its full result, in the shape {@link #reconcile} returns. */
    public RunDetail getRun(String id) {
        return gson.fromJson(request("GET", "/v1/runs/" + enc(id), null, null), RunDetail.class);
    }

    /** Save what a run learned as a model. Returns the model id. {@code name} may be null. */
    public String createModel(String runId, String name) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("run_id", runId);
        if (name != null) body.put("name", name);
        return obj(request("POST", "/v1/models", gson.toJson(body).getBytes(StandardCharsets.UTF_8), "application/json")).get("id").getAsString();
    }

    public List<Model> listModels() {
        return list(request("GET", "/v1/models", null, null), "models", Model.class);
    }

    /** One saved model, including its weights. */
    public Model getModel(String id) {
        return gson.fromJson(obj(request("GET", "/v1/models/" + enc(id), null, null)).get("model"), Model.class);
    }

    public void deleteModel(String id) {
        request("DELETE", "/v1/models/" + enc(id), null, null);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private <T> List<T> list(String json, String key, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (JsonElement e : obj(json).getAsJsonArray(key)) out.add(gson.fromJson(e, type));
        return out;
    }

    // ---------------------------------------------------------------- transport

    private ReconcileResult upload(List<String> fields, List<Table> tables, ReconcileOptions o) {
        return gson.fromJson(new String(multipart("/v1/reconcile", fields, tables, o), StandardCharsets.UTF_8), ReconcileResult.class);
    }

    private byte[] multipart(String path, List<String> fields, List<Table> tables, ReconcileOptions o) {
        String boundary = "----trueup" + Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            if (o.weights != null) field(body, boundary, "weights", gson.toJson(o.weights));
            if (o.answers != null) field(body, boundary, "answers", gson.toJson(o.answers));
            for (int i = 0; i < tables.size(); i++) {
                Table t = tables.get(i);
                String name = t.fileName().replaceAll("[\"\r\n]", "_");
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fields.get(i)
                    + "\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(t.bytes(gson));
                body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TrueUpException.ConnectionException(e.getMessage());
        }
        return send("POST", path, body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    private String request(String method, String path, byte[] body, String contentType) {
        return new String(send(method, path, body, contentType), StandardCharsets.UTF_8);
    }

    private byte[] send(String method, String path, byte[] body, String contentType) {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "trueup-java/" + VERSION);
            if (body != null) {
                req.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> res;
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    sleep(backoff(attempt));
                    continue;
                }
                throw new TrueUpException.ConnectionException("Couldn't reach TrueUp at " + baseUrl + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TrueUpException.ConnectionException("Interrupted");
            }
            int status = res.statusCode();
            if (status >= 200 && status < 300) return res.body();
            String text = new String(res.body(), StandardCharsets.UTF_8);
            String code = "http_" + status;
            String message = "HTTP " + status;
            try {
                JsonObject err = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("error");
                if (err != null) {
                    if (err.has("code")) code = err.get("code").getAsString();
                    if (err.has("message")) message = err.get("message").getAsString();
                }
            } catch (RuntimeException ignored) {
                // not a JSON error body
            }
            TrueUpException error = errorFor(status, code, message, text, res.headers().firstValue("retry-after").orElse(null));
            boolean retryable = error instanceof TrueUpException.RateLimitException || error instanceof TrueUpException.ServerException;
            if (retryable && attempt < maxRetries) {
                Double ra = error instanceof TrueUpException.RateLimitException ? ((TrueUpException.RateLimitException) error).getRetryAfter() : null;
                sleep(ra != null ? (long) (ra * 1000) : backoff(attempt));
                continue;
            }
            throw error;
        }
    }

    private static TrueUpException errorFor(int status, String code, String message, String body, String retryAfter) {
        if (status == 401) return new TrueUpException.AuthenticationException(message, status, code, body);
        if (status == 429 && "quota_exceeded".equals(code)) return new TrueUpException.QuotaExceededException(message, status, code, body);
        if (status == 429) {
            Double ra = null;
            try {
                if (retryAfter != null) ra = Double.parseDouble(retryAfter);
            } catch (NumberFormatException ignored) {
                // not seconds
            }
            return new TrueUpException.RateLimitException(message, status, code, body, ra);
        }
        if (status == 404 || status == 405) return new TrueUpException.NotFoundException(message, status, code, body);
        if (status >= 500) return new TrueUpException.ServerException(message, status, code, body);
        return new TrueUpException.InvalidRequestException(message, status, code, body);
    }

    private static long backoff(int attempt) {
        return (long) (Math.min(30.0, Math.pow(2, attempt)) * (0.5 + RANDOM.nextDouble() / 2) * 1000);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
