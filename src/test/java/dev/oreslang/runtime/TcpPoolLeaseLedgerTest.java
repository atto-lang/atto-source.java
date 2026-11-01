package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class TcpPoolLeaseLedgerTest {
    private static final String PARTITION = "tenant-a|tls|example.test:443";
    private static final TcpPoolLeaseLedger.ActorIdentity A =
            new TcpPoolLeaseLedger.ActorIdentity(101, 1);
    private static final TcpPoolLeaseLedger.ActorIdentity B =
            new TcpPoolLeaseLedger.ActorIdentity(102, 1);

    private static TcpPoolLeaseLedger fresh() {
        return new TcpPoolLeaseLedger(1, PARTITION, 7);
    }

    private static TcpPoolLeaseLedger.Lease acquire(TcpPoolLeaseLedger table) {
        TcpPoolLeaseLedger.Lease lease = table.tryAcquire(0, A, PARTITION, 7);
        assertNotNull(lease);
        return lease;
    }

    @Test
    void exclusivityAndIdentityCannotAliasRawTcp() {
        var table = fresh();
        var lease = acquire(table);
        assertNull(table.tryAcquire(0, B, PARTITION, 7));
        assertNull(table.tryAcquire(0, B, "tenant-b|tls|example.test:443", 7));
        assertFalse(table.tryBegin(lease, B, 7));
        assertFalse(table.tryBegin(lease,
                new TcpPoolLeaseLedger.ActorIdentity(101, 2), 7));
        assertFalse(table.tryRelease(lease, B, 7));
        assertTrue(table.tryBegin(lease, A, 7));
        assertFalse(table.tryBegin(lease, A, 7));
        assertTrue(table.tryRelease(lease, A, 7));
        assertNull(table.tryAcquire(0, B, PARTITION, 7),
                "cancellation never makes live socket reusable");
        assertFalse(table.tryBegin(lease, A, 7));
        assertTrue(table.tryComplete(lease));
        assertFalse(table.tryComplete(lease), "duplicate completion");
        var next = table.tryAcquire(0, B, PARTITION, 7);
        assertNotNull(next);
        assertTrue(next.generation() > lease.generation());
        assertFalse(table.tryRelease(lease, A, 7), "stale capability");
        assertTrue(table.tryRelease(next, B, 7));
    }

    @Test
    void partitionsEpochsAndSlotIndicesFailClosed() {
        var table = new TcpPoolLeaseLedger(2, PARTITION, 7);
        assertNull(table.tryAcquire(-1, A, PARTITION, 7));
        assertNull(table.tryAcquire(2, A, PARTITION, 7));
        assertNull(table.tryAcquire(0, null, PARTITION, 7));
        assertNull(table.tryAcquire(0,
                new TcpPoolLeaseLedger.ActorIdentity(0, 1), PARTITION, 7));
        assertNull(table.tryAcquire(0,
                new TcpPoolLeaseLedger.ActorIdentity(1, 0), PARTITION, 7));
        assertNull(table.tryAcquire(0, A, PARTITION, 6));
        assertNull(table.tryAcquire(0, A, "other-tenant", 7));
        assertThrows(IllegalArgumentException.class,
                () -> new TcpPoolLeaseLedger(0, PARTITION, 7));
        assertThrows(IllegalArgumentException.class,
                () -> new TcpPoolLeaseLedger(1025, PARTITION, 7));
        assertThrows(IllegalArgumentException.class,
                () -> new TcpPoolLeaseLedger(1, " ", 7));
        assertThrows(IllegalArgumentException.class,
                () -> new TcpPoolLeaseLedger(1, PARTITION, 0));
    }

    @Test
    void independentPhysicalSlotsDoNotBlockOneAnother() {
        var table = new TcpPoolLeaseLedger(2, PARTITION, 7);
        var a = table.tryAcquire(0, A, PARTITION, 7);
        var b = table.tryAcquire(1, B, PARTITION, 7);
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(table.tryBegin(a, A, 7));
        assertTrue(table.tryBegin(b, B, 7));
        assertTrue(table.tryComplete(a));
        assertTrue(table.tryRelease(a, A, 7));
        assertNull(table.tryAcquire(1, A, PARTITION, 7));
        assertTrue(table.tryComplete(b));
        assertTrue(table.tryRelease(b, B, 7));
    }

    @Test
    void cancellationEpochRevocationAndReAdmissionWaitForQuiescence() {
        var table = fresh();
        var active = acquire(table);
        assertTrue(table.tryBegin(active, A, 7));
        assertFalse(table.tryRevokeEpoch(7));
        assertFalse(table.tryRevokeEpoch(6));
        assertTrue(table.tryRevokeEpoch(8));
        assertNull(table.tryAcquire(0, B, PARTITION, 8));
        assertFalse(table.tryBegin(active, A, 7));
        assertTrue(table.tryComplete(active));
        var next = table.tryAcquire(0, B, PARTITION, 8);
        assertNotNull(next);
        assertFalse(table.tryComplete(active));
        assertTrue(table.tryRelease(next, B, 8));
    }

    @Test
    void shutdownIsTerminalIncludingAfterPendingCompletion() {
        var table = fresh();
        var active = acquire(table);
        assertTrue(table.tryBegin(active, A, 7));
        assertTrue(table.tryStop());
        assertFalse(table.tryBegin(active, A, 7));
        assertNull(table.tryAcquire(0, B, PARTITION, 7));
        assertTrue(table.tryComplete(active));
        assertNull(table.tryAcquire(0, B, PARTITION, 7),
                "stopped table must never resurrect TCP slot");
        assertTrue(table.tryStop(), "stop idempotent");
        var beforeDial = fresh();
        assertTrue(beforeDial.tryStop());
        assertNull(beforeDial.tryAcquire(0, A, PARTITION, 7));
    }

    @Test
    void foreignManagerTokensCannotRevokeLocalSocket() {
        var first = fresh();
        var second = fresh();
        var a = acquire(first);
        var b = acquire(second);
        assertFalse(first.tryBegin(b, A, 7));
        assertFalse(second.tryRelease(a, A, 7));
        assertTrue(first.tryRelease(a, A, 7));
        assertTrue(second.tryRelease(b, A, 7));
    }

    @Test
    void thirtyTwoContendersCannotOwnTheSamePhysicalSocket() throws Exception {
        var table = fresh();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Thread[] contenders = new Thread[32];
        for (int i = 0; i < contenders.length; i++) {
            final int actor = i + 1;
            contenders[i] = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (table.tryAcquire(0,
                            new TcpPoolLeaseLedger.ActorIdentity(actor, 1),
                            PARTITION, 7) != null) accepted.incrementAndGet();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        for (Thread thread : contenders) {
            thread.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(thread.isAlive(), "admission must not park a contender");
        }
        assertEquals(1, accepted.get());
        assertTrue(table.tryStop());
    }

    @Test
    void admissionIsNonparkingWhenAnotherThreadOwnsHostGate() throws Exception {
        var table = fresh();
        Field field = TcpPoolLeaseLedger.class.getDeclaredField("gate");
        field.setAccessible(true);
        var gate = (ReentrantLock) field.get(table);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            gate.lock();
            try {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                gate.unlock();
            }
        });
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS));
            assertNull(table.tryAcquire(0, A, PARTITION, 7),
                    "a contended host gate never parks actor admission");
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(holder.isAlive());
        }
    }
}
