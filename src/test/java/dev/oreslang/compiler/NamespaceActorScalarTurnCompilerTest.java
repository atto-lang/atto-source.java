package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime turns from closed namespace Atto source.
 * This is a tightly bounded scalar MIR bridge, not general Graal guest
 * execution, AMQP support, or full runtime RW-loan integration.
 */
@Timeout(20)
final class NamespaceActorScalarTurnCompilerTest {
    private static final String SOURCE = """
            use namespace rmq_test;
            declare actor Counter;
            define state on actor Counter as {
                let int count : 9223372036854775806;
            }
            external mtd (Counter c) init(): void { return; }
            external mtd (Counter c) receive(ActorMail<int> mail): void {
                c.bump();
                return;
            }
            critical mtd (Counter c) bump(): void {
                using state.write(critical |s| -> {
                    s.count = s.count + 1;
                });
                return;
            }
            """;

    private static SharedCodeImageStore.CodeImage image(
            SharedCodeImageStore store, String source) {
        return store.publish("tests/counter.atto", Parser.parse(source));
    }

    @Test
    void twoRealActorRuntimeInstancesOwnIndependentStateAndOverflowIndependently()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = image(store, SOURCE);
            var executable = NamespaceActorScalarTurnCompiler.compile(image);
            var a = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Counter");
            var b = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Counter");
            assertNull(a.ready().get(3, TimeUnit.SECONDS));
            assertNull(b.ready().get(3, TimeUnit.SECONDS));
            assertNotEquals(a.id(), b.id());
            assertSame(image, executable.codeImage());

            a.send(1L);
            a.drain().get(3, TimeUnit.SECONDS); // MAX-1 => MAX
            assertTrue(a.failure().isEmpty());

            // B must start from its own initialized state, not A's MAX.
            b.send(2L);
            b.drain().get(3, TimeUnit.SECONDS);
            assertTrue(b.failure().isEmpty());

            a.send(3L);
            assertThrows(ExecutionException.class,
                    () -> a.done().get(3, TimeUnit.SECONDS)); // MAX+1 checked failure
            assertTrue(a.failure().isPresent());
            assertTrue(b.failure().isEmpty(), "A's failure must not poison B");

            b.send(4L);
            assertThrows(ExecutionException.class,
                    () -> b.done().get(3, TimeUnit.SECONDS)); // B's independent MAX+1
            assertTrue(b.failure().isPresent());
        }
    }

    @Test
    void twoRealActorsExecuteTypedBoolWriteWithoutIntegerCoercion()
            throws Exception {
        String source = SOURCE
                .replace("let int count : 9223372036854775806;",
                        "let bool closed : false;")
                .replace("s.count = s.count + 1;", "s.closed = true;");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executable = NamespaceActorScalarTurnCompiler.compile(image(store, source));
            var a = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Counter");
            var b = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Counter");
            a.ready().get(3, TimeUnit.SECONDS);
            b.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(a.id(), b.id());
            a.send(1L);
            b.send(2L);
            a.drain().get(3, TimeUnit.SECONDS);
            b.drain().get(3, TimeUnit.SECONDS);
            assertTrue(a.failure().isEmpty());
            assertTrue(b.failure().isEmpty());
            a.stop();
            b.stop();
            assertNull(a.done().get(3, TimeUnit.SECONDS));
            assertNull(b.done().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void mismatchedScalarTypesCannotEnterCriticalLowering()
            throws Exception {
        String boolSource = SOURCE
                .replace("let int count : 9223372036854775806;",
                        "let bool closed : false;")
                .replace("s.count = s.count + 1;", "s.closed = true;");
        String[] bad = {
                SOURCE.replace("s.count = s.count + 1;", "s.count = true;"),
                SOURCE.replace("let int count : 9223372036854775806;",
                        "let bool count : 9223372036854775806;"),
                boolSource.replace("s.closed = true;", "s.closed = s.closed + 1;"),
                boolSource.replace("s.closed = true;", "s.closed = 1;"),
                boolSource.replace("s.closed = true;", "s.closed = false;")
        };
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            for (int i = 0; i < bad.length; i++) {
                String mutated = bad[i];
                final int index = i;
                assertNotEquals(SOURCE, mutated, "scalar mutation must change source");
                assertThrows(IllegalArgumentException.class,
                        () -> NamespaceActorScalarTurnCompiler.compile(
                                store.publish("tests/bad-scalar-" + index + ".atto",
                                        Parser.parse(mutated))), "scalar type mutation " + i);
            }
        }
    }

    @Test
    void forgedOrUntypedMailboxCannotInvokePrivateCriticalMethod()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = image(store, SOURCE);
            var executable = NamespaceActorScalarTurnCompiler.compile(image);
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executable, "Counter");
            actor.ready().get(3, TimeUnit.SECONDS);
            var forged = new ActorRuntime.ActorContext<Object>() {
                @Override public ActorRuntime.ActorRef<Object> self() { return actor; }
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
                    return Optional.of(image);
                }
            };
            assertThrows(SecurityException.class,
                    () -> executable.initializeActor("Counter", forged));
            assertThrows(SecurityException.class,
                    () -> executable.receiveActor(
                            "Counter", () -> actor.id(),
                            new ActorRuntime.ActorInboxMail<>(
                                    actor.id(), Optional.empty(), 0L, 5L), forged));
            actor.send("not-an-int");
            assertThrows(ExecutionException.class,
                    () -> actor.done().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void exactSourceMutationsCannotSmuggleUnsafeWritesIntoCriticalMir()
            throws Exception {
        String[] mutations = {
                SOURCE.replace("s.count = s.count + 1;", "s.count = s.count + 2;"),
                SOURCE.replace("s.count = s.count + 1;", "s.count = 1;"),
                SOURCE.replace("s.count = s.count + 1;", "s.other = s.count + 1;"),
                SOURCE.replace("s.count = s.count + 1;", "s.count = s.count + 1; s.count = 0;"),
                SOURCE.replace("s.count = s.count + 1;", "await socket.read(); s.count = s.count + 1;"),
                SOURCE.replace("using state.write", "state.write"),
                SOURCE.replace("c.bump();", "c.init();"),
                SOURCE.replace("c.bump();", "c.bump(); c.bump();"),
                SOURCE.replace("c.bump();", "b.bump();"),
                SOURCE.replace("critical mtd (Counter c) bump", "pub critical mtd (Counter c) bump"),
                SOURCE.replace("let int count", "let bool count"),
                SOURCE.replace("critical mtd (Counter c) bump",
                        "pub fnc other(): void { return; }\ncritical mtd (Counter c) bump")
        };
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            for (int i = 0; i < mutations.length; i++) {
                String source = mutations[i];
                final int index = i;
                assertNotEquals(SOURCE, source, "negative mutation must change original source " + i);
                assertThrows(IllegalArgumentException.class,
                        () -> NamespaceActorScalarTurnCompiler.compile(
                                store.publish("tests/bad-" + index + ".atto",
                                        Parser.parse(source))),
                        "unsafe mutation " + i);
            }
        }
    }

    @Test
    void untrustedCriticalAstTokensCannotBePromotedToRuntimeAuthority()
            throws Exception {
        // A caller may construct an AST, but unverified critical body token
        // provenance never grants an executable state loan.
        var parsed = Parser.parse(SOURCE);
        var module = parsed.modules().getFirst();
        var declarations = new java.util.ArrayList<>(module.declarations());
        for (int i = 0; i < declarations.size(); i++) {
            if (declarations.get(i) instanceof dev.oreslang.ast.Ast.CriticalActorMethodDecl m) {
                declarations.set(i, new dev.oreslang.ast.Ast.CriticalActorMethodDecl(
                        m.actorName(), m.receiverName(), m.name(), m.visibility(),
                        m.parameters(), m.returnType(), m.bodyTokens(),
                        java.util.Collections.nCopies(m.bodyTokens().size(), "UNVERIFIED")));
            }
        }
        var altered = new dev.oreslang.ast.Ast.Program(parsed.namespace(),
                parsed.imports(),
                java.util.List.of(new dev.oreslang.ast.Ast.ModuleDecl(
                        module.name(), module.annotations(), module.contracts(), declarations)));
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            assertThrows(IllegalArgumentException.class,
                    () -> NamespaceActorScalarTurnCompiler.compile(
                            store.publish("tests/unverified.atto", altered)));
        }
    }

    @Test
    void publicGuestTypeCheckerStillRejectsNamespaceActorExecution() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(SOURCE)));
        assertTrue(failure.getMessage().contains("LOWERING_UNSUPPORTED"),
                failure.getMessage());
    }
}
