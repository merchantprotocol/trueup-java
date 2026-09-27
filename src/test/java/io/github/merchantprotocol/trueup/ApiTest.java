package io.github.merchantprotocol.trueup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.merchantprotocol.trueup.Models.ReconcileOptions;
import io.github.merchantprotocol.trueup.Models.ReconcileResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Integration tests against the live TrueUp API. Need TRUEUP_API_KEY (and optionally TRUEUP_BASE_URL).
 * Each full run uses 2 analyses. Run in Docker: `just test` (or `docker compose run --rm test`).
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
}
