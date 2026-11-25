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
}
