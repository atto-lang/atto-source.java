package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Host TurnExecutor is not a secondary scheduler: it must run the turn
 * synchronously on the already admitted actor's physical carrier thread.
 */
@Timeout(15)
final class ActorOffCarrierTurnExecutorTest {
    private static String actorTerminationState(ActorRuntime runtime, ActorRuntime.ActorId id) {
        try {
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((java.util.Map<?, ?>) actorsField.get(runtime)).get(id);
            if (cell == null) return "actor removed from registry";
            StringBuilder out = new StringBuilder("Actor " + id + " ");
            for (String name : new String[] {
                    "scheduled", "scheduledDispatchToken", "carrierActive",
                    "carrierOwnerThread", "activeTurns", "stopped", "finalized", "children"
            }) {
                var field = cell.getClass().getDeclaredField(name);
                field.setAccessible(true);
                out.append(name).append("=").append(field.get(cell)).append(" ");
            }
            return out.toString();
        } catch (ReflectiveOperationException e) {
            return "diagnostic reflection failed: " + e;
        }
    }
    @Test
    void returningBeforeActorTurnRunsCannotDelegateGuestCodeToProducerThread()
            throws Exception {
        AtomicReference<Runnable> escaped = new AtomicReference<>();
        CountDownLatch captured = new CountDownLatch(1);
        AtomicBoolean actorConstructorInvoked = new AtomicBoolean();
        ActorRuntime.TurnExecutor malicious = turn -> {
            if (!escaped.compareAndSet(null, turn)) {
                throw new IllegalStateException("unexpected second dispatch");
            }
            captured.countDown();
            // Deliberately violate the TurnExecutor synchronous handoff contract.
        };

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 16),
                malicious)) {
            var ref = runtime.<String>spawnShared(() -> {
                actorConstructorInvoked.set(true);
                return (message, context) -> { };
            });
            // Actors are lazy: a message activates the mailbox dispatcher.
            // Without an enqueued turn the host TurnExecutor is never called.
            ref.send("activate");
            assertTrue(captured.await(3, TimeUnit.SECONDS));
            assertFalse(actorConstructorInvoked.get(),
                    "no actor constructor ran on an admitted carrier");
            assertNotNull(escaped.get());

            // The host's no-op execute() must fail the actor immediately,
            // not wait indefinitely for an escaped callback to be invoked.
            assertTrue(ref.awaitTermination(3, TimeUnit.SECONDS),
                    () -> actorTerminationState(runtime, ref.id())
                            + " failure=" + ref.failure());
            assertTrue(ref.failure().isPresent(),
                    "TurnExecutor returning without executing must fail closed");
            // A delayed callback cannot revive an already-retired actor or
            // execute the actor constructor on an unauthorized host thread.
            assertThrows(IllegalStateException.class, escaped.get()::run);
            assertFalse(actorConstructorInvoked.get());
            assertTrue(ref.failure().isPresent(),
                    "off-carrier execution must cause an observable failure");
            assertFalse(runtime.currentThreadOwnsActorExecutionLease());
        }
    }

    @Test
    void duplicateTurnCallbackFailsWithoutExecutingAnActorTwice() throws Exception {
        AtomicBoolean callbackDuplicated = new AtomicBoolean();
        AtomicReference<Throwable> violation = new AtomicReference<>();
        AtomicBoolean factoryExecuted = new AtomicBoolean();
        ActorRuntime.TurnExecutor duplicate = turn -> {
            turn.run();
            callbackDuplicated.set(true);
            try {
                turn.run();
            } catch (IllegalStateException rejected) {
                violation.set(rejected);
                throw rejected;
            }
        };

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 16), duplicate)) {
            var ref = runtime.<String>spawnShared(() -> {
                if (!factoryExecuted.compareAndSet(false, true)) {
                    throw new IllegalStateException("actor constructor executed twice");
                }
                return (message, context) -> { };
            });
            ref.send("activate");
            assertTrue(ref.awaitTermination(3, TimeUnit.SECONDS),
                    () -> actorTerminationState(runtime, ref.id())
                            + " failure=" + ref.failure());
            assertTrue(callbackDuplicated.get());
            assertNotNull(violation.get());
            assertTrue(factoryExecuted.get());
            assertTrue(ref.failure().isPresent(),
                    "duplicate actor callback must terminate its actor");
        }
    }
    @Test
    void swallowedDuplicateTurnCallbackStillTerminatesActor() throws Exception {
        AtomicBoolean factoryExecuted = new AtomicBoolean();
        AtomicBoolean suppressedError = new AtomicBoolean();
        ActorRuntime.TurnExecutor hostile = turn -> {
            turn.run();
            try {
                turn.run();
            } catch (IllegalStateException ignored) {
                // The host cannot hide a second actor callback attempt
                // merely by suppressing the guard's exception.
                suppressedError.set(true);
            }
        };
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 16), hostile)) {
            var ref = runtime.<String>spawnShared(() -> {
                assertTrue(factoryExecuted.compareAndSet(false, true));
                return (message, context) -> { };
            });
            ref.send("activate");
            assertTrue(ref.awaitTermination(3, TimeUnit.SECONDS),
                    () -> actorTerminationState(runtime, ref.id())
                            + " failure=" + ref.failure());
            assertTrue(suppressedError.get());
            assertTrue(factoryExecuted.get());
            assertTrue(ref.failure().isPresent(),
                    "an attempted duplicate must be fail-stop even if the host swallowed the error");
        }
    }
}
