package dev.oreslang.compiler;

import dev.oreslang.compiler.DiagnosticCollector.Diagnostic;
import dev.oreslang.compiler.DiagnosticCollector.Phase;
import dev.oreslang.compiler.DiagnosticCollector.Related;
import dev.oreslang.compiler.DiagnosticCollector.Span;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DiagnosticCollectorTest {
    private static Diagnostic error(int number, String rootId) {
        return new Diagnostic("ORES-PARSE-001", Phase.PARSE,
                new Span("src/module.ores", number, 1, number, 1),
                "expected expression", rootId, List.of(), "");
    }

    @Test
    void retainsEarliestFiveIndependentlyOfDiscoveryOrder() {
        var collector = new DiagnosticCollector();
        for (int line = 6; line >= 1; line--)
            collector.add(error(line, "node-" + line));

        assertEquals(5, collector.diagnostics().size());
        assertEquals(List.of(1, 2, 3, 4, 5),
                collector.diagnostics().stream().map(d -> d.primary().line()).toList());
        assertTrue(collector.mayHaveMore());
        assertTrue(collector.atLimit());
    }

    @Test
    void maxOneMaxTenAndUnboundedWithHardCap() {
        var one = new DiagnosticCollector(1);
        var ten = new DiagnosticCollector(10);
        for (int n = 1; n <= 6; n++) {
            one.add(error(n, "one-" + n));
            ten.add(error(n, "ten-" + n));
        }
        assertEquals(1, one.diagnostics().size());
        assertEquals(6, ten.diagnostics().size());
        assertTrue(one.mayHaveMore());
        assertFalse(ten.mayHaveMore());
        assertEquals(1000, new DiagnosticCollector(0).effectiveMaxErrors());
        assertEquals(0, new DiagnosticCollector(0).requestedMaxErrors());
    }

    @Test
    void distinctLocationsWithIdenticalMessagesAreIndependent() {
        var collector = new DiagnosticCollector();
        assertTrue(collector.add(error(1, "node-a")));
        assertTrue(collector.add(error(1, "node-b")));
        assertFalse(collector.add(error(2, "node-a")));
        assertEquals(2, collector.diagnostics().size());
        assertFalse(collector.mayHaveMore());
    }

    @Test
    void validatesLimitsWithoutOverflowOrAmbientFlags() {
        assertEquals(5, DiagnosticCollector.parseMaxErrors("5"));
        assertEquals(0, DiagnosticCollector.parseMaxErrors("0"));
        assertEquals(1000, DiagnosticCollector.parseMaxErrors("1000"));
        for (String value : List.of("-1", "+2", "1.5", "9999999999999", "1001", "", "01", " 5"))
            assertThrows(IllegalArgumentException.class,
                    () -> DiagnosticCollector.parseMaxErrors(value));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticCollector(-1));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticCollector(1001));
    }

    @Test
    void validatesHalfOpenRangesAndRelatedResourceLimits() {
        assertThrows(IllegalArgumentException.class, () -> new Span("test.ores", 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Span("test.ores", 2, 4, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new Span("", 1, 1, 1, 1));
        Span where = new Span("test.ores", 1, 1, 1, 2);
        var sourceRelated = new ArrayList<Related>();
        for (int i = 0; i <= DiagnosticCollector.MAX_RELATED; i++)
            sourceRelated.add(new Related(where, "root"));
        assertThrows(IllegalArgumentException.class,
                () -> new Diagnostic("ORES-TYPE-001", Phase.TYPE, where,
                        "problem", "node", sourceRelated, ""));
        var diagnostic = new Diagnostic("ORES-TYPE-001", Phase.TYPE, where,
                "x".repeat(6000), "node", List.of(), "");
        assertTrue(diagnostic.message().length() <= 2048);
    }

    @Test
    void aDiscoveryBudgetStopNeverClaimsExactHiddenTotals() {
        var collector = new DiagnosticCollector(5);
        collector.add(error(2, "node-two"));
        collector.markDiscoveryIncomplete();
        assertEquals(1, collector.diagnostics().size());
        assertTrue(collector.mayHaveMore());
    }
}
