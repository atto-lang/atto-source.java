package dev.oreslang;

import dev.oreslang.types.DerivedCopyPlanV1;
import dev.oreslang.types.ShallowCopyLoanLedger;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class ShallowCopyLoanLedgerTest {
    private static DerivedCopyPlanV1.ShallowLoanLayout layout(String... fields) {
        var scalar = new DerivedCopyPlanV1.Scalar("int");
        var sequence = new DerivedCopyPlanV1.Sequence("Array", scalar);
        var members = java.util.Arrays.stream(fields)
                .map(f -> new DerivedCopyPlanV1.Field(f, sequence)).toList();
        var retained = java.util.Arrays.stream(fields)
                .map(f -> (DerivedCopyPlanV1.RetainedEdge) new DerivedCopyPlanV1.RetainedField(f, sequence)).toList();
        return new DerivedCopyPlanV1.ShallowLoanLayout(
                new DerivedCopyPlanV1.Instance("Person", members), retained);
    }

    private static void rejects(String code, Runnable action) {
        var ex = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(ex.getMessage().startsWith(code + ":"), ex::toString);
    }

    @Test void readLoanBlocksBothRootsAndKnownAliasButNotIndependentRegion() {
        var ledger = new ShallowCopyLoanLedger();
        var source = new Object(); var copy = new Object(); var alias = new Object();
        var independent = new Object(); var scores = new Object(); var other = new Object();
        ledger.registerRoot(source, layout("scores"), Map.of("scores", scores));
        ledger.registerRoot(alias, layout("differentName"), Map.of("differentName", scores));
        ledger.registerRoot(independent, layout("scores"), Map.of("scores", other));
        try (var scope = ledger.openScope()) {
            ledger.copyInto(scope, source, copy);
            rejects("E_COPY_READONLY", () -> ledger.requireDescendantWritable(source, "scores"));
            rejects("E_COPY_READONLY", () -> ledger.requireDescendantWritable(copy, "scores"));
            rejects("E_COPY_READONLY", () -> ledger.requireDescendantWritable(alias, "differentName"));
            rejects("E_COPY_LOAN", () -> ledger.requireTransferable(source));
            rejects("E_COPY_LOAN", () -> ledger.requireTransferable(copy));
            assertDoesNotThrow(() -> ledger.requireDescendantWritable(independent, "scores"));
        }
        assertDoesNotThrow(() -> ledger.requireDescendantWritable(source, "scores"));
        assertDoesNotThrow(() -> ledger.requireTransferable(alias));
        rejects("E_COPY_LOAN", () -> ledger.requireTransferable(copy));
    }

    @Test void nestedScopesAndCopiesOfCopiesRetainOuterLoan() {
        var ledger = new ShallowCopyLoanLedger();
        var a = new Object(); var b = new Object(); var c = new Object();
        ledger.registerRoot(a, layout("scores"), Map.of("scores", new Object()));
        try (var outer = ledger.openScope()) {
            ledger.copyInto(outer, a, b);
            try (var inner = ledger.openScope()) {
                ledger.copyInto(inner, b, c);
                rejects("E_COPY_LOAN", outer::close);
                rejects("E_COPY_READONLY", () -> ledger.requireDescendantWritable(c, "scores"));
            }
            rejects("E_COPY_READONLY", () -> ledger.requireDescendantWritable(a, "scores"));
            rejects("E_COPY_LOAN", () -> ledger.requireTransferable(c));
        }
        assertDoesNotThrow(() -> ledger.requireTransferable(a));
    }

    @Test void replacementAllowsFreshDescendantWritesWithoutThawingOldRegion() {
        var ledger = new ShallowCopyLoanLedger();
        var a = new Object(); var b = new Object(); var old = new Object(); var fresh = new Object();
        ledger.registerRoot(a, layout("scores"), Map.of("scores", old));
        try (var scope = ledger.openScope()) {
            ledger.copyInto(scope, a, b);
            rejects("E_COPY_LOAN", () -> ledger.replaceWithFresh(b, "scores", old));
            ledger.replaceWithFresh(b, "scores", fresh);
            assertDoesNotThrow(() -> ledger.requireDescendantWritable(b, "scores"));
            rejects("E_COPY_READONLY", () -> ledger.requireDescendantWritable(a, "scores"));
            rejects("E_COPY_LOAN", () -> ledger.requireTransferable(b));
            rejects("E_COPY_LOAN", () -> ledger.replaceWithFresh(a, "scores", fresh));
        }
        assertDoesNotThrow(() -> ledger.requireDescendantWritable(a, "scores"));
    }

    @Test void repeatedRegionIdentityCountsOnceAndCleanupSurvivesException() {
        var ledger = new ShallowCopyLoanLedger();
        var a = new Object(); var b = new Object(); var region = new Object();
        ledger.registerRoot(a, layout("left", "right"), Map.of("left", region, "right", region));
        assertThrows(IllegalStateException.class, () -> {
            try (var scope = ledger.openScope()) {
                ledger.copyInto(scope, a, b);
                rejects("E_COPY_READONLY", () -> ledger.requireDescendantWritable(a, "right"));
                throw new IllegalStateException("simulate compiler error");
            }
        });
        assertDoesNotThrow(() -> ledger.requireDescendantWritable(a, "left"));
        assertDoesNotThrow(() -> ledger.requireDescendantWritable(a, "right"));
    }

    @Test void rejectsMissingOrForgedProvenanceBeforeAcquisition() {
        var ledger = new ShallowCopyLoanLedger();
        var a = new Object(); var b = new Object(); var region = new Object();
        rejects("E_COPY_LOAN", () -> ledger.registerRoot(a, layout("scores"), Map.of()));
        ledger.registerRoot(a, layout("scores"), Map.of("scores", region));
        rejects("E_COPY_LOAN", () -> ledger.registerRoot(a, layout("scores"), Map.of("scores", region)));
        try (var scope = ledger.openScope()) {
            rejects("E_COPY_LOAN", () -> ledger.copyInto(scope, b, a));
            rejects("E_COPY_LOAN", () -> ledger.copyInto(scope, b, new Object()));
            assertDoesNotThrow(() -> ledger.requireTransferable(a));
            ledger.copyInto(scope, a, b);
            rejects("E_COPY_LOAN", () -> ledger.copyInto(scope, a, b));
        }
        assertDoesNotThrow(() -> ledger.requireTransferable(a));
    }

    @Test void equalButDistinctRegionObjectsAreNotConflated() {
        var ledger = new ShallowCopyLoanLedger();
        var a = new Object(); var b = new Object(); var c = new Object();
        ledger.registerRoot(a, layout("scores"), Map.of("scores", new String("region")));
        ledger.registerRoot(c, layout("scores"), Map.of("scores", new String("region")));
        try (var scope = ledger.openScope()) {
            ledger.copyInto(scope, a, b);
            assertDoesNotThrow(() -> ledger.requireDescendantWritable(c, "scores"));
        }
    }
}
