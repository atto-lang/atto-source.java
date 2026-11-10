package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed source tests for the exact ActorRef<Actor> parameter
 * boundary. Interface/virtual-actor RPC is deliberately not admitted here.
 */
final class ActorRefParameterContractTest {
    private static final Path FIXTURES =
            Path.of("tests", "fixtures", "actor-ref-contract-v1");

    private static String source(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void concreteActorReferenceFlowsIntoOrdinaryFunction() throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(
                Parser.parse(source("accept-typed-function.atto"))));
    }

    @Test
    void transparentActorReferenceAliasPreservesMintedHandleTyping() throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(
                Parser.parse(source("accept-alias-typed-function.atto"))));
    }

    @Test
    void typedFunctionActorSendExecutesThroughGuestMailbox() throws Exception {
        String program = source("accept-typed-function.atto");
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "accept-typed-function.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("actorref-ok", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsNonActorReferenceTargetsBeforeExecution() throws Exception {
        expectRejected("reject-primitive-target.atto", "ActorRef target");
        expectRejected("reject-class-target.atto", "ActorRef target");
        expectRejected("reject-interface-target.atto", "ActorRef target");
        expectRejected("reject-unbound-generic-target.atto",
                "fully resolved nominal actor type arguments");
    }

    @Test
    void actorReferenceTypeArgumentIsExplicitAndUnique() throws Exception {
        expectRejected("reject-missing-type-argument.atto",
                "requires exactly one explicit actor type argument");
        expectRejected("reject-multiple-type-arguments.atto",
                "requires exactly one explicit actor type argument");
    }

    @Test
    void actorReferencesCannotBeConstructedAsObjectsOrThroughAliases() throws Exception {
        expectRejected("reject-new-actor-ref.atto",
                "ActorRef capabilities cannot be constructed with new");
        expectRejected("reject-alias-new-actor-ref.atto",
                "ActorRef capabilities cannot be constructed with new");
    }

    @Test
    void genericValuesCannotImpersonateActorReferenceCapabilities() throws Exception {
        expectRejected("reject-generic-capability-coercion.atto", "argument 1");
        expectRejected("reject-nested-generic-capability-coercion.atto", "argument 1");
    }

    @Test
    void builtInActorReferenceNameCannotBeShadowed() throws Exception {
        expectRejected("reject-ref-shadow-class.atto",
                "ActorRef is a reserved nominal built-in");
        expectRejected("reject-ref-shadow-interface.atto",
                "ActorRef is a reserved nominal built-in");
        expectRejected("reject-ref-shadow-alias.atto",
                "ActorRef is a reserved nominal built-in");
    }

    @Test
    void actorReferencesCannotInvokeActorLocalMethods() throws Exception {
        expectRejected("reject-direct-actor-method.atto",
                "unknown ActorRef callable member");
    }

    @Test
    void selfIsNotAnActorReferenceAndActorTypesAreInvariant() throws Exception {
        expectRejected("reject-self-as-reference.atto", null);
        expectRejected("reject-other-actor-type.atto", null);
    }

    private static void expectRejected(String fixture, String expectedMessage)
            throws Exception {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source(fixture))),
                "Expected source to be rejected: " + fixture);
        if (expectedMessage != null) {
            assertTrue(failure.getMessage().contains(expectedMessage),
                    () -> fixture + ": " + failure.getMessage());
        }
    }
}
