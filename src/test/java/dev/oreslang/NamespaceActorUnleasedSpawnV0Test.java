package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real unleased Context.eval source-spawn admission. The parser AST, strict
 * actor verifier and actor-owned Runtime run the source main; no prechecked
 * host program lease or Java ActorRef is furnished by these tests.
 */
@Timeout(30)
final class NamespaceActorUnleasedSpawnV0Test {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/unleased-source-spawn.atto"));
    }

    private static Source guest(String source, String name) throws Exception {
        return Source.newBuilder(OresLanguage.ID, source, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
    }

    private static long evaluate(String source) throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(guest(source, "unleased-namespace-spawn.atto")).asLong();
        }
    }

    @Test
    void normalContextEvalSpawnsTwoSourceActorsAndExecutes1001Mails() throws Exception {
        assertEquals(42L, evaluate(source()));
    }

    @Test
    void sourceReturnAndActorNameAreNotHardcoded() throws Exception {
        String changed = source()
                .replace("use namespace foo;", "use namespace accounts;")
                .replace("Meter", "Ledger")
                .replace("return 42;", "return 99;");
        assertEquals(99L, evaluate(changed));
    }

    @Test
    void secondContextEvalUsesFreshActorIncarnationsAndCodeImage() throws Exception {
        Source guest = guest(source(), "repeated-namespace-spawn.atto");
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(guest).asLong());
            assertEquals(42L, context.eval(guest).asLong());
        }
    }

    @Test
    void rejectsUnknownActorOperationsAndDirectExternalIngress() throws Exception {
        String original = source();
        String[] invalid = {
                original.replace("a.send(1);", "a.receive(1);"),
                original.replace("b.send(7);", "b.init();"),
                original.replace("a.send(1);", "a.state.received = 999;"),
                original.replace("a.send(1);", "a.send(b);"),
                original.replace("a.send(1);", "other.send(1);"),
                original.replace("const a = spawn Meter();", "let a = spawn Meter();"),
                original.replace("const a = spawn Meter();", "const a = spawn Meter(10);"),
                original.replace("const a = spawn Meter();", "const a = new Meter();"),
                original.replace("const a = spawn Meter();", "const a = spawn Other();"),
                original.replace("b.send(7);", "b.send(7); b.stop();"),
                original.replace("return 42;", "return a.state.received;"),
                original.replace("return 42;", "return a.receive(0);"),
                original.replace("return 42;", "return 42;\n pub fnc hidden(): int { return 3; }")
        };
        for (int i = 0; i < invalid.length; i++) {
            final int id = i;
            assertThrows(RuntimeException.class, () ->
                    evaluate(invalid[id]), "must reject unverified guest actor operation " + id);
        }
    }

    @Test
    void boundsLoopAndForbidsDynamicOrUnboundedSendExpressions() throws Exception {
        String original = source();
        String[] invalid = {
                original.replace("i < 1000", "i < 1025"),
                original.replace("i < 1000", "i <= 1000"),
                original.replace("i = i + 1", "i = i + 2"),
                original.replace("i = i + 1", "i = i - 1"),
                original.replace("for (let i = 0; i < 1000; i = i + 1)",
                        "for (let a = 0; a < 1000; a = a + 1)"),
                original.replace("a.send(1);", "a.send(i);"),
                original.replace("a.send(1);", "a.send(1 + 2);"),
                original.replace("a.send(1);", "a.send(1.0);"),
                original.replace("for (let i = 0;", "for (let i = 1;"),
                original.replace("for (let i = 0;", "while (true) {")
        };
        for (int i = 0; i < invalid.length; i++) {
            final int id = i;
            assertThrows(RuntimeException.class, () ->
                    evaluate(invalid[id]), "must reject unsafe loop shape " + id);
        }
    }

    @Test
    void actorCriticalBodiesStillRejectUnverifiedEffects() throws Exception {
        String original = source();
        String[] invalid = {
                original.replace("p.state.received + delta", "readHost(delta)"),
                original.replace("p.state.budget - delta", "await delta"),
                original.replace("critical mtd (Meter p) add", "external mtd (Meter p) add"),
                original.replace("p.add(mail.value);", "p.unknown(mail.value);"),
                original.replace("ActorMail<int>", "ActorMail<borrow int>")
        };
        for (int i = 0; i < invalid.length; i++) {
            final int id = i;
            assertThrows(RuntimeException.class, () -> evaluate(invalid[id]),
                    "unverified actor state effect " + id);
        }
    }

    @Test
    void hostOnlyWaitBridgeCannotParkAnotherActorCarrier() throws Exception {
        String checked = source();
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var parent = runtime.spawnShared(() -> (message, actorContext) -> {
                try (Context nested = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false).build()) {
                    RuntimeException error = assertThrows(RuntimeException.class,
                            () -> nested.eval(guest(checked, "nested-actor-context.atto")));
                    assertTrue(error.getMessage().contains(
                            "cannot park an actor or Mailman carrier"), error.getMessage());
                }
            });
            parent.ready().get(3, TimeUnit.SECONDS);
            parent.send(1L);
            parent.drain().get(10, TimeUnit.SECONDS);
            assertTrue(parent.failure().isEmpty(),
                    "nested source-main admission must reject without failing the actor");
            parent.stop();
            parent.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorWithoutSourceMainRemainsFailClosed() throws Exception {
        String actorOnly = source().substring(0, source().indexOf("pub fnc main(): int"));
        assertThrows(RuntimeException.class, () -> evaluate(actorOnly));
    }
}
