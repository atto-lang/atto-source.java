package dev.oreslang.prop;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes seeded property checks from stdlib/testing.ores (pure AttoLang)
 * through the real Java compiler/runtime.
 */
final class PropertyShrinkingStdlibTest {
    private static List<String> receipts() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                Path.of("examples/prop/property-shrinking.atto"),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(l -> l.startsWith("PROP|"))
                .toList();
    }

    private static String receipt(List<String> all, String name) {
        return all.stream().filter(l -> l.startsWith("PROP|" + name + "|")).findFirst()
                .orElseThrow(() -> new AssertionError("no receipt for " + name + " in " + all));
    }

    @Test
    void passingPropertyPassesAcrossEdgeCasesAndSeededDraws() throws Exception {
        assertTrue(receipt(receipts(), "square").equals("PROP|square|PASS"));
    }

    @Test
    void failingPropertyShrinksToExactBoundaryAndReportsSeed() throws Exception {
        String line = receipt(receipts(), "below100");
        assertTrue(line.startsWith("PROP|below100|FAIL|property below100 falsified: seed=42 "), line);
        assertTrue(line.contains(" shrunk=100 "), line);
    }

    @Test
    void sameSeedReplaysIdenticalCounterexample() throws Exception {
        List<String> all = receipts();
        String first = receipt(all, "below100");
        String replay = receipt(all, "below100-replay");
        assertEquals(first.substring(first.indexOf("|property ")),
                replay.substring(replay.indexOf("|property ")));
    }

    @Test
    void shrinksTowardNearestBoundWhenZeroIsOutOfRange() throws Exception {
        String line = receipt(receipts(), "negative");
        assertTrue(line.contains(" shrunk=-42 "), line);
    }

    @Test
    void invalidConfigFailsClosedWithoutRunningProperty() throws Exception {
        assertEquals("PROP|invalid|FAIL|prop: lo must be <= hi",
                receipt(receipts(), "invalid"));
    }

    @Test
    void everyCaseReportsExactlyOnce() throws Exception {
        assertEquals(5, receipts().size());
    }
}
