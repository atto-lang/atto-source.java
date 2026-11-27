package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ActorScalarSuspendingLowering;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production parser + type/ownership checker, actual actor SourceTask CALL
 * suspension, nested callee PC/local persistence and same-actor mailbox order.
 * This is still an opt-in restricted scalar image with externally bound
 * scheduler Future ports; not general `.atto` receive lowering or native AOT.
 */
final class ActorScalarSuspendingLoweringTest {
    private static final String SOURCE = """
            async fnc gate(): int { return 1; }
            suspends fnc child(int x): int {
                val int saved = x + 1;
                return (await gate()) + saved;
            }
            suspends fnc outer(int base): int {
                return child(base) + 1;
            }
            """;

    private record State(ActorRuntime.ActorId ownerActorId)
            implements ActorRuntime.ActorOwnedGuestState { }

    private static void waitUntil(BooleanSupplier predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(predicate.getAsBoolean(), "bounded actor source suspension observation timed out");
    }

    @Test
    void checkedSourceNestedAwaitKeepsTwoCalleeFramesAcrossFutureResume()
            throws Exception {
        var compiled = ActorScalarSuspendingLowering.compile(SOURCE, "outer");
        assertEquals(2, compiled.program().functions().size());
        assertEquals(List.of("gate"), compiled.program().portNames());
        var gate = new OresFuture<Long>();
        AtomicReference<OresFuture<Long>> result = new AtomicReference<>();
        AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
        AtomicInteger laterMail = new AtomicInteger();
        Ast.Program actorImage = Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var code = store.publish("tests/actor-source-nested-await.ores", actorImage);
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return code; }

                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String name, ActorRuntime.ActorContext<Object> context) {
                    return new State(context.self().id());
                }

                @Override public void receiveActor(String name, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    assertEquals(state.ownerActorId(),context.self().id());
                    ActorReceiveInvocation invocation = ActorRuntime.currentReceiveInvocation();
                    assertNotNull(invocation,"admitted source receive root required");
                    arena.set(ActorRuntime.currentActorFrameArena());
                    if ("first".equals(mail.value())) {
                        var future = runtime.startActorTask(
                                ActorScalarSuspendingMachine.task(
                                        compiled.program(),compiled.entry(),List.of(39L),
                                        Map.of("gate",gate)));
                        result.set(future);
                        runtime.suspendCurrentUserMailboxUntil(future);
                    } else if ("second".equals(mail.value())) {
                        laterMail.incrementAndGet();
                        assertTrue(result.get().isDone(),
                                "second DATA cannot overtake source receive completion");
                        context.self().stop();
                    } else {
                        fail("unexpected test message");
                    }
                }
            };
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED,executor,"Worker");
            actor.ready().join();
            actor.send("first");

            waitUntil(() -> result.get()!=null && arena.get()!=null &&
                    arena.get().liveFrames()>=4);
            int charged = arena.get().usedBytes();
            assertTrue(charged>2*ActorReceiveInvocation.ROOT_BYTES,
                    "nested scalar CALL frames must stay charged while awaited");
            actor.send("second");
            Thread.sleep(35);
            assertEquals(0,laterMail.get());
            assertEquals(charged,arena.get().usedBytes(),
                    "await must keep the same actor-owned logical activation bytes");

            gate.completeFromRuntime(1L);
            assertEquals(42L,result.get().get(4,TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(4,TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(),()->"failed source actor: "+actor.failure());
            assertEquals(1,laterMail.get());
            assertEquals(0,arena.get().usedBytes(),
                    "source receive and completed Task frames fully retired");
        }
    }

    @Test
    void scalarSuspendsLoweringRejectsUnsafeOrUnsupportedGuestSyntax() {
        assertThrows(IllegalArgumentException.class,
                () -> ActorScalarSuspendingLowering.compile("""
                        suspends fnc outer(): int { return await 42; }
                        ""","outer"),
                "real type checker rejects awaiting a non-Future");

        assertThrows(IllegalArgumentException.class,
                () -> ActorScalarSuspendingLowering.compile("""
                        suspends fnc outer(): int {
                            if true { return 42; }
                            return 0;
                        }
                        ""","outer"),
                "unsupported control-flow nodes cannot silently lower");

        assertThrows(IllegalArgumentException.class,
                () -> ActorScalarSuspendingLowering.compile("""
                        suspends fnc outer(int n): int { return n; }
                        ""","missing"),
                "an unknown entry cannot be treated as a compiled callable");

        assertThrows(IllegalArgumentException.class,
                () -> ActorScalarSuspendingLowering.compile("""
                        suspends fnc outer(int n): int {
                            val &int borrowed = rt borrow n;
                            return n;
                        }
                        ""","outer"),
                "borrowed guest references are not scalar frame cells");
    }

    @Test
    void noHostCarrierCanResumeScalarTaskOrForgeACompilerTaskFrame() {
        var compiled=ActorScalarSuspendingLowering.compile(SOURCE,"outer");
        var gate=new OresFuture<Long>();
        var task=ActorScalarSuspendingMachine.task(
                compiled.program(),compiled.entry(),List.of(39L),Map.of("gate",gate));
        assertThrows(SecurityException.class,
                () -> task.resume(new OresScheduler.Resume(true,null,null)),
                "guest task execution requires the owning actor physical scheduler turn");
        assertThrows(IllegalArgumentException.class,
                () -> ActorScalarSuspendingMachine.task(
                        compiled.program(),compiled.entry(),List.of(39L),Map.of()),
                "missing async Future-port authority cannot be fabricated");
    }
}
