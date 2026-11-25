package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real ActorRuntime turns with typed compiler-internal critical IR. */
@Timeout(20)
final class NamespaceActorCriticalIrCompilerTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/critical-ir-multifield.atto"));
    }
    private static ActorRuntime.ActorCodeExecutor compile(
            SharedCodeImageStore store, String code, String name) {
        return NamespaceActorCriticalIrCompiler.compile(
                store.publish(name, Parser.parse(code)));
    }

    @Test
    void executesTwoMethodsOnTwoActorOwnedFieldsFor1000MailboxTurns() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executable = compile(store, source(), "ir/two-fields.atto");
            var a = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            var b = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            a.ready().get(3, TimeUnit.SECONDS);
            b.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(a.id(), b.id());

            for (int i = 0; i < 1000; i++) a.send(1L);
            a.drain().get(5, TimeUnit.SECONDS);
            assertTrue(a.failure().isEmpty());

            // The first field must be exactly 1000 for this next message to
            // attain MAX; overflow on the following message is a precise check.
            a.send(Long.MAX_VALUE - 1000L);
            a.drain().get(3, TimeUnit.SECONDS);
            assertTrue(a.failure().isEmpty());
            a.send(1L);
            assertThrows(ExecutionException.class,
                    () -> a.done().get(3, TimeUnit.SECONDS));
            assertTrue(a.failure().orElseThrow() instanceof ArithmeticException);

            // Proves each incarnation starts with its own scalar state.
            b.send(Long.MAX_VALUE);
            b.drain().get(3, TimeUnit.SECONDS);
            assertTrue(b.failure().isEmpty());
            b.send(1L);
            assertThrows(ExecutionException.class,
                    () -> b.done().get(3, TimeUnit.SECONDS));
            assertTrue(b.failure().orElseThrow() instanceof ArithmeticException);
        }
    }

    @Test
    void rejectsForgedActorIngressOutsidePhysicalTurn() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var exec = compile(store, source(), "ir/forgery.atto");
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, exec, "Probe");
            actor.ready().get(3, TimeUnit.SECONDS);
            var forged = new ActorRuntime.ActorContext<Object>() {
                @Override public ActorRuntime.ActorRef<Object> self() { return actor; }
                @Override public ActorRuntime runtime() { return runtime; }
                @Override public ActorRuntime.ActorKind kind() {
                    return ActorRuntime.ActorKind.SHARED;
                }
                @Override public IsolatePolicy policy() { return IsolatePolicy.developer(); }
                @Override public Optional<ActorRuntime.ActorMemorySlice> privateMemory() {
                    return Optional.empty();
                }
                @Override public Optional<SharedCodeImageStore.CodeImage> codeImage() {
                    return Optional.of(exec.codeImage());
                }
            };
            assertThrows(SecurityException.class,
                    () -> exec.initializeActor("Probe", forged));
            assertThrows(SecurityException.class,
                    () -> exec.receiveActor("Probe", () -> actor.id(),
                            new ActorRuntime.ActorInboxMail<>(
                                    actor.id(), Optional.empty(), 0L, 5L), forged));
            actor.stop();
            actor.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void staleActorRefCannotMutateNewGeneration() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executable = compile(store, source(), "ir/generation.atto");
            var old = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            old.ready().get(3, TimeUnit.SECONDS);
            old.send(4L);
            old.drain().get(3, TimeUnit.SECONDS);
            old.stop();
            old.done().get(3, TimeUnit.SECONDS);
            var next = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            next.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(old.id(), next.id());
            assertThrows(RuntimeException.class, () -> old.send(9L));
            next.send(1L);
            next.drain().get(3, TimeUnit.SECONDS);
            assertTrue(next.failure().isEmpty());
            next.stop();
            next.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void badMailKillsOnlyItsRecipient() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executable = compile(store, source(), "ir/bad-mail.atto");
            var victim = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            var sibling = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            victim.ready().get(3, TimeUnit.SECONDS);
            sibling.ready().get(3, TimeUnit.SECONDS);
            victim.send("not an int");
            assertThrows(ExecutionException.class,
                    () -> victim.done().get(3, TimeUnit.SECONDS));
            assertTrue(victim.failure().orElseThrow() instanceof SecurityException);
            sibling.send(100L);
            sibling.drain().get(3, TimeUnit.SECONDS);
            assertTrue(sibling.failure().isEmpty());
            sibling.stop();
            sibling.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void multipleVerifiedCriticalCallsAreAcceptedWithinOneMailboxTurn() throws Exception {
        String multiple = source().replace(
                "p.debit(mail.value);",
                "p.debit(mail.value); p.add(mail.value);");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var exec = compile(store, multiple, "ir/repeated-verified-calls.atto");
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, exec, "Probe");
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send(7L);
            actor.drain().get(3, TimeUnit.SECONDS);
            assertTrue(actor.failure().isEmpty());
            actor.stop();
            actor.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void multipleVerifiedWritesWithinOneCriticalMethodAreAllowed() throws Exception {
        String multi = source().replace(
                "p.state.budget = p.state.budget - delta;",
                "p.state.budget = p.state.budget - delta;\n"
                        + "    p.state.received = p.state.received + 1;");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var exec = compile(store, multi, "ir/multiple-checked-writes.atto");
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, exec, "Probe");
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send(3L);
            actor.drain().get(3, TimeUnit.SECONDS);
            assertTrue(actor.failure().isEmpty());
            actor.stop();
            actor.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void mutationCorpusRejectsNoncriticalWritesAndLoanEscapes() throws Exception {
        String s = source();
        String[] invalid = {
                s.replace("critical mtd (Probe p) add", "define mtd (Probe p) add"),
                s.replace("critical mtd (Probe p) debit", "pub critical mtd (Probe p) debit"),
                s.replace("p.add(mail.value);", "p.state.received = mail.value;"),
                s.replace("p.debit(mail.value);", "p.add(1);"),
                s.replace("p.debit(mail.value);", "p.debit(mail.value); p.unchecked(mail.value);"),
                s.replace("p.state.received + delta", "other.state.received + delta"),
                s.replace("p.state.received + delta", "p.state.unknown + delta"),
                s.replace("p.state.received = p.state.received + delta",
                          "p.state.received = get_delta(delta)"),
                s.replace("p.state.budget - delta", "p.state.budget * delta"),
                s.replace("p.state.budget - delta", "await delta"),
                s.replace("p.state.budget - delta", "p.state.budget - delta; unsafe()"),
                s.replace("let int budget: 10000", "let int budget: call()"),
                s.replace("ActorMail<int>", "ActorMail<borrow int>"),
                s.replace("declare actor Probe;", "declare actor Probe implements Unknown;"),
                s + "\npub fnc capture(): int { return 1; }",
                s.replace("p.add(mail.value);", "p.debit(mail.value);")
                 .replace("p.debit(mail.value);\n    return;", "p.missing(mail.value);\n    return;"),
                s.replace("critical mtd (Probe p) debit", "external mtd (Probe p) debit")
        };
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            for (int i = 0; i < invalid.length; i++) {
                final int idx = i;
                assertThrows(RuntimeException.class,
                        () -> compile(store, invalid[idx], "mutations/" + idx + ".atto"),
                        "invalid critical IR source variant " + i);
            }
        }
    }

    @Test
    void normalGraalGuestCompilerRemainsFailClosed() throws Exception {
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source())));
        assertTrue(error.getMessage().contains("LOWERING_UNSUPPORTED"),
                error.getMessage());
    }
}
