package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

final class AuthenticatedActorStreamSourceTest {
    private static String fixture() throws Exception {
        return Files.readString(
                Path.of("examples/actorstream-authenticated-linear.ores"));
    }

    private static String execute(String source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, source, "authenticated-actorstream.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void compiledThreeActorSourceRoundTripsThroughAuthenticatedFrames()
            throws Exception {
        // 1 -> 2 -> 3; sink emits -4; transform emits -5; source prints -5.
        assertEquals("-5", execute(fixture()));
    }

    @Test
    void infixPipeUsesTheSameAuthenticatedRouter() throws Exception {
        String source = fixture().replace(
                "ActorUtils.pipe(\n        spawn Source(),\n        spawn Transform(),\n        spawn Sink()\n    )",
                "spawn Source() pipe spawn Transform() pipe spawn Sink()");
        assertNotEquals(fixture(), source);
        assertEquals("-5", execute(source));
    }

    @Test
    void mismatchedMailboxDomainsFailBeforeActorsAreSpawned() throws Exception {
        String source = fixture().replace(
                "define actor Sink as\n    receive(ActorMail<int> mail): void",
                "define actor Sink as\n    receive(ActorMail<string> mail): void");
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().contains("domains must match exactly"),
                rejected.toString());
    }

    @Test
    void isolatedStageIsRejectedBeforeRuntimeBinding() throws Exception {
        String source = fixture().replace(
                "define actor Transform as",
                "define isoactor Transform as");
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().contains("requires SHARED actors"),
                rejected.toString());
    }

    @Test
    void actorCodeCannotConstructANestedActorStream() throws Exception {
        String source = fixture().replace(
                "self.forward(mail.value + 1);",
                "val nested = ActorUtils.pipe(spawn Transform(), spawn Sink());\n"
                        + "            self.forward(mail.value + 1);");
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().contains("non-actor supervisor/root turn"),
                rejected.toString());
    }

    @Test
    void oversizedInfixPipeFailsBeforeEvaluatingSpawnExpressions()
            throws Exception {
        StringBuilder stages = new StringBuilder("spawn Source()");
        for (int i = 0; i < 16; i++) {
            stages.append(" pipe spawn Transform()");
        }
        stages.append(" pipe spawn Sink()");
        String source = fixture().replace(
                "ActorUtils.pipe(\n        spawn Source(),\n        spawn Transform(),\n        spawn Sink()\n    )",
                stages.toString());
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().contains("2..16"),
                rejected.toString());
    }

    @Test
    void streamIngressIsSingleFlowAndReplayFailsClosed() throws Exception {
        String source = fixture().replace(
                "route.send(1);",
                "route.send(1);\n    route.send(1);");
        PolyglotException rejected = assertThrows(
                PolyglotException.class,
                () -> execute(source));
        assertTrue(rejected.toString().contains("exactly one root flow"),
                rejected.toString());
    }

    @Test
    void failedStageTerminatesIdleSiblingsAndDoneDoesNotHang() throws Exception {
        String source = fixture().replace(
                "self.backward(0 - mail.value - 1);",
                "self.forward(mail.value);");
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            PolyglotException rejected = assertThrows(
                    PolyglotException.class,
                    () -> execute(source));
            assertTrue(
                    rejected.toString().contains("source has no connected downstream consumers")
                            || rejected.toString().contains("ActorStream stage failed"),
                    rejected.toString());
        });
    }

    @Test
    void duplicatePreexistingActorIsRejectedAfterInlineSpawnInFunctionForm()
            throws Exception {
        String source = fixture().replace(
                "return ActorUtils.pipe(\n        spawn Source(),\n        spawn Transform(),\n        spawn Sink()\n    );",
                "val retained = spawn Source();\n"
                        + "    return ActorUtils.pipe(spawn Source(), retained, retained);");
        assertNotEquals(fixture(), source);
        // The first inline actor is owned by this attempted construction;
        // 'retained' was created separately and must not be cancelled by
        // construction rollback.
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            PolyglotException rejected = assertThrows(
                    PolyglotException.class, () -> execute(source));
            assertTrue(rejected.toString().contains("distinct same-runtime SHARED"),
                    rejected::toString);
        });
    }

    @Test
    void duplicatePreexistingActorIsRejectedAfterInlineSpawnInInfixForm()
            throws Exception {
        String source = fixture().replace(
                "return ActorUtils.pipe(\n        spawn Source(),\n        spawn Transform(),\n        spawn Sink()\n    );",
                "val retained = spawn Source();\n"
                        + "    return spawn Source() pipe retained pipe retained;");
        assertNotEquals(fixture(), source);
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            PolyglotException rejected = assertThrows(
                    PolyglotException.class, () -> execute(source));
            assertTrue(rejected.toString().contains("distinct same-runtime SHARED"),
                    rejected::toString);
        });
    }

    @Test
    void nonActorStageCannotBorrowAuthenticatedActorPipeAuthority() throws Exception {
        String source = fixture().replace(
                "ActorUtils.pipe(\n        spawn Source(),\n        spawn Transform(),\n        spawn Sink()\n    )",
                "ActorUtils.pipe(spawn Source(), 42, spawn Sink())");
        assertNotEquals(fixture(), source);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().contains("statically typed ActorRef<T>"),
                rejected.toString());
    }

    @Test
    void reservedPipelineTypesCannotBeRebound() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class ActorStream as
                        end
                        pub fnc main(): int { return 1; }
                        """)));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class ActorUtils as
                        end
                        pub fnc main(): int { return 1; }
                        """)));
    }
    @Test
    void requiresExplicitConcreteActorStreamReturnDomain() throws Exception {
        // The compiled graph is ActorStream<int>, not an erased any stream.
        // Allowing a dynamic return signature here could bypass its checked
        // send(T) boundary after the local capability escapes the factory.
        String source = fixture().replace(
                "make_actor_pipeline(): ActorStream<int>",
                "make_actor_pipeline(): ActorStream<any>");
        assertNotEquals(fixture(), source);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

}
