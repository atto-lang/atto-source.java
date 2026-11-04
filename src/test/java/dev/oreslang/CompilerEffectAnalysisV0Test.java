package dev.oreslang;

import dev.oreslang.compiler.CompilerEffectAnalysisV0;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-backed scalar analysis v0; NOT an exceptional-effect safety proof. */
final class CompilerEffectAnalysisV0Test {

    private static CompilerEffectAnalysisV0.Report admitted(String source) {
        return CompilerEffectAnalysisV0.analyze(OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void realFrontendAdmitsLiteralAndDirectCallsWithoutExecutingGuest() {
        for (String source : List.of(
                "pub fnc main(): int { return 42; }",
                "pub fnc main(): bool { return true; }",
                "fnc identity(int value): int { return value; } "
                    + "pub fnc main(): int { return identity(7); }",
                "fnc helper(): int { return 7; } "
                    + "pub fnc main(): int { return helper(); }")) {
            var report = admitted(source);
            assertEquals(0, report.exitStatus(), report.records().toString());
            assertFalse(report.records().isEmpty());
            report.records().forEach(record -> {
                assertTrue(record.startsWith("ORES-ANALYZE-V0\t"), record);
                assertTrue(record.contains("\tstatus=known\tknown_effects=none"), record);
            });
        }
    }

    @Test
    void checkedArithmeticIsNotCertifiedEffectFree() {
        var result = admitted("pub fnc main(): int { return 1 + 2; }");
        assertEquals(2, result.exitStatus());
        assertTrue(result.records().stream().anyMatch(
                record -> record.contains("status=inconclusive")));
        assertFalse(result.records().stream().anyMatch(
                record -> record.contains("status=known")));
    }

    @Test
    void unknownSyntaxShapesAndRecursiveCallsNeverProduceKnownReceipts() {
        for (String source : List.of(
                "fnc recurse(): int { return recurse(); } "
                    + "pub fnc main(): int { return recurse(); }",
                "pub fnc main(): int { return unresolved(); }",
                "fnc helper(): int { return 1 + 2; } "
                    + "pub fnc main(): int { return helper(); }")) {
            // Parser-only here: an actual source admission failure is ALSO
            // fail-closed, not an excuse to infer a trusted effect summary.
            var report = CompilerEffectAnalysisV0.analyze(Parser.parse(source));
            assertEquals(2, report.exitStatus(), source);
            assertFalse(report.records().stream().anyMatch(
                    record -> record.contains("status=known")), source);
        }
    }

    @Test
    void longCallableIdentitiesCannotBeCertifiedByTruncatedReports() {
        String longName = "n".repeat(260);
        String source = "fnc " + longName + "(): int { return 42; } "
                + "pub fnc main(): int { return " + longName + "(); }";
        // Rejection at lexical admission is acceptable; if parsed, the
        // effect receipt itself must remain explicitly inconclusive.
        try {
            var report = CompilerEffectAnalysisV0.analyze(Parser.parse(source));
            assertEquals(2, report.exitStatus());
            assertFalse(report.records().stream().anyMatch(
                    record -> record.contains("status=known")));
        } catch (IllegalArgumentException unsupportedName) {
            assertNotNull(unsupportedName.getMessage());
        }
    }

    @Test
    void invalidGuestSourceCannotBeAdmittedByRealTypechecker() {
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "pub fnc main(): int { return unresolved(); }"));
    }

    @Test
    void emptySourceAndUnsupportedDeclarationsFailClosed() {
        var empty = CompilerEffectAnalysisV0.analyze(Parser.parse(""));
        assertEquals(2, empty.exitStatus());
        assertTrue(empty.records().stream().anyMatch(
                record -> record.contains("reason=no-callables")));
        var unsupported = CompilerEffectAnalysisV0.analyze(Parser.parse(
                "define class Box as pub let int value = 0; end "
                    + "pub fnc main(): int { return 42; }"));
        assertEquals(2, unsupported.exitStatus());
    }
}
