package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class BoundDeferFactoryExecutionTest {
    private static Source fixture(String name) throws Exception {
        try (var stream = BoundDeferFactoryExecutionTest.class.getResourceAsStream("/defer/" + name)) {
            assertNotNull(stream, name);
            return Source.newBuilder(OresLanguage.ID,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), name)
                    .mimeType(OresLanguage.MIME_TYPE).build();
        }
    }

    @Test
    void factoryBindsBeforeBodyButOrdinaryCallOperandsRemainDelayed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(true).out(out).build()) {
            Value result = context.parse(fixture("bound_factory.atto")).execute(
                    OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "main");
            assertEquals(42, result.asInt());
            assertEquals("factory\nbody\nlast\n5\noperand\n2\n",
                    out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void sourceTaskBindsAndInvokesCallbackBeforeCompletion() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(true).out(out).build()) {
            // Observe completion inside the guest: exposing the raw OresFuture
            // as a Polyglot return value violates its host interop contract.
            // This test covers source-task cleanup, not host Future projection.
            context.eval(fixture("bound_factory_task.atto"));
            assertEquals("factory\nbody\n5\n42\n", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void failingArgumentDoesNotPublishPartialRegistration() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(true).out(out).build()) {
            var call = context.parse(fixture("bound_factory_registration_failure.atto"));
            assertThrows(RuntimeException.class, () -> call.execute(
                    OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "main"));
            assertEquals("factory\nolder\n", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void sharedPipelineFactoryBindsScalarChainAndTupleBeforeBody() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(true).out(out).build()) {
            Value result = context.parse(fixture("pipeline-defer.atto")).execute(
                    OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "main");
            assertEquals(42, result.asInt());
            assertEquals("seed\ntwice\nbody\ntuple\n7\n10\n",
                    out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void sharedPipelineArityFixtureRejectsBeforeGuestExecution() throws Exception {
        String source = fixture("pipeline-reject-arity.atto").getCharacters().toString();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("nullary braced cleanup"), failure::toString);
    }

    @Test
    void pipelineFactoryRejectsWrongTypeAndNonNullaryCleanup() {
        for (String registration : new String[] {
                "defer \"wrong\" |> (|int x| -> || -> { stdio.println(x); });",
                "defer 5 |> (|int x| -> |int other| -> { stdio.println(other); });",
                "defer 5 |> (|int x| -> || -> { return 1; });",
                "defer 5 |> (|int x| -> { stdio.println(x); });"
        }) {
            String source = "pub fnc main(): int { " + registration + " return 42; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), registration);
        }
    }

    @Test
    void pipelineRegistrationFailurePreservesOlderCleanup() throws Exception {
        String guest = """
                fnc seed(int divisor): int {
                    stdio.println("factory");
                    return Integer.rem(1, divisor);
                }
                pub fnc main(): int {
                    defer stdio.println("older");
                    defer seed(0) |> (|int x| -> || -> { stdio.println("INVALID"); });
                    stdio.println("INVALID-body");
                    return 42;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(true).out(out).build()) {
            var call = context.parse(Source.newBuilder(OresLanguage.ID, guest, "pipeline-failure.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            assertThrows(RuntimeException.class, () -> call.execute(
                    OresEvalRootNode.INVOKE_PUBLIC_COMMAND, "main"));
            assertEquals("factory\nolder\n", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsExternalCaptureBeforeExecution() {
        String source = """
                pub fnc main(): int {
                    const int outer_value = 7;
                    defer (|int value| -> || -> { stdio.println(outer_value + value); })(5);
                    return 42;
                }
                """;
        var program = TypeChecker.checkTypes(Parser.parse(source));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> OwnershipChecker.check(program));
        assertTrue(failure.getMessage().contains("explicit scalar parameters"), failure::toString);
    }

    @Test
    void rejectsNonNullaryAndNonVoidCallbacks() {
        for (String body : new String[]{
                "|int other| -> { stdio.println(other); }",
                "|| -> { return 1; }"
        }) {
            String source = """
                    pub fnc main(): int {
                        defer (|int value| -> %s)(5);
                        return 42;
                    }
                    """.formatted(body);
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
        }
    }
}
