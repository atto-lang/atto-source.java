package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression for #403. A malicious/buggy TurnExecutor attempts a second
 * actor entry while the first carrier owns the actor's single-writer lease.
 * Even if the invariant fails closed, the rejected carrier must not retain
 * the actor's ThreadLocal authority when it is recycled.
 */
@Timeout(20)
final class ActorCarrierIdentityCleanupTest {
    @Test
    void foreignWorkerTurnExecutorCannotForgeOrLeakActorContext() throws Exception {
        CountDownLatch foreignAttemptFinished = new CountDownLatch(1);
        AtomicBoolean leakedToForeignThread = new AtomicBoolean(true);
        AtomicBoolean leakedToAdmittedWorker = new AtomicBoolean(true);
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
        AtomicBoolean guestExecuted = new AtomicBoolean();

        ActorRuntime.TurnExecutor adversarialExecutor = turn -> {
            Thread foreign = new Thread(() -> {
                try {
                    turn.run();
                } catch (Throwable failure) {
                    foreignFailure.set(failure);
                } finally {
                    leakedToForeignThread.set(
                            ActorRuntime.inActorExecution()
                                    || ActorRuntime.currentActorId().isPresent()
                                    || ActorRuntime.currentActorKind() != null
                                    || ActorRuntime.currentActorPolicy() != null
                                    || ActorRuntime.currentActorExecutionDomain() != null);
                    foreignAttemptFinished.countDown();
                }
            }, "ores-test-illegal-foreign-carrier");
            foreign.setDaemon(true);
            foreign.start();
            try {
                assertTrue(foreignAttemptFinished.await(5, TimeUnit.SECONDS),
                        "foreign TurnExecutor attempt did not finish");
                // The foreign callback already consumed/violated invocation
                // identity; retry on the admitted worker cannot revive it.
                assertThrows(IllegalStateException.class, turn::run);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } finally {
                leakedToAdmittedWorker.set(
                        ActorRuntime.inActorExecution()
                                || ActorRuntime.currentActorId().isPresent()
                                || ActorRuntime.currentActorKind() != null
                                || ActorRuntime.currentActorPolicy() != null
                                || ActorRuntime.currentActorExecutionDomain() != null);
                try {
                    foreign.join(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(foreign.isAlive(), "foreign callback must finish");
            }
        };

        var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 16);
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), config, adversarialExecutor)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                guestExecuted.set(true);
            });
            ref.send(1);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse(leakedToForeignThread.get(),
                    "foreign TurnExecutor callback leaked actor ThreadLocal identity");
            assertFalse(leakedToAdmittedWorker.get(),
                    "rejected duplicate leaked actor ThreadLocal identity");
            assertFalse(guestExecuted.get(),
                    "a foreign worker must never enter actor-owned code");
            assertInstanceOf(IllegalStateException.class, foreignFailure.get());
            assertTrue(foreignFailure.get().getMessage().contains("foreign worker"),
                    () -> "unexpected foreign error: " + foreignFailure.get());
            Throwable cause = ref.failure().orElseThrow(
                    () -> new AssertionError("invalid TurnExecutor must fail-stop actor"));
            assertTrue(cause.getMessage().contains("TurnExecutor returned after an invalid"),
                    () -> "unexpected termination cause: " + cause);
        }
    }

    @Test
    void leaseReleaseFailureStillClearsCarrierIdentity() throws Exception {
        AtomicBoolean leakedAfterReleaseFailure = new AtomicBoolean(true);
        ActorRuntime.TurnExecutor executor = turn -> {
            try {
                turn.run();
            } finally {
                leakedAfterReleaseFailure.set(
                        ActorRuntime.inActorExecution()
                                || ActorRuntime.currentActorId().isPresent()
                                || ActorRuntime.currentActorKind() != null
                                || ActorRuntime.currentActorPolicy() != null
                                || ActorRuntime.currentActorExecutionDomain() != null);
            }
        };

        var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config, executor)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                // Inject the otherwise-impossible accounting corruption through
                // host-side reflection. Production code has no API for this.
                // This specifically tests endTurn() throwing in finally.
                try {
                    Field actorsField = ActorRuntime.class.getDeclaredField("actors");
                    actorsField.setAccessible(true);
                    Object actor = ((Map<?, ?>) actorsField.get(context.runtime()))
                            .get(context.self().id());
                    assertNotNull(actor);
                    Field activeTurns = actor.getClass().getDeclaredField("activeTurns");
                    activeTurns.setAccessible(true);
                    assertEquals(1, activeTurns.getInt(actor));
                    activeTurns.setInt(actor, 0);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("test failed to inject lease violation", failure);
                }
            });
            ref.send(1);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse(leakedAfterReleaseFailure.get(),
                    "failed endTurn must not strand the actor's ThreadLocal identity");
            Throwable cause = ref.failure().orElseThrow(
                    () -> new AssertionError("endTurn accounting failure must fail-stop actor"));
            assertTrue(cause.getMessage().contains("execution lease accounting violation"),
                    () -> "unexpected termination cause: " + cause);
        }
    }
}
