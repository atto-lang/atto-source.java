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

/**
 * Executed Java ActorRuntime bootstrap, not Graal guest or full namespace
 * actor MIR. Every negative case must fail without weakening TypeChecker.
 */
@Timeout(15)
final class NamespaceActorBootstrapCompilerTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/bootstrap-turn.atto"));
    }

    private static SharedCodeImageStore.CodeImage publish(
            SharedCodeImageStore store, String source) {
        return store.publish("tests/probe.atto", Parser.parse(source));
    }

    @Test
    void aRealActorRuntimeAdmitsAndDrainsTwoCompilerBoundNoopTurns()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            Ast.Program parsed = Parser.parse(source());
            var image = store.publish("tests/probe.atto", parsed);
            ActorRuntime.ActorCodeExecutor executable =
                    NamespaceActorBootstrapCompiler.compile(image);
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(), executable, "Probe");
            assertNull(actor.ready().get(3, TimeUnit.SECONDS));
            assertSame(image, executable.codeImage());
            actor.send(17L);
            actor.send(-23L);
            assertNull(actor.drain().get(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            actor.stop();
            assertNull(actor.done().get(3, TimeUnit.SECONDS));
            assertEquals(1, store.imageCount());
        }
    }


    @Test
    void stoppedActorGenerationCannotImpersonateAnotherActorWithSameCodeImage()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = publish(store, source());
            var executable = NamespaceActorBootstrapCompiler.compile(image);
            var retired = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executable, "Probe");
            retired.ready().get(3, TimeUnit.SECONDS);
            retired.send(9L);
            retired.drain().get(3, TimeUnit.SECONDS);
            retired.stop();
            retired.done().get(3, TimeUnit.SECONDS);

            var successor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executable, "Probe");
            successor.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(retired.id(), successor.id(),
                    "an actor generation must never reuse the retired actor identity");
            assertThrows(RuntimeException.class, () -> retired.send(10L),
                    "retired ActorRef must never admit mail to the successor");
            successor.send(11L);
            successor.drain().get(3, TimeUnit.SECONDS);
            assertTrue(successor.failure().isEmpty());
            successor.stop();
            successor.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void malformedTypedMailFailsTheActualRunningActor()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executable = NamespaceActorBootstrapCompiler.compile(
                    publish(store, source()));
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executable, "Probe");
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("not-an-int");
            assertThrows(ExecutionException.class,
                    () -> actor.done().get(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent());
        }
    }

    @Test
    void userFacingTypeCheckerStillFailsClosedForThisActor()
            throws Exception {
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source())));
        assertTrue(error.getMessage().contains("LOWERING_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void directInitDispatchHasNoRuntimeTurnAuthority()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = publish(store, source());
            var executor = NamespaceActorBootstrapCompiler.compile(image);
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Probe");
            actor.ready().get(3, TimeUnit.SECONDS);
            ActorRuntime.ActorContext<Object> forged =
                    new ActorRuntime.ActorContext<>() {
                        @Override public ActorRuntime.ActorRef<Object> self() {
                            return actor;
                        }
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
                    () -> executor.initializeActor("Probe", forged));
            assertThrows(SecurityException.class,
                    () -> executor.receiveActor("Probe", () -> actor.id(),
                            new ActorRuntime.ActorInboxMail<>(
                                    actor.id(), Optional.empty(), 0L, 1L),
                            forged));
            actor.stop();
            actor.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void unsupportedBodiesStateContractsAndOtherDeclarationsStayRejected()
            throws Exception {
        String good = source();
        String[] wrong = {
                good.replace("return;\n}", "return 1;\n}"),
                good.replace("declare actor Probe;", "declare pub actor Probe implements Missing;"),
                good + "\ndefine state on actor Probe as { let int x: 0; }\n",
                good.replace("ActorMail<int>", "ActorMail<borrow int>"),
                good.replace("init(): void", "init(int n): void"),
                good.replace("receive(ActorMail<int> mail): void",
                        "receive(ActorMail<int> mail): int"),
                good.replace("declare actor Probe;", "define actor Probe as end"),
                good + "\npub fnc unsafe_extra(): int { return 1; }\n"
        };
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            for (int i = 0; i < wrong.length; i++) {
                final int index = i;
                assertThrows(IllegalArgumentException.class,
                        () -> NamespaceActorBootstrapCompiler.compile(
                                store.publish("invalid/" + index + ".atto",
                                        Parser.parse(wrong[index]))),
                        "invalid actor variant " + i);
            }
        }
    }
}
