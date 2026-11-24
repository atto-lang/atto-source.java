package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 1: executable receives checking, parsed but denied sends effects. */
final class ActorMessageContractV1Test {
    private static Ast.ClassDecl actor(String declaration) {
        return (Ast.ClassDecl) Parser.parse(declaration)
                .modules().getFirst().declarations().getFirst();
    }

    @Test void receivesHeaderIsTypedAgainstExistingMailbox() {
        String source = """
                define actor Worker receives String as
                  receive(ActorMail<String> mail): void { return; }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send("ok");
                  return;
                }
                """;
        assertEquals("String", actor(source).actorMessageContract().receives().name());
        assertNull(actor(source).actorMessageContract().sends());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test void sourceActorRefStillRejectsWrongPayload() {
        String source = """
                define actor Worker receives String as
                  receive(ActorMail<String> mail): void { return; }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send(123);
                  return;
                }
                """;
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(bad.getMessage().contains("mailbox value"), bad::getMessage);
    }

    @Test void declaredReceivesMustExactlyMatchHandler() {
        String source = """
                define actor Worker receives int as
                  receive(ActorMail<String> mail): void { return; }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test void receivesCannotCreateMailboxWithoutHandler() {
        String source = "define actor Empty receives String as end";
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(bad.getMessage().contains("receive(ActorMail<T>)"), bad::getMessage);
    }

    @Test void sendsIsPreservedInAstButFailsClosedUntilTransitiveEffectChecker() {
        String source = """
                define actor Router receives String sends int | bool as
                  receive(ActorMail<String> mail): void { return; }
                end
                """;
        Ast.ActorMessageContract contract = actor(source).actorMessageContract();
        assertNotNull(contract.receives());
        assertTrue(contract.sends().isUnion());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(bad.getMessage().contains("ACTOR_SENDS_EFFECT_UNSUPPORTED"),
                bad::getMessage);
    }

    @Test void headerOrderIsNotARecipientCount() {
        Ast.ActorMessageContract contract = actor("""
                define actor Router sends int receives String as end
                """).actorMessageContract();
        assertEquals("String", contract.receives().name());
        assertEquals("int", contract.sends().name());
    }

    @Test void duplicateClausesAreRejectedBeforeAnyLowering() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define actor Worker receives String receives int as end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define actor Worker sends String sends int as end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "declare actor Worker emits String emits int;"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define actor Worker receives String;"));
    }

    @Test void declaredActorPreservesAllContractTypesAndStillChecksSends() {
        String source = """
                declare actor Router sends bool emits int receives String;
                mtd (Router self) receive(ActorMail<String> mail): void { self.send(1); }
                """;
        Ast.ActorMessageContract protocol = actor(source).actorMessageContract();
        assertEquals("String", protocol.receives().name());
        assertEquals("bool", protocol.sends().name());
        assertEquals("int", protocol.emits().name());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(denied.getMessage().contains("ACTOR_SENDS_EFFECT_UNSUPPORTED"));
    }

    @Test void emitsChecksTheValueAndTypesTheConsumerSide() {
        String source = """
                define actor Worker emits int as
                  receive(ActorMail<String> mail): void { self.send(4); }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  val ActorOutputStream<int> outputs = worker.outputs;
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source.replace("self.send(4)", "self.send(\"bad\")"))));
        assertTrue(denied.getMessage().contains("emits contract"));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                source.replace("ActorOutputStream<int>", "ActorOutputStream<String>"))));
    }

    @Test void inheritedOutputContractCannotBeErasedOrInvented() {
        String source = """
                define actor Base emits int as
                  receive(ActorMail<String> mail): void { self.send(4); }
                end
                define actor Child emits int extends Base as end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        for (String child : List.of("Child", "Child emits String")) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source.replace("Child emits int", child))));
            assertTrue(denied.getMessage().contains("emits contract must be preserved"));
        }
    }

    @Test void annotationsDoNotDiscardProtocolMetadata() {
        Ast.Program parsed = Parser.parse("""
                define actor Worker receives String as
                  receive(ActorMail<String> mail): void { return; }
                end
                """);
        Ast.Program expanded = AnnotationExpander.expand(parsed);
        Ast.ClassDecl before = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.ClassDecl after = (Ast.ClassDecl) expanded.modules().getFirst().declarations().getFirst();
        assertEquals(before.actorMessageContract(), after.actorMessageContract());
    }

    @Test void ordinaryActorLegacyHeaderRemainsUnchanged() {
        Ast.ActorMessageContract absent = actor("define actor Worker as end")
                .actorMessageContract();
        assertFalse(absent.declared());
        assertNull(absent.receives());
        assertNull(absent.sends());
    }

    private static String fixture(String name) {
        try (var input = ActorMessageContractV1Test.class
                .getResourceAsStream("/actor-message-v1/" + name)) {
            assertNotNull(input, "missing portable .atto fixture: " + name);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    @Test void multipleRecipientsKeepIndependentTypedMailboxContracts() {
        assertDoesNotThrow(() -> TypeChecker.check(
                Parser.parse(fixture("positive-multiple-recipients.atto"))));
    }

    @Test void wrongPayloadForOneRecipientCannotBeLaunderedThroughOtherRefs() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        fixture("negative-wrong-downstream-payload.atto"))));
        assertTrue(failure.getMessage().contains("ActorRef.send mailbox value"),
                failure::getMessage);
    }

    @Test void explicitSendsIsDeniedEvenWithoutAVisibleOutboundCall() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(fixture("negative-unverified-sends.atto"))));
        assertTrue(failure.getMessage().contains("ACTOR_SENDS_EFFECT_UNSUPPORTED"),
                failure::getMessage);
    }

    @Test void controlOnlyNeverRequiresVerifiedControlRouting() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(fixture("negative-receives-never.atto"))));
        assertTrue(failure.getMessage().contains("ACTOR_RECEIVES_NEVER_UNSUPPORTED"),
                failure::getMessage);
    }

}
