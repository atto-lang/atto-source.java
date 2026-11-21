package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
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

@Timeout(20)
final class NamespaceActorScalarCriticalCompilerTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/critical-scalar-turn.atto"));
    }

    private static SharedCodeImageStore.CodeImage image(
            SharedCodeImageStore store, String code, String name) {
        return store.publish(name, Parser.parse(code));
    }

    @Test
    void parseAndBindCriticalModifierDistinctFromRuntimeIngress() throws Exception {
        Ast.Program program = Parser.parse(source());
        assertEquals("foo", program.namespace());
        var methods = program.modules().getFirst().declarations().stream()
                .filter(Ast.ExternalActorMethodDecl.class::isInstance)
                .map(Ast.ExternalActorMethodDecl.class::cast).toList();
        assertEquals(3, methods.size());
        assertTrue(methods.stream().filter(m -> m.name().equals("add"))
                .findFirst().orElseThrow().criticalMethod());
        assertFalse(methods.stream().filter(m -> m.name().equals("receive"))
                .findFirst().orElseThrow().criticalMethod());
        assertTrue(methods.stream().filter(m -> m.name().equals("receive"))
                .findFirst().orElseThrow().runtimeIngress());
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            assertNotNull(NamespaceActorScalarCriticalCompiler.compile(
                    image(store, source(), "proof/critical.atto")));
        }
    }

    @Test
    void thousandCriticalUpdatesArePerActorAndCheckedForOverflow() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executable = NamespaceActorScalarCriticalCompiler.compile(
                    image(store, source(), "proof/counter.atto"));
            var a = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            var b = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            a.ready().get(3, TimeUnit.SECONDS);
            b.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(a.id(), b.id());
            for (int i = 0; i < 1_000; i++) {
                a.send(1L);
            }
            // Draining before the limit exercise proves *all* 1000 writes
            // have finished through the serialized mailbox/critical path.
            a.drain().get(5, TimeUnit.SECONDS);
            assertTrue(a.failure().isEmpty());
            a.send(Long.MAX_VALUE - 1_000L);
            a.drain().get(3, TimeUnit.SECONDS);
            assertTrue(a.failure().isEmpty(),
                    "1000 increments followed by MAX-1000 must be exactly MAX");
            a.send(1L);
            assertThrows(ExecutionException.class,
                    () -> a.done().get(3, TimeUnit.SECONDS),
                    "one additional checked increment must overflow and fail only actor a");
            assertTrue(a.failure().orElseThrow() instanceof ArithmeticException);

            // b starts with its own zero: a's 1000 writes and terminal
            // failure may not contaminate its numeric state or lifecycle.
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
    void retiredScalarActorCannotWriteIntoSuccessorIncarnation() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executable = NamespaceActorScalarCriticalCompiler.compile(
                    image(store, source(), "proof/reincarnation.atto"));
            var retired = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executable, "Probe");
            retired.ready().get(3, TimeUnit.SECONDS);
            retired.send(17L);
            retired.drain().get(3, TimeUnit.SECONDS);
            retired.stop();
            retired.done().get(3, TimeUnit.SECONDS);

            var successor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executable, "Probe");
            successor.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(retired.id(), successor.id());
            assertThrows(RuntimeException.class, () -> retired.send(5L),
                    "a retired actor cannot mutate a successor's scalar state");
            successor.send(Long.MAX_VALUE);
            successor.drain().get(3, TimeUnit.SECONDS);
            assertTrue(successor.failure().isEmpty(),
                    "new incarnation starts with a fresh actor-owned count");
            successor.stop();
            successor.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void forgedDirectInvocationFailsWithoutAnActorTurn() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var code = image(store, source(), "proof/forged.atto");
            var executable = NamespaceActorScalarCriticalCompiler.compile(code);
            var ref = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    executable, "Probe");
            ref.ready().get(3, TimeUnit.SECONDS);
            ActorRuntime.ActorContext<Object> forged = new ActorRuntime.ActorContext<>() {
                @Override public ActorRuntime.ActorRef<Object> self() { return ref; }
                @Override public ActorRuntime runtime() { return runtime; }
                @Override public ActorRuntime.ActorKind kind() {
                    return ActorRuntime.ActorKind.SHARED;
                }
                @Override public IsolatePolicy policy() {
                    return IsolatePolicy.developer();
                }
                @Override public Optional<ActorRuntime.ActorMemorySlice> privateMemory() {
                    return Optional.empty();
                }
                @Override public Optional<SharedCodeImageStore.CodeImage> codeImage() {
                    return Optional.of(code);
                }
            };
            assertThrows(SecurityException.class,
                    () -> executable.initializeActor("Probe", forged));
            assertThrows(SecurityException.class,
                    () -> executable.receiveActor("Probe", () -> ref.id(),
                            new ActorRuntime.ActorInboxMail<>(
                                    ref.id(), Optional.empty(), 0L, 10L), forged));
            ref.stop();
            ref.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidScalarMailFailsClosedAndDoesNotAffectSibling() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executable = NamespaceActorScalarCriticalCompiler.compile(
                    image(store, source(), "proof/mail.atto"));
            var bad = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            var good = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Probe");
            bad.ready().get(3, TimeUnit.SECONDS);
            good.ready().get(3, TimeUnit.SECONDS);
            bad.send("untyped");
            assertThrows(ExecutionException.class,
                    () -> bad.done().get(3, TimeUnit.SECONDS));
            assertTrue(bad.failure().orElseThrow() instanceof SecurityException);
            good.send(1L);
            good.drain().get(3, TimeUnit.SECONDS);
            assertTrue(good.failure().isEmpty());
            good.stop();
            good.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void stateWritesWithoutCriticalAndBodyMutationsAreRejected() throws Exception {
        String valid = source();
        String[] invalid = {
                valid.replace("critical mtd", "define mtd"),
                valid.replace("critical mtd", "pub critical mtd"),
                valid.replace("p.state.count = p.state.count + delta",
                        "p.state.count = p.state.count - delta"),
                valid.replace("p.state.count = p.state.count + delta",
                        "p.count = p.count + delta"),
                valid.replace("p.state.count = p.state.count + delta",
                        "p.state.count = delta"),
                valid.replace("p.state.count = p.state.count + delta",
                        "p.state.count = p.state.count + delta; p.state.count = 0"),
                valid.replace("p.add(mail.value);", "p.state.count = p.state.count + mail.value;"),
                valid.replace("p.add(mail.value);", "p.add(1);"),
                valid.replace("p.add(mail.value);", "p.add(mail.value); p.add(mail.value);"),
                valid.replace("ActorMail<int>", "ActorMail<borrow int>"),
                valid.replace("let int count: 0", "let int count: do_work()"),
                valid.replace("declare actor Probe;", "declare pub actor Probe implements Unknown;"),
                valid.replace("critical mtd (Probe p) add", "critical mtd (Other p) add"),
                valid + "\npub fnc leak(): int { return 42; }"
        };
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            for (int i = 0; i < invalid.length; i++) {
                final int slot = i;
                assertThrows(IllegalArgumentException.class,
                        () -> NamespaceActorScalarCriticalCompiler.compile(
                                image(store, invalid[slot], "invalid/scalar" + slot + ".atto")),
                        "invalid critical state mutation variant " + i);
            }
        }
    }

    @Test
    void normalGuestTypeCheckerRemainsClosedToStatefulActor() throws Exception {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source())));
        assertTrue(thrown.getMessage().contains("LOWERING_UNSUPPORTED"),
                thrown.getMessage());
    }
}
