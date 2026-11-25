package dev.oreslang.runtime;

import dev.oreslang.types.ActorDataDescriptorFixture;
import dev.oreslang.types.PortableCodecRegistry.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class SupervisorSerializedDataIntegrationTest {
    @Test
    void serializedScalarUsesSamePermitAndCannotCrossAncestorFence() throws Exception {
        Descriptor pin = ActorDataDescriptorFixture.scalar(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2);
        byte[] wire = MessagePackWireV2.encode(pin.schemaId(), MessagePackWireV2.Shape.i64(), 42L);
        long bytes = 256L + 16L * wire.length, work = 3L * wire.length + 1;
        AtomicLong received = new AtomicLong();
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(runtime, bridge, control, ignored -> false);
            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { throw new IllegalStateException("fence"); });
            var child = runtime.<Long>spawnShared(() -> (message, turn) -> received.set(message));
            recovery.attach(parent, null);
            long childId = recovery.attach(child, parent);
            runtime.bindSerializedI64(child, pin, bytes, work, 1);
            byte[] malformed = wire.clone(); malformed[0] = 0;
            assertThrows(IllegalArgumentException.class, () -> runtime.sendSerializedI64(child,
                    child.generation(), pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), malformed));
            assertEquals(0, bridge.dataAdmissionStatus(childId).committed());
            runtime.sendSerializedI64(child, child.generation(), pin.format(),
                    pin.schemaId(), 1, pin.fingerprintHex(), wire);
            child.drain().get(5, TimeUnit.SECONDS);
            assertEquals(42L, received.get());
            assertEquals(1, bridge.dataAdmissionStatus(childId).committed());
            parent.send("fail");
            assertThrows(ExecutionException.class, () -> parent.done().get(5, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> runtime.sendSerializedI64(child,
                    child.generation(), pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire));
            assertEquals(1, bridge.dataAdmissionStatus(childId).committed());
            assertEquals(0, bridge.dataAdmissionStatus(childId).inFlight());
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS))
                throw new AssertionError("timed out waiting for test interleaving");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitStopped(ActorRuntime.ActorRef<?> ref) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ref.isAlive() && System.nanoTime() < deadline) Thread.sleep(1);
        assertFalse(ref.isAlive(), "structured stop must mark the actor stopped");
    }

    @Test
    void directCancellationCannotFinalizeWhileDecodedIngressOwnsCredits() throws Exception {
        Descriptor pin = ActorDataDescriptorFixture.scalar(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2);
        byte[] wire = MessagePackWireV2.encode(pin.schemaId(), MessagePackWireV2.Shape.i64(), 42L);
        long bytes = 256L + 16L * wire.length, work = 3L * wire.length + 1;
        AtomicLong delivered = new AtomicLong();
        var decoded = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService senders = Executors.newSingleThreadExecutor()) {
            var child = runtime.<Long>spawnShared(() -> (message, turn) -> delivered.incrementAndGet());
            runtime.bindSerializedI64(child, pin, bytes, work, 1);
            Future<?> send = senders.submit(() -> runtime.sendSerializedI64ForTest(child,
                    child.generation(), pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire,
                    () -> { decoded.countDown(); await(resume); }));
            try {
                assertTrue(decoded.await(5, TimeUnit.SECONDS), "actual decoder did not reach barrier");
                runtime.cancel(child);
                awaitStopped(child);
                assertFalse(child.done().isDone(), "outstanding decode must prevent finalization");
                assertEquals(0, delivered.get());
            } finally {
                resume.countDown();
            }
            var denied = assertThrows(ExecutionException.class, () -> send.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, denied.getCause());
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS), "decode exit must unblock finalization");
            assertEquals(0, delivered.get(), "cancelled ingress must never publish");
            // A new endpoint must be able to use the runtime after rollback.
            var next = runtime.<Long>spawnShared(() -> (message, turn) -> delivered.set(message));
            runtime.bindSerializedI64(next, pin, bytes, work, 1);
            runtime.sendSerializedI64(next, next.generation(), pin.format(), pin.schemaId(), 1,
                    pin.fingerprintHex(), wire);
            next.drain().get(5, TimeUnit.SECONDS);
            assertEquals(42L, delivered.get());
        }
    }

    @Test
    void ancestorFenceAfterDecodeBeforePermitRejectsAndDelaysChildFinalization() throws Exception {
        Descriptor pin = ActorDataDescriptorFixture.scalar(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2);
        byte[] wire = MessagePackWireV2.encode(pin.schemaId(), MessagePackWireV2.Shape.i64(), 42L);
        long bytes = 256L + 16L * wire.length, work = 3L * wire.length + 1;
        AtomicLong delivered = new AtomicLong();
        var decoded = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor();
             ExecutorService senders = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            // Deliberately optimistic test oracle: it must not override the
            // runtime DONE barrier. Not independent physical-release evidence.
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(runtime, bridge, control, ignored -> true);
            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { throw new IllegalStateException("fence"); });
            var child = runtime.<Long>spawnShared(() -> (message, turn) -> delivered.incrementAndGet());
            long parentId = recovery.attach(parent, null);
            long childId = recovery.attach(child, parent);
            runtime.bindSerializedI64(child, pin, bytes, work, 1);
            Future<?> send = senders.submit(() -> runtime.sendSerializedI64ForTest(child,
                    child.generation(), pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire,
                    () -> { decoded.countDown(); await(resume); }));
            try {
                assertTrue(decoded.await(5, TimeUnit.SECONDS));
                assertEquals(0, bridge.dataAdmissionStatus(childId).inFlight(), "decode is not a permit");
                parent.send("fail");
                assertThrows(ExecutionException.class, () -> parent.done().get(5, TimeUnit.SECONDS));
                awaitStopped(child);
                assertFalse(child.done().isDone(), "child decode still owns old-generation resources");
                assertNull(bridge.beginDataAdmissionFromRuntime(childId, child.generation()));
                assertTrue(bridge.commitAndAcknowledge(bridge.deliver(parentId), () -> { }));
                assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.CHILD_STILL_RUNNING,
                        recovery.restartBlocker(parentId));
            } finally {
                resume.countDown();
            }
            var denied = assertThrows(ExecutionException.class, () -> send.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, denied.getCause());
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, delivered.get());
            assertEquals(0, bridge.dataAdmissionStatus(childId).committed());
            assertEquals(0, bridge.dataAdmissionStatus(childId).inFlight());
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.READY,
                    recovery.restartBlocker(parentId), "logical restart only; oracle is synthetic");
        }
    }

    @Test
    void nestedDescriptorUsesActualMailboxAndExactQuota() throws Exception {
        Descriptor pin = ActorDataDescriptorFixture.shape(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2,
                new Sequence(SequenceKind.LIST,
                        new Sequence(SequenceKind.LIST, new Scalar(ScalarKind.I64), -1), -1));
        var shape = MessagePackWireV2.Shape.list(MessagePackWireV2.Shape.list(MessagePackWireV2.Shape.i64()));
        var expected = java.util.List.of(java.util.List.of(1L, 2L), java.util.List.of(3L));
        byte[] wire = MessagePackWireV2.encode(pin.schemaId(), shape, expected);
        long bytes = ActorDataMessagePackPlanV1.logicalBytes(wire.length);
        long work = ActorDataMessagePackPlanV1.logicalWork(wire.length);
        var received = new java.util.concurrent.atomic.AtomicReference<Object>();
        var count = new AtomicLong();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Object>spawnShared(() -> (value, turn) -> {
                received.set(value); count.incrementAndGet();
            });
            runtime.bindSerializedData(actor, pin, bytes, work, 1);
            var under = runtime.<Object>spawnShared(() -> (value, turn) -> count.incrementAndGet());
            runtime.bindSerializedData(under, pin, bytes - 1, work, 1);
            assertThrows(IllegalArgumentException.class, () -> runtime.sendSerializedData(under,
                    under.generation(), pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire));
            byte[] invalid = wire.clone(); invalid[0] = 0;
            assertThrows(IllegalArgumentException.class, () -> runtime.sendSerializedData(actor,
                    actor.generation(), pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), invalid));
            runtime.sendSerializedData(actor, actor.generation(), pin.format(), pin.schemaId(),
                    1, pin.fingerprintHex(), wire);
            java.util.Arrays.fill(wire, (byte) 0);
            actor.drain().get(5, TimeUnit.SECONDS);
            assertEquals(expected, received.get());
            assertEquals(1, count.get());
            byte[] retry = MessagePackWireV2.encode(pin.schemaId(), shape, expected);
            runtime.sendSerializedData(actor, actor.generation(), pin.format(), pin.schemaId(),
                    1, pin.fingerprintHex(), retry);
            actor.drain().get(5, TimeUnit.SECONDS);
            assertEquals(2, count.get());
        }
    }
}
