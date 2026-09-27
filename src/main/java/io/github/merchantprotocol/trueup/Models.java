package io.github.merchantprotocol.trueup;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** Response types. Fields mirror the API's JSON; anything not listed here is in the raw JSON. */
public final class Models {
    private Models() {}

    /** One thing TrueUp found. */
    public static final class Finding {
        /** phantom, unbilled, duplicate, received_duplicate, qty_mismatch, price_change, amount_mismatch, unsure_pair */
        public String kind;
        public String label;
        /** The row it is about: "statement.csv:row 5". */
        public String subject;
        public String detail;
        /** How likely the pairing is right; null for rows with no pair. */
        public Double confidence;
        public String status;
        public Double amount;
        public List<String> provenance;
        public JsonObject data;
    }

    /** One pairing of a left row with a right row. */
    public static final class Pair {
        public String left;
        public String right;
        public double confidence;
    }

    /** What the run learned, and every pair it made. */
    public static final class Details {
        public JsonObject model;
        /** Pass back as {@link ReconcileOptions#weights} to apply what was learned without learning again. */
        public JsonObject weights;
        public List<Pair> pairs;
    }

    /** The answer to a reconcile call. */
    public static final class ReconcileResult {
        public String analysis;
        public String title;
        public String headline;
        public Map<String, Double> stats;
        public List<Finding> findings;
        public Details details;
        public List<String> inputs;
        public String engine;
        /** The kept run, for {@link TrueUp#reconcileStored}; null for tables sent inline. */
        public String run_id;
    }

    /** The answer to a match call. Findings' kind: match, unsure_match (a person should check), only_left, only_right. */
    public static final class MatchResult {
        public String analysis;
        public String title;
        public String headline;
        public Map<String, Double> stats;
        public List<Finding> findings;
        public MatchDetails details;
        public List<String> inputs;
        public String engine;
        /** The kept run, for {@link TrueUp#matchStored}; null otherwise. */
        public String run_id;
    }

    /** How the columns lined up, every pair ([left id, right id, confidence]), and what was learned. */
    public static final class MatchDetails {
        public JsonObject columns;
        public List<List<Object>> pairs;
        public JsonObject model;
        /** Pass back as the {@code weights} of {@link TrueUp#match} to match the same way without learning. */
        public JsonObject weights;
    }

    /** A file stored in the team (uploaded through the API or the dashboard). */
    public static final class StoredFile {
        public String id;
        public String name;
        public long size;
        /** "table" or "document". */
        public String kind;
        public Integer rows;
        public List<String> columns;
        /** What TrueUp read each column as: "date", "number", "text", ... */
        public Map<String, String> roles;
        public String created_at;
    }

    /** A run on stored files, from the API or the dashboard. */
    public static final class Run {
        public String id;
        public String analysis;
        /** "done" or "failed". */
        public String status;
        /** "api" or "portal". */
        public String via;
        public List<String> inputs;
        public Named model;
        public String headline;
        public Map<String, Double> stats;
        /** How many findings the run has. */
        public Integer findings;
        public String error;
        public String created_at;

        public static final class Named { public String id; public String name; }
    }

    /** One page of runs, newest first. */
    public static final class RunPage {
        public List<Run> runs;
        public boolean has_more;
    }

    /** One run and its full result (null if the run failed). */
    public static final class RunDetail {
        public Run run;
        public ReconcileResult result;
    }

    /** A saved model: what a run learned, reusable on next month's files. */
    public static final class Model {
        public String id;
        public String name;
        public String analysis;
        public String source_run_id;
        public String created_at;
        /** Only from {@link TrueUp#getModel}. */
        public JsonObject weights;
    }

    /** The team, plan and key behind an API key. */
    public static final class Account {
        public Named team;
        public Plan plan;
        public Key key;

        public static final class Named { public String id; public String name; }
        public static final class Plan { public String slug; public String name; }
        public static final class Key { public String id; public String name; public String prefix; }
    }

    /** This month's use of one metric. */
    public static final class UsageMetric {
        public String metric;
        public String label;
        public long used;
        public long included;
        public long remaining;
        public boolean hard_cap;
        public long overage;
    }

    /** This month's usage for a team. */
    public static final class Usage {
        public String period;
        public String resets_at;
        public List<UsageMetric> metrics;
    }

    /** A plan a team can be on. */
    public static final class Plan {
        public String slug;
        public String name;
        public String description;
        public long price_cents;
        public String interval;
        public boolean purchasable;
        public List<JsonObject> limits;
    }

    /** Decisions a person made about pairs: [left row, right row]. */
    public static final class Answers {
        public List<List<String>> same;
        public List<List<String>> different;

        public Answers(List<List<String>> same, List<List<String>> different) {
            this.same = same;
            this.different = different;
        }
    }

    /** Optional settings for a reconcile call. */
    public static final class ReconcileOptions {
        /** details.weights from an earlier result. */
        public JsonObject weights;
        public Answers answers;

        public ReconcileOptions weights(JsonObject w) { this.weights = w; return this; }
        public ReconcileOptions answers(Answers a) { this.answers = a; return this; }
    }
}
