package dev.oreslang;

import dev.oreslang.compiler.ModelRunFencing;
import dev.oreslang.compiler.ModelRunFencing.Ledger;
import dev.oreslang.compiler.ModelRunFencing.Owner;
import dev.oreslang.compiler.ModelRunFencing.RunStamp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ModelRunFencingTest {
    private static final String G = "a".repeat(64);
    private static final String P = "b".repeat(64);

    private static Owner owner() {
        return new Owner("app::Tax@v1", G, 7, "instance-1", 11L, P);
    }

    @Test
    void validatesIdentityAndCounters() {
        RunStamp good = new RunStamp("run-1", "app::Tax@v1", G, 7, "instance-1", 11L, P);
        Ledger ledger = ModelRunFencing.validateAndCommit(owner(), good, Ledger.empty());
        assertTrue(ledger.committedRunIds().contains("run-1"));
        assertEquals(8, ModelRunFencing.nextGraphGeneration(7));
        assertEquals(12, ModelRunFencing.nextParameterVersion(11));
        assertNotEquals(ModelRunFencing.planCacheKey(G, P),
                ModelRunFencing.planCacheKey(G, "c".repeat(64)));

        assertEquals("MODEL_RUN_REPLAY",
                assertThrows(IllegalStateException.class,
                        () -> ModelRunFencing.validateAndCommit(owner(), good, ledger)).getMessage());
        assertEquals("MODEL_GRAPH_GENERATION_MISMATCH",
                rejected(new RunStamp("run-2", "app::Tax@v1", G, 8, "instance-1", 11L, P)));
        assertEquals("MODEL_PARAMETER_VERSION_MISMATCH",
                rejected(new RunStamp("run-3", "app::Tax@v1", G, 7, "instance-1", 12L, P)));
        assertEquals("MODEL_EXECUTION_PROFILE_MISMATCH",
                rejected(new RunStamp("run-4", "app::Tax@v1", G, 7, "instance-1", 11, "c".repeat(64))));
    }

    @Test
    void versionCountersFailOnOverflow() {
        assertEquals("MODEL_GRAPH_GENERATION_OVERFLOW",
                assertThrows(IllegalStateException.class,
                        () -> ModelRunFencing.nextGraphGeneration(Long.MAX_VALUE)).getMessage());
        assertEquals("MODEL_PARAMETER_VERSION_OVERFLOW",
                assertThrows(IllegalStateException.class,
                        () -> ModelRunFencing.nextParameterVersion(Long.MAX_VALUE)).getMessage());
    }

    private static String rejected(RunStamp stamp) {
        return assertThrows(IllegalStateException.class,
                () -> ModelRunFencing.validateAndCommit(owner(), stamp, Ledger.empty())).getMessage();
    }
    @Test
    void statelessInstancesFenceWithNoParameterVersion() {
        Owner stateless = new Owner("finance.PriceEstimate@v1", G, 1,
                "instance-1", null, P);
        RunStamp good = new RunStamp("run-stateless", "finance.PriceEstimate@v1",
                G, 1, "instance-1", null, P);
        assertTrue(ModelRunFencing.validateAndCommit(stateless, good, Ledger.empty())
                .committedRunIds().contains("run-stateless"));

        RunStamp wronglyStateful = new RunStamp("run-stateful", "finance.PriceEstimate@v1",
                G, 1, "instance-1", 0L, P);
        assertEquals("MODEL_PARAMETER_VERSION_MISMATCH", rejected(stateless, wronglyStateful));
    }

    @Test
    void identityGrammarMatchesPortableNominalIds() {
        assertDoesNotThrow(() -> new Owner("finance.PriceEstimate@v1", G, 0,
                "instance-1", null, P));
        assertEquals("MODEL_INVALID_MODEL_TYPE_ID",
                assertThrows(IllegalArgumentException.class,
                        () -> new Owner(" bad id ", G, 0, "instance-1", null, P)).getMessage());
    }

    private static String rejected(Owner owner, RunStamp stamp) {
        return assertThrows(IllegalStateException.class,
                () -> ModelRunFencing.validateAndCommit(owner, stamp, Ledger.empty())).getMessage();
    }


}
