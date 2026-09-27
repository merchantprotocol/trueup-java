package io.github.merchantprotocol.trueup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.merchantprotocol.trueup.Models.AuditResult;
import io.github.merchantprotocol.trueup.Models.EstimateResult;
import io.github.merchantprotocol.trueup.Models.MatchResult;
import io.github.merchantprotocol.trueup.Models.ReconcileOptions;
import io.github.merchantprotocol.trueup.Models.RunDetail;
import io.github.merchantprotocol.trueup.Models.RunPage;
import io.github.merchantprotocol.trueup.Models.StoredFile;
import io.github.merchantprotocol.trueup.Models.ReconcileResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Integration tests against the live TrueUp API. Need TRUEUP_API_KEY (and optionally TRUEUP_BASE_URL).
 * Each full run uses 10 analyses. Run in Docker: `just test` (or `docker compose run --rm test`).
 */
class ApiTest {
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private static boolean live() {
        String k = System.getenv("TRUEUP_API_KEY");
        return k != null && !k.isEmpty();
    }

    private static List<Map<String, String>> rows(String name) throws IOException {
        List<String> lines = Files.readAllLines(FIXTURES.resolve(name));
        String[] head = lines.get(0).split(",");
        List<Map<String, String>> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isEmpty()) continue;
            String[] cells = line.split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < head.length; i++) row.put(head[i], cells[i]);
            out.add(row);
        }
        return out;
    }

    @Test
    void missingApiKeyFailsBeforeAnyRequest() {
        TrueUpException e = assertThrows(TrueUpException.AuthenticationException.class,
            () -> TrueUp.builder().environment(name -> null).build());
        assertEquals("missing_api_key", e.getCode());
    }

    @Test
    void accountUsagePlans() {
        assumeTrue(live(), "needs TRUEUP_API_KEY");
        TrueUp tu = TrueUp.builder().build();
        assertTrue(tu.account().key.prefix.startsWith("tu_live_"));
        assertTrue(tu.usage().metrics.stream().anyMatch(m -> m.metric.equals("analyses")));
        assertTrue(tu.plans().stream().anyMatch(p -> p.slug.equals("free")));
    }

    @Test
    void reconcileFilesThenRowsWithSavedWeights() throws IOException {
        assumeTrue(live(), "needs TRUEUP_API_KEY");
        TrueUp tu = TrueUp.builder().build();
        ReconcileResult result = tu.reconcile(Table.file(FIXTURES.resolve("statement.csv")), Table.file(FIXTURES.resolve("receiving.csv")));
        assertEquals("reconcile", result.analysis);
        assertEquals(7.0, result.stats.get("paired"));
        assertEquals(2, result.findings.size());
        assertEquals("qty_mismatch statement.csv:row 5", result.findings.get(0).kind + " " + result.findings.get(0).subject);
        assertEquals("phantom statement.csv:row 6", result.findings.get(1).kind + " " + result.findings.get(1).subject);
        assertEquals(43.2, result.findings.get(1).amount, 1e-9);
        assertEquals("trueup.match-weights", result.details.weights.get("format").getAsString());

        ReconcileResult again = tu.reconcile(Table.rows("statement.csv", rows("statement.csv")),
            Table.rows("receiving.csv", rows("receiving.csv")), new ReconcileOptions().weights(result.details.weights));
        assertEquals(7.0, again.stats.get("paired"));
        assertFalse(again.details.model.get("learned").getAsBoolean());
    }

    @Test
    void errorsAreTyped() {
        assumeTrue(live(), "needs TRUEUP_API_KEY");
        TrueUpException.AuthenticationException auth = assertThrows(TrueUpException.AuthenticationException.class,
            () -> TrueUp.builder().apiKey("tu_live_" + "x".repeat(40)).build().account());
        assertEquals(401, auth.getStatus());
        assertEquals("invalid_api_key", auth.getCode());
        TrueUpException.InvalidRequestException bad = assertThrows(TrueUpException.InvalidRequestException.class,
            () -> TrueUp.builder().build().reconcile(Table.file(FIXTURES.resolve("statement.csv")), Table.content("scan.pdf", "%PDF-1.4")));
        assertEquals(422, bad.getStatus());
        assertEquals("unsupported_file", bad.getCode());
    }

    @Test
    void storedFilesRunsAndModels() throws IOException {
        assumeTrue(live(), "needs TRUEUP_API_KEY");
        TrueUp tu = TrueUp.builder().build();
        List<StoredFile> files = tu.uploadFiles(Table.file(FIXTURES.resolve("statement.csv")), Table.file(FIXTURES.resolve("receiving.csv")));
        StoredFile statement = files.get(0);
        StoredFile receiving = files.get(1);
        try {
            assertEquals(8, statement.rows);
            assertEquals("number", statement.roles.get("Qty"));
            assertEquals("receiving.csv", tu.getFile(receiving.id).name);
            assertTrue(tu.listFiles().stream().anyMatch(f -> f.id.equals(statement.id)));
            assertArrayEquals(Files.readAllBytes(FIXTURES.resolve("statement.csv")), tu.fileContent(statement.id));

            ReconcileResult result = tu.reconcileStored(statement.id, receiving.id);
            assertEquals(7, result.stats.get("paired"));
            assertTrue(result.run_id.startsWith("run_"));
            RunDetail run = tu.getRun(result.run_id);
            assertEquals("done", run.run.status);
            assertEquals(7, run.result.stats.get("paired"));
            RunPage page = tu.listRuns(1, null);
            assertEquals(1, page.runs.size());
            if (page.has_more) assertNotEquals(page.runs.get(0).id, tu.listRuns(1, page.runs.get(0).id).runs.get(0).id);

            String modelId = tu.createModel(result.run_id, "sdk test");
            try {
                assertEquals("trueup.match-weights", tu.getModel(modelId).weights.get("format").getAsString());
                ReconcileResult again = tu.reconcileStored(List.of(statement.id, receiving.id), modelId, null);
                assertFalse(again.details.model.get("learned").getAsBoolean());
            } finally {
                tu.deleteModel(modelId);
            }
            assertThrows(TrueUpException.NotFoundException.class, () -> tu.getModel(modelId));
        } finally {
            tu.deleteFile(statement.id);
            tu.deleteFile(receiving.id);
        }
        assertThrows(TrueUpException.NotFoundException.class, () -> tu.getFile(statement.id));
    }

    @Test
    void matchTwoListsThenReuseTheLearning() throws IOException {
        assumeTrue(live(), "needs TRUEUP_API_KEY");
        TrueUp tu = TrueUp.builder().build();
        List<List<String>> want = List.of(List.of("1", "1"), List.of("2", "2"), List.of("3", "3"), List.of("4", "5"));
        MatchResult result = tu.match(Table.file(FIXTURES.resolve("invoice.csv")), Table.file(FIXTURES.resolve("catalog.csv")));
        assertEquals("match", result.analysis);
        assertEquals(want, result.details.pairs.stream().map(p -> List.of((String) p.get(0), (String) p.get(1))).collect(Collectors.toList()));
        assertEquals(List.of("5"), result.findings.stream().filter(f -> f.kind.equals("only_left")).map(f -> f.subject).collect(Collectors.toList()));
        MatchResult again = tu.match(Table.rows("invoice.csv", rows("invoice.csv")), Table.rows("catalog.csv", rows("catalog.csv")), result.details.weights);
        assertEquals(want, again.details.pairs.stream().map(p -> List.of((String) p.get(0), (String) p.get(1))).collect(Collectors.toList()));
        assertFalse(again.details.model.get("learned").getAsBoolean());
    }

    @Test
    void auditSixInvoicesThenOneAgainstTheSavedLaws() {
        assumeTrue(live(), "needs TRUEUP_API_KEY");
        TrueUp tu = TrueUp.builder().build();
        List<Table> files = new ArrayList<>();
        for (int i = 1; i <= 6; i++) files.add(Table.file(FIXTURES.resolve("invoices/inv-104" + i + ".txt")));
        AuditResult result = tu.audit(files);
        assertEquals("audit", result.analysis);
        assertEquals(1, result.findings.size());
        assertEquals("inv-1045.txt", result.findings.get(0).subject);
        assertEquals(200.0, result.findings.get(0).amount);
        assertTrue(result.details.laws.stream().anyMatch(l -> l.law.equals("subtotal + tax amount = total")));
        AuditResult one = tu.audit(List.of(Table.file(FIXTURES.resolve("invoices/inv-1045.txt"))), result.details.weights);
        assertFalse(one.details.model.get("learned").getAsBoolean());
        assertEquals("inv-1045.txt", one.findings.get(0).subject);
    }

    @Test
    void estimateANewJobThenTheNextWithTheSavedModel() {
        assumeTrue(live(), "needs TRUEUP_API_KEY");
        TrueUp tu = TrueUp.builder().build();
        List<Table> files = new ArrayList<>();
        for (String n : List.of("barndo.tu", "01_anderson.csv", "02_brooks.csv", "03_carter.md", "04_dalton.txt", "05_ellis.json",
                "06_foster.tsv", "07_garrison.txt", "08_hayes.csv", "09_iverson.csv", "10_jensen.md", "job_a.txt")) {
            files.add(Table.file(FIXTURES.resolve("barndo/" + n)));
        }
        EstimateResult result = tu.estimate(files);
        assertEquals("estimate", result.analysis);
        assertEquals(10.0, result.stats.get("past estimates"));
        double total = result.stats.get("total");
        assertTrue(Math.abs(total - 292267) / 292267 < 0.05, "total " + total);
        assertTrue(result.stats.get("low") < total);
        EstimateResult next = tu.estimate(List.of(Table.file(FIXTURES.resolve("barndo/job_b.txt"))), result.details.weights);
        assertFalse(next.details.model.get("learned").getAsBoolean());
    }
}
