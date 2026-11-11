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
        assertNull(table.tryAcquire(0, B, PARTITION, 7),
                "retired socket cannot be reused before physical close ack");
        assertFalse(table.tryConfirmPhysicalClose(0, lease.generation()),
                "stale generation must not clear quarantine");
        assertTrue(table.tryConfirmPhysicalClose(0, lease.generation() + 1),
                "trusted close ack reopens the empty slot");
        var next = table.tryAcquire(0, B, PARTITION, 7);
        assertNotNull(next);
        assertTrue(next.generation() > lease.generation());
        assertFalse(table.tryRelease(lease, A, 7), "stale capability");
        assertFalse(table.tryConfirmPhysicalClose(0, lease.generation() + 1),
                "live slot cannot be rearmed again");
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
        assertNull(table.tryAcquire(0, B, PARTITION, 8),
                "policy rollover must fence old physical socket");
        assertTrue(table.tryConfirmPhysicalClose(0, active.generation() + 1));
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
    void idleSocketsAreQuarantinedByPolicyEpochRollover() {
        var table = fresh();
        assertTrue(table.tryRevokeEpoch(8));
        assertNull(table.tryAcquire(0, B, PARTITION, 8),
                "idle preconnected socket may carry old TLS credentials");
        assertFalse(table.tryConfirmPhysicalClose(0, 0));
        assertTrue(table.tryConfirmPhysicalClose(0, 1));
        assertNotNull(table.tryAcquire(0, B, PARTITION, 8));
    }

    @Test
    void thousandRetirementsNeverReissueStaleLease() {
        var table = fresh();
        TcpPoolLeaseLedger.Lease last = null;
        for (int iteration = 0; iteration < 1024; iteration++) {
            var current = acquire(table);
            if (last != null) assertFalse(table.tryBegin(last, A, 7));
            if ((iteration & 1) == 0) {
                assertTrue(table.tryBegin(current, A, 7));
                assertTrue(table.tryRelease(current, A, 7));
                assertFalse(table.tryConfirmPhysicalClose(0, current.generation() + 1),
                        "pending reactor operation is not quiescence");
                assertTrue(table.tryComplete(current));
            } else {
                assertTrue(table.tryRelease(current, A, 7));
            }
            assertNull(table.tryAcquire(0, A, PARTITION, 7));
            assertTrue(table.tryConfirmPhysicalClose(0, current.generation() + 1));
            last = current;
        }
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
    @Test
    void realLoopbackSocketRetiresBeforeAnotherActorGetsTheSlot() throws Exception {
        // Host-only physical-close integration witness: does not grant guest
        // capabilities and does not imply safe generic TCP response demux.
        var table = fresh();
        try (var listener = java.nio.channels.ServerSocketChannel.open()) {
            listener.bind(new java.net.InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), 0));
            var address = (java.net.InetSocketAddress) listener.getLocalAddress();
            var original = acquire(table);
            try (var first = java.nio.channels.SocketChannel.open(address);
                 var peer = listener.accept()) {
                assertTrue(table.tryBegin(original, A, 7));
                var written = java.nio.ByteBuffer.wrap(new byte[] {11, 22, 33, 44});
                while (written.hasRemaining()) first.write(written);
                var received = java.nio.ByteBuffer.allocate(4);
                while (received.hasRemaining()) {
                    assertTrue(peer.read(received) > 0,
                            "real loopback peer must receive the full frame");
                }
                assertArrayEquals(new byte[] {11, 22, 33, 44}, received.array());
                assertTrue(table.tryRelease(original, A, 7),
                        "actor release revokes admission while physical socket exists");
                assertNull(table.tryAcquire(0, B, PARTITION, 7),
                        "second actor cannot borrow still-live socket");
                assertFalse(table.tryConfirmPhysicalClose(
                        0, original.generation() + 1),
                        "in-flight read/write cannot be cleared by a close acknowledgement");
                assertTrue(table.tryComplete(original));
                assertNull(table.tryAcquire(0, B, PARTITION, 7),
                        "reactor completion alone is not physical close");
                // Close both ends via AutoCloseable before trusted acknowledgment.
            }
            assertTrue(table.tryConfirmPhysicalClose(
                    0, original.generation() + 1),
                    "only host code after real channel close may acknowledge");
            var next = table.tryAcquire(0, B, PARTITION, 7);
            assertNotNull(next, "new incarnation can borrow only after close");
            assertTrue(next.generation() > original.generation());
            try (var second = java.nio.channels.SocketChannel.open(address);
                 var peer = listener.accept()) {
                assertTrue(table.tryBegin(next, B, 7));
                var output = java.nio.ByteBuffer.wrap(new byte[] {55, 66});
                while (output.hasRemaining()) second.write(output);
                var input = java.nio.ByteBuffer.allocate(2);
                while (input.hasRemaining()) assertTrue(peer.read(input) > 0);
                assertArrayEquals(new byte[] {55, 66}, input.array());
                assertFalse(table.tryBegin(original, A, 7),
                        "old lease cannot access newly connected socket");
                assertTrue(table.tryComplete(next));
                assertTrue(table.tryRelease(next, B, 7));
            }
            assertTrue(table.tryConfirmPhysicalClose(
                    0, next.generation() + 1));
            assertTrue(table.tryStop());
        }
    }

    @Test
    void completionReplayCannotFinishAnotherExchangeOnTheSameLease() {
        var table = fresh();
        var first = acquire(table);
        assertTrue(table.tryBegin(first, A, 7));
        assertTrue(table.tryComplete(first));
        // Without a physical-op nonce, begin again on the same lease would
        // let a late duplicate completion finish a new exchange. Deny it.
        assertFalse(table.tryBegin(first, A, 7),
                "v1 is one complete exchange per issued lease");
        assertFalse(table.tryComplete(first),
                "replayed completion must not affect new work");
        assertTrue(table.tryRelease(first, A, 7));
        assertTrue(table.tryConfirmPhysicalClose(
                0, first.generation() + 1));
        var next = table.tryAcquire(0, B, PARTITION, 7);
        assertNotNull(next);
        assertTrue(table.tryBegin(next, B, 7));
        assertFalse(table.tryComplete(first),
                "old completion cannot finish replacement lease");
        assertFalse(table.tryBegin(next, B, 7));
        assertTrue(table.tryComplete(next));
        assertTrue(table.tryRelease(next, B, 7));
    }

}
