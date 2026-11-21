package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host actor-turn containment, not a proof of killable JNI/native code.
 * The real native-stack test runs normal JVM recursion on a shared actor
 * carrier; the strict managed-frame quota cases must fail before a native
 * StackOverflowError can happen.
 */
final class ActorStackOverflowIsolationTest {
    private static final int TIMEOUT_SECONDS = 15;
    private static volatile long recursionMarker;

    private static long exhaustNativeStack(long depth) {
        // Intentional NON-tail recursion: a real StackOverflowError must
        // bubble to the actor's physical-turn boundary. The volatile write
        // makes this test resistant to trivial compiler elimination.
        recursionMarker = depth;
        return 1L + exhaustNativeStack(depth + 1L);
    }

    private static ActorScalarCallMachine.Program quotaProgram() {
         return new ActorScalarCallMachine.Program(List.of(
                new ActorScalarCallMachine.Function("deep", 1, 1, 12, List.of(
                        ActorScalarCallMachine.Instruction.at(ActorScalarCallMachine.Op.LOAD, 0),
                        ActorScalarCallMachine.Instruction.constant(0),
                        ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.LE),
                        ActorScalarCallMachine.Instruction.at(ActorScalarCallMachine.Op.JUMP_IF_FALSE, 6),
                        ActorScalarCallMachine.Instruction.constant(0),
                        ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.RET),
                        ActorScalarCallMachine.Instruction.constant(1),
                        ActorScalarCallMachine.Instruction.at(ActorScalarCallMachine.Op.LOAD, 0),
                        ActorScalarCallMachine.Instruction.constant(1),
                        ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.SUB),
                        ActorScalarCallMachine.Instruction.at(ActorScalarCallMachine.Op.CALL, 0),
                        ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.ADD),
                        ActorScalarCallMachine.Instruction.of(ActorScalarCallMachine.Op.RET)
                ))));
    }

    @Test
    void syntheticStackOverflowIsRecordedAsActorLocalFailure() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<Thread> failedCarrier = new AtomicReference<>();
            AtomicReference<ActorFrameArena> failedArena = new AtomicReference<>();
            var failed = runtime.<String>spawnShared(factory -> (message, turn) -> {
                failedCarrier.set(Thread.currentThread());
                failedArena.set(ActorRuntime.currentActorFrameArena());
                throw new StackOverflowError("guest stack overflow");
            });
            AtomicReference<Thread> siblingCarrier = new AtomicReference<>();
            AtomicInteger received = new AtomicInteger();
            var sibling = runtime.<String>spawnShared(factory -> (message, turn) -> {
                siblingCarrier.set(Thread.currentThread());
                received.incrementAndGet();
                turn.self().stop();
            });

            failed.send("overflow");
            assertTrue(failed.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(ActorStackOverflowException.class, failed.failure().orElseThrow());
            assertTrue(failedArena.get().isPoisoned());
            assertTrue(failedArena.get().isRetired());
            assertEquals(0, failedArena.get().usedBytes());
            assertFalse(failed.isAlive());

            sibling.send("alive");
            assertTrue(sibling.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertSame(failedCarrier.get(), siblingCarrier.get(),
                    "one-worker SHARED pool must reuse a healthy carrier after actor failure");
            assertEquals(1, received.get());
            assertTrue(sibling.failure().isEmpty());
        }
    }

    @Test
    void realJvmStackOverflowUnwindsAndHealthySiblingReusesSharedCarrier() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<Thread> owner = new AtomicReference<>();
            var failing = runtime.<String>spawnShared(factory -> (message, turn) -> {
                owner.set(Thread.currentThread());
                exhaustNativeStack(0);
            });
            AtomicReference<Thread> nextCarrier = new AtomicReference<>();
            var sibling = runtime.<String>spawnShared(factory -> (message, turn) -> {
                nextCarrier.set(Thread.currentThread());
                turn.self().stop();
            });
            failing.send("deep");
            assertTrue(failing.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(ActorStackOverflowException.class, failing.failure().orElseThrow());
            sibling.send("continue");
            assertTrue(sibling.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertSame(owner.get(), nextCarrier.get(),
                    "recovered JVM actor overflow must not kill shared worker");
            assertTrue(sibling.failure().isEmpty());
        }
    }

    @Test
    void caughtManagedFrameQuotaStillTerminatesOnlyTheActor() throws Exception {
        var program = quotaProgram();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> badArena = new AtomicReference<>();
            AtomicReference<ActorFrameArena> goodArena = new AtomicReference<>();
            AtomicReference<Boolean> siblingArenaHealthyDuringTurn = new AtomicReference<>();
            AtomicInteger swallowed = new AtomicInteger();
            AtomicInteger extraMail = new AtomicInteger();
            var bad = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                badArena.set(ActorRuntime.currentActorFrameArena());
                if ("extra".equals(mail)) {
                    extraMail.incrementAndGet();
                    return;
                }
                try {
                    ActorScalarCallMachine.execute(
                            program, 0, List.of(100_000L), () -> { });
                } catch (ActorFrameArena.BudgetExceeded expected) {
                    swallowed.incrementAndGet();
                    // Deliberately swallow a fatal actor quota error.
                    // ActorRuntime must still detect the poisoned arena
                    // after the handler returns, and stop this generation.
                }
            });
            AtomicInteger siblingCalls = new AtomicInteger();
            var sibling = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                goodArena.set(ActorRuntime.currentActorFrameArena());
                siblingArenaHealthyDuringTurn.set(!goodArena.get().isPoisoned());
                siblingCalls.incrementAndGet();
                if (siblingCalls.get() == 2) turn.self().stop();
            });
            bad.send("overflow");
            try {
                bad.send("extra");
            } catch (ActorRuntime.ActorTerminatedException alreadyStopped) {
                // Both outcomes are valid: STOP can win before the second
                // send, or a queued second mail must be discarded after
                // the first message poisons its actor generation.
            }
            assertTrue(bad.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(ActorFrameArena.BudgetExceeded.class, bad.failure().orElseThrow());
            assertEquals(1, swallowed.get());
            assertEquals(0, extraMail.get(), "poisoned actor cannot process next mail");
            assertEquals(0, badArena.get().usedBytes());
            assertTrue(badArena.get().isPoisoned());
            assertTrue(badArena.get().isRetired());

            sibling.send("one");
            sibling.send("two");
            assertTrue(sibling.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(2, siblingCalls.get());
            assertTrue(sibling.failure().isEmpty());
            assertNotSame(badArena.get(), goodArena.get());
            assertEquals(Boolean.TRUE, siblingArenaHealthyDuringTurn.get(),
                    "sibling arena must be healthy while B is executing");
            // After B stops normally, its arena is retired and marked
            // terminal/poisoned by the lifecycle protocol: do NOT treat
            // post-retirement isPoisoned() as evidence B failed.
        }
    }

    @Test
    void uncaughtManagedFrameQuotaIsAControlledActorTerminalFailure() throws Exception {
        var program = quotaProgram();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> owner = new AtomicReference<>();
            var failing = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                owner.set(ActorRuntime.currentActorFrameArena());
                ActorScalarCallMachine.execute(program, 0, List.of(100_000L), () -> { });
            });
            failing.send("overflow");
            assertTrue(failing.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(ActorFrameArena.BudgetExceeded.class, failing.failure().orElseThrow());
            assertEquals(0, owner.get().usedBytes());
            assertTrue(owner.get().isPoisoned());
            assertTrue(owner.get().isRetired());
        }
    }

    @Test
    void independentActorCanUseSameManagedStackQuotaAfterFailure() throws Exception {
        var program = quotaProgram();
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var failing = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                ActorScalarCallMachine.execute(program, 0, List.of(100_000L), () -> { });
            });
            AtomicReference<Long> result = new AtomicReference<>();
            var surviving = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                result.set(ActorScalarCallMachine.execute(
                        program, 0, List.of(12L), () -> { }));
                turn.self().stop();
            });
            failing.send("overflow");
            assertTrue(failing.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(ActorFrameArena.BudgetExceeded.class, failing.failure().orElseThrow());
            surviving.send("compute");
            assertTrue(surviving.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(surviving.failure().isEmpty());
            assertEquals(12L, result.get().longValue());
        }
    }

    @Test
    void shallowNativeStackProcessStillPreservesActorSiblingAndSharedCarrier()
            throws Exception {
        // A child JVM exercises the actual stack-depth boundary without
        // risking this JUnit worker's own process. Maven Surefire publishes
        // the full tested project classpath in surefire.test.class.path.
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        assertNotNull(classpath, "unable to locate child JVM test classpath");
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"),
                "bin", exe).toString();
        var process = new ProcessBuilder(
                javaExecutable, "-Xss256k", "-cp", classpath,
                ActorStackOverflowLowStackProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS),
                    "low-stack actor isolation probe did not terminate");
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(),
                    "subprocess failed under shallow native stack:\n" + output);
            assertTrue(output.contains(
                    "ACTOR_NATIVE_STACK_OVERFLOW=A_FAILS_B_SURVIVES"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void leakedGuestCallScopeIsQuarantinedBeforeSiblingReusesCarrier()
            throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorGuestStack> brokenStack = new AtomicReference<>();
            AtomicReference<ActorFrameArena> brokenArena = new AtomicReference<>();
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            var broken = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                firstThread.set(Thread.currentThread());
                brokenStack.set(ActorRuntime.currentActorGuestStack());
                brokenArena.set(ActorRuntime.currentActorFrameArena());
                // Simulate a malformed unwinder: deliberately never leave.
                // The turn-exit fence MUST revoke the per-carrier ThreadLocal
                // before allowing any independent actor onto that worker.
                int leakedDepth = GuestCallDepthGuard.enter();
                assertEquals(1, leakedDepth);
            });
            AtomicReference<Thread> nextThread = new AtomicReference<>();
            AtomicInteger healthyCalls = new AtomicInteger();
            var healthy = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                nextThread.set(Thread.currentThread());
                int depth = GuestCallDepthGuard.enter();
                try {
                    assertEquals(1, depth,
                            "actor B must not inherit A's stale carrier call scope");
                    healthyCalls.incrementAndGet();
                } finally {
                    GuestCallDepthGuard.leave(depth);
                    turn.self().stop();
                }
            });
            broken.send("leak");
            assertTrue(broken.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,
                    broken.failure().orElseThrow());
            assertTrue(brokenStack.get().isPoisoned());
            assertEquals(0, brokenStack.get().activeFrames());
            assertEquals(0, brokenArena.get().usedBytes());
            assertTrue(brokenArena.get().isRetired());

            healthy.send("alive");
            assertTrue(healthy.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, healthyCalls.get());
            assertTrue(healthy.failure().isEmpty());
            assertSame(firstThread.get(), nextThread.get());
        }
    }

    @Test
    void nativeOverflowWithLeakedGuardCannotContaminateAnotherActor()
            throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorGuestStack> oldStack = new AtomicReference<>();
            AtomicReference<ActorFrameArena> oldArena = new AtomicReference<>();
            var broken = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                oldStack.set(ActorRuntime.currentActorGuestStack());
                oldArena.set(ActorRuntime.currentActorFrameArena());
                GuestCallDepthGuard.enter();
                throw new StackOverflowError("guest failed during unbalanced native unwind");
            });
            AtomicInteger siblingCalls = new AtomicInteger();
            var healthy = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                int depth = GuestCallDepthGuard.enter();
                try {
                    siblingCalls.incrementAndGet();
                    assertEquals(1, depth);
                } finally {
                    GuestCallDepthGuard.leave(depth);
                    turn.self().stop();
                }
            });
            broken.send("overflow");
            assertTrue(broken.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(ActorStackOverflowException.class,
                    broken.failure().orElseThrow());
            assertEquals(0, oldStack.get().activeFrames());
            assertEquals(0, oldArena.get().usedBytes());
            healthy.send("continue");
            assertTrue(healthy.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, siblingCalls.get());
            assertTrue(healthy.failure().isEmpty());
        }
    }

    @Test
    void leakedCallDepthStopsActorBeforeSecondMessageInSameBatch() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var enteredFirstMessage = new java.util.concurrent.CountDownLatch(1);
            var allowFirstToReturn = new java.util.concurrent.CountDownLatch(1);
            AtomicInteger otherDeliveries = new AtomicInteger();
            AtomicReference<ActorGuestStack> stack = new AtomicReference<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                stack.set(ActorRuntime.currentActorGuestStack());
                arena.set(ActorRuntime.currentActorFrameArena());
                if ("first".equals(mail)) {
                    int reserved = GuestCallDepthGuard.enter();
                    assertEquals(1, reserved);
                    enteredFirstMessage.countDown();
                    try {
                        if (!allowFirstToReturn.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("test admission barrier expired");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    // Deliberately omit leave(): the actor must fail here,
                    // before the queued "second" mail can be dispatched.
                    return;
                }
                otherDeliveries.incrementAndGet();
                turn.self().stop();
            });
            actor.send("first");
            assertTrue(enteredFirstMessage.await(10, TimeUnit.SECONDS));
            actor.send("second"); // known admitted while the first is running
            allowFirstToReturn.countDown();
            assertTrue(actor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, otherDeliveries.get(),
                    "an unfinished stack lease must stop the actor before its next mail");
            assertInstanceOf(IllegalStateException.class, actor.failure().orElseThrow());
            assertTrue(stack.get().isPoisoned());
            assertEquals(0, stack.get().activeFrames());
            assertEquals(0, arena.get().usedBytes());
        }
    }

    @Test
    void regularGuestHandlerFailureDoesNotBecomeStackOverflow() throws Exception {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var failing = runtime.<String>spawnShared(factory -> (message, turn) -> {
                throw new IllegalArgumentException("guest failure");
            });
            failing.send("bad");
            assertTrue(failing.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class,
                    failing.failure().orElseThrow());
        }
    }
}
