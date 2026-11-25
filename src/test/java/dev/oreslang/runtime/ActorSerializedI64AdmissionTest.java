package dev.oreslang.runtime;

import dev.oreslang.types.ActorDataDescriptorFixture;
import dev.oreslang.types.PortableCodecRegistry.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorSerializedI64AdmissionTest {
    @Test
    void pinnedWireUsesActualMailboxAndRecoversFromDenials() throws Exception {
        Descriptor pin = ActorDataDescriptorFixture.scalar(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2);
        byte[] wire = MessagePackWireV2.encode(pin.schemaId(), MessagePackWireV2.Shape.i64(), 42L);
        long bytes = 256L + 16L * wire.length, work = 3L * wire.length + 1L;
        AtomicInteger count = new AtomicInteger();
        AtomicLong received = new AtomicLong();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Long>spawnShared(() -> (message, context) -> {
                received.set(message);
                count.incrementAndGet();
            });
            runtime.bindSerializedI64(ref, pin, bytes, work, 1);
            assertThrows(IllegalStateException.class, () -> runtime.bindSerializedI64(ref, pin, bytes, work, 1));
            long epoch = ref.generation();
            assertThrows(IllegalArgumentException.class, () -> runtime.sendSerializedI64(ref,
                    epoch, Format.PROTOBUF, pin.schemaId(), 1, pin.fingerprintHex(), wire));
            assertThrows(IllegalArgumentException.class, () -> runtime.sendSerializedI64(ref,
                    epoch + 1, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire));
            byte[] malformed = wire.clone(); malformed[0] = 0;
            assertThrows(IllegalArgumentException.class, () -> runtime.sendSerializedI64(ref,
                    epoch, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), malformed));
            runtime.sendSerializedI64(ref, epoch, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire);
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(42L, received.get());
            assertEquals(1, count.get());
            runtime.sendSerializedI64(ref, epoch, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire);
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(2, count.get());
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> runtime.sendSerializedI64(ref,
                    epoch, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire));
        }
    }
}
