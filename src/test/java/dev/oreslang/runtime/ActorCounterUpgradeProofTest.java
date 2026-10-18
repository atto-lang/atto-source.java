package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual ActorRuntime physical turns + trusted Java host code replacement.
 * This is not an Oreslang source compiler/AOT actor migration.
 */
@Timeout(30)
final class ActorCounterUpgradeProofTest {
    private static String hash(char ch) { return String.valueOf(ch).repeat(64); }
    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(
                hash(code), hash('1'), hash('2'), hash('3'));
    }
    private static final LongBinaryOperator V1 = Math::addExact;
    private static final LongBinaryOperator V2 =
            (value, delta) -> Math.addExact(value, Math.abs(delta));
    private static final LongUnaryOperator IDENTITY = value -> value;

    private static void processed(ActorRuntime runtime, ActorRuntime.ActorRef<?> ref)
            throws Exception {
        runtime.drain(ref).get(5, TimeUnit.SECONDS);
    }

    private static void eventuallyUpgrade(
            ActorCounterUpgradeProof proof,
            ActorUpgradeEpochGate.Image proposed,
            LongBinaryOperator apply,
            LongUnaryOperator restore) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!proof.upgrade(proposed, apply, restore)) {
            if (System.nanoTime() >= deadline)
                fail("actor did not quiesce for committed upgrade");
            Thread.sleep(2);
        }
    }

    @Test
    void counterPreservesRealActorRefIdentityAndStateThroughCodeVersionSwitch()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var proof = ActorCounterUpgradeProof.spawn(runtime, image('a'), 1, V1);
            var ref = proof.actorRef();
            var address = ref.address();
            ref.getReady().get(5, TimeUnit.SECONDS);
            ref.send(41);
            processed(runtime, ref);
            assertEquals(42, proof.value());
            eventuallyUpgrade(proof, image('b'), V2, IDENTITY);
            assertSame(ref, proof.actorRef());
            assertEquals(address, ref.address());
            assertEquals(2, proof.epoch());
            assertEquals(image('b'), proof.image());
            assertEquals(42, proof.value());
            ref.send(1);
            processed(runtime, ref);
            assertEquals(43, proof.value());
            ref.send(-2);
            processed(runtime, ref);
            assertEquals(45, proof.value(),
                    "version 2 must execute the replacement behavior");
            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedRestoreAndIncompatibleAbiRollbackWithoutPoisoningOldActor()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var proof = ActorCounterUpgradeProof.spawn(runtime, image('a'), 42, V1);
            var ref = proof.actorRef();
            ref.getReady().get(5, TimeUnit.SECONDS);
            // First deliberately incompatible capability receipt; fail closed.
            var wrong = new ActorUpgradeEpochGate.Image(
                    hash('b'), hash('1'), hash('2'), hash('f'));
            assertThrows(SecurityException.class,
                    () -> proof.upgrade(wrong, V2, IDENTITY));
            assertEquals(1, proof.epoch());
            assertEquals(42, proof.value());

            assertThrows(IllegalStateException.class, () ->
                    proof.upgrade(image('b'), V2, value -> {
                        throw new IllegalStateException("candidate failed to initialize");
                    }));
            assertEquals(image('a'), proof.image());
            assertEquals(1, proof.epoch());
            assertThrows(SecurityException.class,
                    () -> proof.upgrade(image('b'), V2, value -> value + 1),
                    "same-schema restoration must preserve the exact counter value");
            assertEquals(image('a'), proof.image());
            assertEquals(1, proof.epoch());
            assertEquals(42, proof.value());
            ref.send(1);
            processed(runtime, ref);
            assertEquals(43, proof.value(), "old code remains active after rollback");
            eventuallyUpgrade(proof, image('b'), V2, IDENTITY);
            assertEquals(43, proof.value());
            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void activeCarrierMakesUpgradeNonblockingAndRollbackPreservesMessages()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        LongBinaryOperator blocked = (value, delta) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("blocked actor was not released");
                return Math.addExact(value, delta);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                error.set(interruption);
                throw new IllegalStateException(interruption);
            }
        };

        try (ActorRuntime runtime = new ActorRuntime()) {
            var proof = ActorCounterUpgradeProof.spawn(runtime, image('a'), 1, blocked);
            var ref = proof.actorRef();
            ref.getReady().get(5, TimeUnit.SECONDS);
            ref.send(41);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                assertFalse(proof.upgrade(image('b'), V2, IDENTITY),
                        "an admitted old-generation turn prevents publication");
                assertEquals(1, proof.epoch());
            } finally {
                release.countDown();
            }
            processed(runtime, ref);
            assertNull(error.get());
            assertEquals(42, proof.value());
            eventuallyUpgrade(proof, image('b'), V2, IDENTITY);
            ref.send(1);
            processed(runtime, ref);
            assertEquals(43, proof.value());
            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
