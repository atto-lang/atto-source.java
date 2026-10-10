package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.tomlj.Toml;

@Timeout(15)
final class ActorTurnContentionProbeTest {
    @Test
    void typedFlagIsBackedByFlags2EnvManifest() throws Exception {
        var contract = Toml.parse(Files.readString(Path.of(".cli-flags.toml")));
        assertTrue(contract.errors().isEmpty(), () -> contract.errors().toString());
        assertEquals("bool", contract.getString("flags.use_core_mutexes.type"));
        assertEquals(CoreRuntimeFlags.ENV_USE_CORE_MUTEXES,
                contract.getString("flags.use_core_mutexes.env"));
        assertEquals(Boolean.FALSE, contract.getBoolean("flags.use_core_mutexes.default"));
        assertFalse(CoreRuntimeFlags.fromFlags2EnvMap(Map.of()).useCoreMutexes());
        assertTrue(CoreRuntimeFlags.fromFlags2EnvMap(
                Map.of(CoreRuntimeFlags.ENV_USE_CORE_MUTEXES, "yes")).useCoreMutexes());
        assertFalse(CoreRuntimeFlags.fromFlags2EnvMap(
                Map.of(CoreRuntimeFlags.ENV_USE_CORE_MUTEXES, "0")).useCoreMutexes());
        assertThrows(IllegalArgumentException.class, () -> CoreRuntimeFlags.fromFlags2EnvMap(
                Map.of(CoreRuntimeFlags.ENV_USE_CORE_MUTEXES, "sometimes")));
    }

    @Test
    void overlappingCarriersAreDetectedWithoutBlockingOrStealingOwnership() throws Exception {
        AtomicLong violations = new AtomicLong();
        var probe = new ActorTurnContentionProbe(ActorRuntime.ActorId.create(), violations);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        Thread owner = Thread.ofPlatform().start(() -> {
            try (var ignored = probe.enter()) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test timed out waiting for owner release");
                }
            } catch (Throwable failure) {
                ownerFailure.set(failure);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var failure = assertThrows(IllegalStateException.class, probe::enter);
            assertTrue(failure.getMessage().contains("contention probe violation"));
            assertEquals(1, violations.get());
        } finally {
            release.countDown();
            owner.join(5000);
        }
        assertFalse(owner.isAlive());
        assertNull(ownerFailure.get());
        try (var ignored = probe.enter()) {
            assertEquals(1, violations.get(), "a released actor can resume on another carrier");
        }
    }

    @Test
    void unrelatedActorsCanExecuteAtTheSameTime() throws Exception {
        AtomicLong violations = new AtomicLong();
        var first = new ActorTurnContentionProbe(ActorRuntime.ActorId.create(), violations);
        var second = new ActorTurnContentionProbe(ActorRuntime.ActorId.create(), violations);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread owner = Thread.ofPlatform().start(() -> {
            try (var ignored = first.enter()) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try (var ignored = second.enter()) {
                assertEquals(0, violations.get());
            }
        } finally {
            release.countDown();
            owner.join(5000);
        }
        assertFalse(owner.isAlive());
    }

    @Test
    void runtimeAcceptsTypedDiagnosticFlagWithoutSerializingOtherActors() throws Exception {
        var flags = new CoreRuntimeFlags(true);
        var config = new ActorRuntime.DispatcherConfig(2, 2, 16, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config,
                ActorRuntime.TurnExecutor.direct(), ActorRuntime.ControlDispatcher.unavailable(), flags)) {
            assertTrue(runtime.coreRuntimeFlags().useCoreMutexes());
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> context.self().stop());
            actor.send(1);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.coreContentionViolationCount());
        }
    }

    @Test
    void probeRejectsReleaseFromAnUnrelatedCarrier() throws Exception {
        AtomicLong violations = new AtomicLong();
        var probe = new ActorTurnContentionProbe(ActorRuntime.ActorId.create(), violations);
        var lease = probe.enter();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrongCarrier = Thread.ofPlatform().start(() -> {
            try {
                lease.close();
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        wrongCarrier.join(5000);
        assertFalse(wrongCarrier.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(1, violations.get());
        lease.close();
        try (var ignored = probe.enter()) {
            assertEquals(1, violations.get());
        }
    }

    @Test
    void injectedConcurrentActorTurnTripsRuntimeProbeAndCleansUp() throws Exception {
        CountDownLatch insideTurn = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        ActorRuntime.TurnExecutor adversarial = turn -> {
            Thread owner = Thread.ofPlatform().start(() -> {
                try {
                    turn.run();
                } catch (Throwable failure) {
                    ownerFailure.set(failure);
                }
            });
            try {
                assertTrue(insideTurn.await(5, TimeUnit.SECONDS));
                try {
                    turn.run(); // fault injection: two carriers, same actor
                } catch (IllegalStateException expected) {
                    // The runtime may propagate or supervise the fail-stop.
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            } finally {
                releaseOwner.countDown();
                try {
                    owner.join(5000);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                assertFalse(owner.isAlive());
            }
        };
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config, adversarial,
                ActorRuntime.ControlDispatcher.unavailable(), new CoreRuntimeFlags(true))) {
            var actor = runtime.<Integer>spawnShared(() -> (value, context) -> {
                insideTurn.countDown();
                try {
                    assertTrue(releaseOwner.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                context.self().stop();
            });
            actor.send(1);
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(ownerFailure.get());
            assertEquals(1, runtime.coreContentionViolationCount(),
                    "injected overlapping actor entry must trip the independent witness");
            assertTrue(actor.failure().orElseThrow().getMessage().contains("contention probe"));
        }
    }
}
