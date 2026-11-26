package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime source-executor admission, not compiler-generated .atto
 * actor receive or nested guest await lowering.
 */
final class SourceActorReceiveInvocationTest {
    private record State(ActorRuntime.ActorId ownerActorId)
            implements ActorRuntime.ActorOwnedGuestState { }

    private static void awaitCondition(java.util.function.BooleanSupplier ready)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(ready.getAsBoolean(), "source invocation observation timed out");
    }

    @Test
    void twoSourceReceivesGetDifferentStacksAndSuspendedFirstRetainsBytes()
            throws Exception {
        Ast.Program parsed = Parser.parse("pub routine main(): void { return; }");
        var scalar = new ActorScalarCallMachine.Program(List.of(
                new ActorScalarCallMachine.Function("value", 0, 0, 1, List.of(
                        ActorScalarCallMachine.Instruction.constant(42),
                        ActorScalarCallMachine.Instruction.of(
                                ActorScalarCallMachine.Op.RET)))));
        OresFuture<Integer> gate = new OresFuture<>();
        AtomicReference<ExecutionContext.ExecutionId> first = new AtomicReference<>();
        AtomicReference<ExecutionContext.ExecutionId> second = new AtomicReference<>();
        AtomicReference<ActorFrameArena> arenaRef = new AtomicReference<>();
        try (SharedCodeImageStore code = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var image = code.publish("tests/receive-stack.ores", parsed);
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

                @Override
                public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String actorType, ActorRuntime.ActorContext<Object> ctx) {
                    assertNull(ActorRuntime.currentReceiveInvocation(),
                            "actor initialization cannot inherit a receive stack");
                    return new State(ctx.self().id());
                }

                @Override
                public void receiveActor(
                        String actorType, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> ctx) {
                    ActorReceiveInvocation active = ActorRuntime.currentReceiveInvocation();
                    assertNotNull(active, "source receive must own a logical stack root");
                    assertEquals(ctx.self().id(), state.ownerActorId());
                    ActorFrameArena frames = ActorRuntime.currentActorFrameArena();
                    arenaRef.set(frames);
                    assertEquals(ActorReceiveInvocation.ROOT_BYTES, frames.usedBytes());
                    assertEquals(42L, ActorScalarCallMachine.execute(
                            scalar, 0, List.of(), () -> {}));
                    assertEquals(ActorReceiveInvocation.ROOT_BYTES, frames.usedBytes(),
                            "CALL/RET must leave receive root intact");
                    if ("first".equals(mail.value())) {
                        first.set(active.executionContext().id());
                        runtime.suspendCurrentUserMailboxUntil(gate);
                    } else if ("second".equals(mail.value())) {
                        second.set(active.executionContext().id());
                        assertNotEquals(first.get(), second.get());
                        ctx.self().stop();
                    } else {
                        fail("unexpected test message");
                    }
                }
            };
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Worker");
            actor.ready().join();
            actor.send("first");
            awaitCondition(() -> first.get() != null && arenaRef.get() != null
                    && arenaRef.get().usedBytes() == ActorReceiveInvocation.ROOT_BYTES);
            actor.send("second");
            Thread.sleep(30);
            assertNull(second.get(), "DATA must not overtake a suspended source receive");
            assertEquals(ActorReceiveInvocation.ROOT_BYTES, arenaRef.get().usedBytes());
            gate.completeFromRuntime(1);
            awaitCondition(() -> second.get() != null);
            assertTrue(actor.awaitTermination(4, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), "successful receive cleanup");
            assertNotEquals(first.get(), second.get());
            assertEquals(0, arenaRef.get().usedBytes());
        }
        assertNull(ActorRuntime.currentReceiveInvocation(),
                "worker receive authority must never leak to the test thread");
    }

    @Test
    void swallowedStackQuotaTerminatesOnlySourceActorAndSiblingStillRuns()
            throws Exception {
        Ast.Program parsed = Parser.parse("pub routine main(): void { return; }");
        java.util.concurrent.atomic.AtomicInteger siblingProcessed =
                new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<ActorFrameArena> failedArena = new AtomicReference<>();
        AtomicReference<ActorFrameArena> survivingArena = new AtomicReference<>();
        try (SharedCodeImageStore code = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var image = code.publish("tests/receive-stack-failure.ores", parsed);
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

                @Override
                public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String actorType, ActorRuntime.ActorContext<Object> ctx) {
                    return new State(ctx.self().id());
                }

                @Override
                public void receiveActor(
                        String actorType, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> ctx) {
                    ActorReceiveInvocation invocation = ActorRuntime.currentReceiveInvocation();
                    assertNotNull(invocation);
                    ActorFrameArena frames = ActorRuntime.currentActorFrameArena();
                    if ("poison".equals(mail.value())) {
                        failedArena.set(frames);
                        assertEquals(ActorReceiveInvocation.ROOT_BYTES, frames.usedBytes());
                        try {
                            frames.push(invocation.executionContext(),
                                    ActorFrameArena.DEFAULT_MAX_BYTES);
                            fail("over-budget call must be terminal");
                        } catch (ActorFrameArena.BudgetExceeded ignored) {
                            // Malicious guest code attempted to swallow the quota breach.
                            // ActorRuntime's post-handler poison check MUST fail-stop it.
                        }
                        return;
                    }
                    if ("ok".equals(mail.value())) {
                        survivingArena.set(frames);
                        siblingProcessed.incrementAndGet();
                        ctx.self().stop();
                        return;
                    }
                    fail("a failed source actor must not process later DATA");
                }
            };
            var failing = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    executor, "Worker");
            var surviving = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    executor, "Worker");
            failing.ready().join();
            surviving.ready().join();
            failing.send("poison");
            surviving.send("ok");
            assertTrue(failing.awaitTermination(4, TimeUnit.SECONDS));
            assertTrue(surviving.awaitTermination(4, TimeUnit.SECONDS));
            assertTrue(failing.failure().isPresent(),
                    "swallowing an actor stack quota exception cannot continue the actor");
            assertTrue(surviving.failure().isEmpty(),
                    "independent actor must not inherit the failed arena");
            assertNotSame(failedArena.get(), survivingArena.get());
            assertEquals(1, siblingProcessed.get());
            assertEquals(0, failedArena.get().usedBytes());
            assertEquals(0, survivingArena.get().usedBytes());
        }
    }
}
