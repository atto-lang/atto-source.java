package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static dev.oreslang.runtime.ActorEmitPipelineContractV3.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiler-contract tests, NOT source-compiled actor execution. */
final class ActorEmitPipelineContractV3Test {
    private static Stage actor(String id, String name, String input, String... emitted) {
        return new Stage(id, name, input, List.of(emitted), false);
    }

    private static void rejected(List<Stage> stages, String code) {
        var e = assertThrows(IllegalArgumentException.class, () -> validate(stages));
        assertTrue(e.getMessage().startsWith(code + ":"), e::getMessage);
    }

    @Test void oneChainWithStaticEmitAndTerminalSink() {
        var links = validate(List.of(
                actor("p0", "Parse", "int", "int"),
                actor("v0", "Validate", "int", "int", "int"),
                actor("s0", "Sink", "int")));
        assertEquals(List.of(new Link("p0", "v0", "int"),
                new Link("v0", "s0", "int")), links);
    }

    @Test void outputMayDifferFromInputAndSameNominalActorMayBeInstantiatedTwice() {
        var links = validate(List.of(
                actor("parse-0", "Worker", "int", "bool"),
                actor("validate-1", "Worker", "bool", "bool"),
                actor("sink-2", "Sink", "bool")));
        assertEquals("bool", links.get(0).payloadType());
        assertEquals("bool", links.get(1).payloadType());
        assertEquals("parse-0", links.get(0).source());
        assertEquals("validate-1", links.get(1).target());
    }

    @Test void refusesInconsistentEmitValuesAndSourceSinkInversion() {
        rejected(List.of(
                actor("a", "Parse", "int", "int", "bool"),
                actor("b", "Sink", "int")), "E_ACTOR_PIPE_EMIT_TYPE");
        rejected(List.of(
                actor("a", "Parse", "int", "bool"),
                actor("b", "Sink", "int")), "E_ACTOR_PIPE_EMIT_TYPE");
        rejected(List.of(
                actor("a", "Sink", "int"),
                actor("b", "Validate", "int")), "E_ACTOR_PIPE_STAGE_NO_EMIT");
        rejected(List.of(
                actor("a", "Parse", "int", "int"),
                actor("b", "Sink", "int", "int")), "E_ACTOR_PIPE_SINK_EMIT");
    }

    @Test void cannotDuplicateInstanceOrSmuggleSupervisorIntoDataPath() {
        rejected(List.of(actor("same", "Parse", "int", "int"),
                actor("same", "Sink", "int")), "E_ACTOR_PIPE_DUPLICATE_INSTANCE");
        rejected(List.of(actor("a", "Parse", "int", "int"),
                new Stage("b", "Supervisor", "int", List.of(), true)),
                "E_ACTOR_PIPE_SUPERVISOR");
    }

    @Test void scalarDomainAndIdentityAreClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> actor("a", "Parse", "string", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> actor("a", "Parse", "int", "float"));
        assertThrows(IllegalArgumentException.class,
                () -> actor("", "Parse", "int", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> actor("bad\0id", "Parse", "int", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> actor("a", new String(new char[]{(char) 0xD800}), "int", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> actor("x".repeat(257), "Parse", "int", "int"));
        assertThrows(IllegalArgumentException.class,
                () -> actor("a", "🌍".repeat(65), "int", "int"));
    }

    @Test void strictUtf8IdentityBoundaryMatchesNativeAndDotNet() {
        // 256 ASCII characters and 64 four-byte Unicode scalars are valid.
        assertEquals(1, validate(List.of(
                actor("x".repeat(256), "Parse", "int", "bool"),
                actor("sink", "Sink", "bool"))).size());
        assertEquals(1, validate(List.of(
                actor("🌍".repeat(64), "Parse", "int", "bool"),
                actor("sink", "Sink", "bool"))).size());

        // The 257th byte, NUL and malformed UTF-16 remain rejected.
        assertThrows(IllegalArgumentException.class,
                () -> actor("x".repeat(257), "Parse", "int", "bool"));
        assertThrows(IllegalArgumentException.class,
                () -> actor("🌍".repeat(65), "Parse", "int", "bool"));
        assertThrows(IllegalArgumentException.class,
                () -> actor(new String(new char[]{'a', 'b', 'c', (char) 0, 'd', 'e', 'f'}), "Parse", "int", "bool"));
        assertThrows(IllegalArgumentException.class,
                () -> actor(new String(new char[]{'x', (char) 0xD800}), "Parse", "int", "bool"));

        // A hostile million-character identifier must be rejected before
        // allocating an encoder buffer proportional to the input length.
        var failure = assertThrows(IllegalArgumentException.class,
                () -> actor("x".repeat(1_000_000), "Parse", "int", "bool"));
        assertTrue(failure.getMessage().startsWith("E_ACTOR_PIPE_IDENTITY:"));
    }

    @Test void topologyCannotExceedTenantBudgetAndReusedArrayIsNotAuthoritative() {
        rejected(List.of(actor("a", "A", "int", "int")),
                "E_ACTOR_PIPE_LIMIT");
        var stages = new ArrayList<Stage>();
        for (int i = 0; i < 17; i++)
            stages.add(actor("instance" + i, "Worker", "int", i == 16 ? new String[0] : new String[]{"int"}));
        rejected(stages, "E_ACTOR_PIPE_LIMIT");

        var mutable = new ArrayList<>(List.of("int"));
        var snap = new Stage("a", "Parse", "int", mutable, false);
        mutable.add("bool");
        assertEquals(List.of("int"), snap.emitTypes());
        assertThrows(UnsupportedOperationException.class, () -> snap.emitTypes().add("bool"));
    }

    @Test void emittedSiteBudgetRejectsUnboundedCompilerEvidenceBeforeCopy() {
        assertEquals(MAX_EMIT_OBSERVATIONS, 1024);
        assertEquals(1, validate(List.of(
                new Stage("source", "Parse", "int",
                        java.util.Collections.nCopies(MAX_EMIT_OBSERVATIONS, "bool"), false),
                actor("sink", "Sink", "bool"))).size());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new Stage("source", "Parse", "int",
                        java.util.Collections.nCopies(MAX_EMIT_OBSERVATIONS + 1, "bool"), false));
        assertTrue(failure.getMessage().startsWith("E_ACTOR_PIPE_EMIT_LIMIT:"));
        // Invalid evidence cannot bypass the budget by placing the error
        // after a thousand otherwise-consistent emit observations.
        failure = assertThrows(IllegalArgumentException.class,
                () -> new Stage("source", "Parse", "int",
                        java.util.Collections.nCopies(1_000_000, "int"), false));
        assertTrue(failure.getMessage().startsWith("E_ACTOR_PIPE_EMIT_LIMIT:"));
    }

    @Test void differentialMutationCannotCreateImplicitOutputFromInput() {
        for (int n = 2; n <= 16; n++) {
            var stages = new ArrayList<Stage>();
            for (int i = 0; i < n; i++)
                stages.add(actor("instance-" + i, "Worker", i == 0 ? "int" : "bool",
                        i == n - 1 ? new String[0] : new String[]{"bool"}));
            assertEquals(n - 1, validate(stages).size());
            stages.set(1, actor("instance-1", "Worker", "int",
                    n == 2 ? new String[0] : new String[]{"bool"}));
            rejected(stages, "E_ACTOR_PIPE_EMIT_TYPE");
        }
    }
}
