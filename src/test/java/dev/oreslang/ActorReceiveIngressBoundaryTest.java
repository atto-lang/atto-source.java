package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorReceiveIngressBoundaryTest {
    private static String fixture(String name) {
        String path = "/atto/actor-ingress/" + name + ".atto";
        try (var in = ActorReceiveIngressBoundaryTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing AttoLang regression fixture: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void mustReject(String name, String diagnostic) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(fixture(name))),
                name);
        assertTrue(error.getMessage().contains(diagnostic),
                () -> name + ": " + error.getMessage());
    }

    @Test
    void receiveCannotBeInvokedFromActorSelf() {
        mustReject("negative-self-receive", "reserved actor lifecycle hook");
    }

    @Test
    void receiveCannotBeInvokedImplicitlyInsideItsActor() {
        mustReject("negative-unqualified-receive", "runtime-only");
    }

    @Test
    void aResolvedActorReceiverCannotBypassIngressEvenOutsideItsClass() {
        mustReject("negative-typed-actor-receiver", "runtime-only");
    }

    @Test
    void actorRefDoesNotExposeReceiveAsPublicCall() {
        mustReject("negative-actor-ref-receive", "unknown ActorRef callable member 'receive'");
    }

    @Test
    void receiveCannotBeCapturedAsFirstClassCallback() {
        mustReject("negative-captured-receive", "runtime-only");
    }

    @Test
    void externalMailboxSendRemainsLegal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                fixture("positive-mailbox-send"))));
    }

    @Test
    void ordinaryClassesMayUseReceiveAsAnOrdinaryMethod() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                fixture("positive-nonactor-receive"))));
    }
}
