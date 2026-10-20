package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class StaticSafetyReportTest {
    @Test
    void emptyReportIsExplicitlyAdvisoryAndNotAuthenticated() {
        String json = StaticSafetyReport.render(List.of());
        assertTrue(json.contains("\"schema\":\"ores-static-safety/v0\""), json);
        assertTrue(json.contains("\"producer_authenticated\":false"), json);
        assertTrue(json.contains("\"proof_complete\":false"), json);
        assertTrue(json.contains("\"findings\":[]"), json);
    }

    @Test
    void escapesHostileDiagnosticTextAndRetainsWitness() {
        String json = StaticSafetyReport.render(List.of(
                new StaticSafetyAnalyzer.Finding("ORES-CB-002", "potential", "main",
                        "quote\" newline\n tab\t slash\\", List.of("event\\done"))));
        assertTrue(json.contains("ORES-CB-002"), json);
        assertTrue(json.contains("quote\\\" newline\\n tab\\t slash\\\\"), json);
        assertTrue(json.contains("event\\\\done"), json);
    }

    @Test
    void rejectsOversizedOutputInsteadOfSilentlyTruncatingIt() {
        var result = new ArrayList<StaticSafetyAnalyzer.Finding>();
        for (int n = 0; n < 10_001; n++)
            result.add(new StaticSafetyAnalyzer.Finding("ORES-TERM-002", "potential",
                    "main", "unbounded loop", List.of("main")));
        assertThrows(IllegalArgumentException.class, () -> StaticSafetyReport.render(result));
    }
}
