package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class GpuDeviceBrokerTest {
    @Test
    void atomicallyEnforcesDeviceSlotsMemoryAndOwnerQuota() {
        try (var broker = new GpuDeviceBroker(List.of(
                new GpuDeviceBroker.DeviceSpec("gpu0", 3, 1024),
                new GpuDeviceBroker.DeviceSpec("gpu1", 2, 2048)))) {
            var owner = broker.issueOwner("group-1", 2);

            var a = broker.tryReserve(owner, "gpu0", 400).orElseThrow();
            var b = broker.tryReserve(owner, "gpu0", 400).orElseThrow();
            assertTrue(broker.tryReserve(owner, "gpu0", 1).isEmpty(),
                    "owner quota must win even though the device has another slot");
            assertTrue(broker.tryReserve(owner, "gpu1", 1).isEmpty(),
                    "owner in-flight quota is global across devices");

            var snapshot = broker.snapshot().stream()
                    .filter(s -> s.deviceId().equals("gpu0")).findFirst().orElseThrow();
            assertEquals(2, snapshot.inFlight());
            assertEquals(800, snapshot.reservedBytes());

            a.close();
            a.close(); // idempotent
            var c = broker.tryReserve(owner, "gpu0", 224).orElseThrow();
            assertTrue(broker.tryReserve(owner, "gpu0", 401).isEmpty(),
                    "memory must be reserved atomically without overflow");

            b.close();
            c.close();
            assertTrue(broker.ownerDrained(owner));
        }
    }

    @Test
    void ownerCloseAndDeviceDisableStopNewReservationsWithoutReclaimingLiveWork() {
        try (var broker = new GpuDeviceBroker(List.of(
                new GpuDeviceBroker.DeviceSpec("gpu0", 4, 4096)))) {
            var owner = broker.issueOwner("group", 4);
            var live = broker.tryReserve(owner, "gpu0", 1024).orElseThrow();

            broker.beginOwnerClose(owner);
            assertTrue(broker.tryReserve(owner, "gpu0", 1).isEmpty());
            assertFalse(broker.ownerDrained(owner));
            live.close();
            assertTrue(broker.ownerDrained(owner));

            var other = broker.issueOwner("group-2", 4);
            var beforeLoss = broker.tryReserve(other, "gpu0", 512).orElseThrow();
            broker.disableDevice("gpu0");
            assertTrue(broker.tryReserve(other, "gpu0", 1).isEmpty(),
                    "device loss blocks new work immediately");
            assertThrows(IllegalStateException.class, broker::close,
                    "live work remains charged until provider quiescence");
            beforeLoss.close();
        }
    }

    @Test
    void concurrentReservationsNeverExceedPhysicalOrOwnerLimits() throws Exception {
        try (var broker = new GpuDeviceBroker(List.of(
                new GpuDeviceBroker.DeviceSpec("gpu0", 4, 4 * 256L)))) {
            var owner = broker.issueOwner("parallel-group", 3);
            final int workers = 24;
            var ready = new CountDownLatch(workers);
            var start = new CountDownLatch(1);
            var admittedThree = new CountDownLatch(3);
            var release = new CountDownLatch(1);
            var done = new CountDownLatch(workers);
            var admitted = new AtomicInteger();
            var error = new AtomicReference<Throwable>();

            for (int i = 0; i < workers; i++) {
                Thread.ofPlatform().start(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("reservation start timeout");
                        Optional<GpuDeviceBroker.Lease> lease =
                                broker.tryReserve(owner, "gpu0", 256);
                        if (lease.isPresent()) {
                            admitted.incrementAndGet();
                            admittedThree.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("lease release timeout");
                            lease.orElseThrow().close();
                        }
                    } catch (Throwable failure) {
                        error.compareAndSet(null, failure);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(admittedThree.await(5, TimeUnit.SECONDS),
                    "owner quota should admit exactly three held reservations");
            var snap = broker.snapshot().get(0);
            assertTrue(snap.inFlight() <= 3);
            assertTrue(snap.reservedBytes() <= 3 * 256L);
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(3, admitted.get(),
                    "owner quota must atomically cap a simultaneous reservation burst");
            assertTrue(broker.ownerDrained(owner));
        }
    }

    @Test
    void malformedOrForeignAuthorityFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceBroker.DeviceSpec("../gpu", 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceBroker.DeviceSpec("gpu0", 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceBroker.DeviceSpec("gpu0", 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceBroker(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuDeviceBroker(List.of(
                        new GpuDeviceBroker.DeviceSpec("gpu0", 1, 1),
                        new GpuDeviceBroker.DeviceSpec("gpu0", 1, 1))));

        try (var first = new GpuDeviceBroker(List.of(
                     new GpuDeviceBroker.DeviceSpec("gpu0", 1, 128)));
             var second = new GpuDeviceBroker(List.of(
                     new GpuDeviceBroker.DeviceSpec("gpu0", 1, 128)))) {
            var foreignOwner = first.issueOwner("first", 1);
            assertThrows(SecurityException.class,
                    () -> second.tryReserve(foreignOwner, "gpu0", 1));
            assertThrows(IllegalArgumentException.class,
                    () -> first.tryReserve(foreignOwner, "gpu0", 0));
            assertTrue(first.tryReserve(foreignOwner, "missing", 1).isEmpty());
        }
    }
}
