package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A runtime-private actor mailbox is still a bounded Channel<T>, but it must
 * never acquire the global guest multi-channel select coordinator.
 *
 * <p>These tests deliberately exercise a held coordinator, concurrent writers,
 * close/drain, FIFO, capacity and non-escalation to public selectable endpoints.
 * ActorRuntime itself continues to enforce byte quotas, CONTROL priority,
 * cancellation and physical-turn exclusivity.
 */
final class ActorPrivateMailboxChannelTest {
    @Test
    void privateMailboxIsBoundedFifoAndClosePreservesBufferedMail() {
        ChannelRuntime.Channel<Integer> mailbox =
                ChannelRuntime.Channel.runtimePrivateMailbox(2);
        assertEquals(0, mailbox.size());
        assertFalse(mailbox.isClosed());
        assertTrue(mailbox.tryWrite(11));
        assertTrue(mailbox.tryWrite(22));
        assertEquals(2, mailbox.size());
        assertFalse(mailbox.tryWrite(33), "full mailbox may not silently admit");
        assertEquals(Optional.of(11), mailbox.tryRead());
        assertTrue(mailbox.tryWrite(33));
        mailbox.close(new IllegalStateException("stopped"));
        assertTrue(mailbox.isClosed());
        assertThrows(ChannelRuntime.ChannelClosedException.class,
                () -> mailbox.tryWrite(44));
        assertEquals(Optional.of(22), mailbox.tryRead());
        assertEquals(Optional.of(33), mailbox.tryRead());
        assertThrows(ChannelRuntime.ChannelClosedException.class, mailbox::tryRead);
        assertEquals(0, mailbox.size());
        mailbox.close(); // idempotent; cannot re-open after STOP
    }

    @Test
    void privateMailboxDrainRunsConsumerWithoutHoldingMailboxMonitor() {
        ChannelRuntime.Channel<Integer> mailbox =
                ChannelRuntime.Channel.runtimePrivateMailbox(2);
        assertTrue(mailbox.tryWrite(1));
        assertTrue(mailbox.tryWrite(2));
        Set<Integer> observed = new HashSet<>();
        assertTrue(mailbox.drainOne(value -> {
            observed.add(value);
            // This reentrant operation must be allowed, and the consumer must
            // never be invoked while holding the private mailbox monitor.
            assertTrue(mailbox.tryWrite(3));
        }));
        mailbox.close();
        assertTrue(mailbox.drainOne(observed::add));
        assertTrue(mailbox.drainOne(observed::add));
        assertFalse(mailbox.drainOne(observed::add));
        assertEquals(Set.of(1, 2, 3), observed);
    }

    @Test
    void privateMailboxCannotEscapeToGuestSelectOrAsyncRegistration() {
        ChannelRuntime.Channel<Integer> mailbox =
                ChannelRuntime.Channel.runtimePrivateMailbox(2);
        assertEquals(0, mailbox.pendingRegistrations());
        assertThrows(IllegalStateException.class, mailbox::input);
        assertThrows(IllegalStateException.class, mailbox::output);
        assertThrows(IllegalStateException.class, mailbox::readAsync);
        assertThrows(IllegalStateException.class, () -> mailbox.writeAsync(1));
        assertThrows(IllegalStateException.class, () -> ChannelRuntime.read(mailbox));
        assertThrows(IllegalStateException.class,
                () -> ChannelRuntime.write(mailbox, 1));
        assertThrows(NullPointerException.class, () -> mailbox.tryWrite(null));
        assertThrows(IllegalArgumentException.class,
                () -> ChannelRuntime.Channel.runtimePrivateMailbox(0));
        // An ordinary guest channel retains the shared select coordinator.
        ChannelRuntime.Channel<Integer> guest = new ChannelRuntime.Channel<>(2);
        assertTrue(guest.tryWrite(7));
        assertEquals(Optional.of(7), guest.tryRead());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void independentMailboxMakesProgressWhileGlobalSelectCoordinatorIsHeld()
            throws Exception {
        Field field = ChannelRuntime.class.getDeclaredField("COORDINATOR");
        field.setAccessible(true);
        ReentrantLock coordinator = (ReentrantLock) field.get(null);
        ChannelRuntime.Channel<Integer> mailbox =
                ChannelRuntime.Channel.runtimePrivateMailbox(4);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        coordinator.lock();
        try {
            Future<Boolean> progressed = pool.submit(() -> {
                if (!mailbox.tryWrite(42)) return false;
                return mailbox.tryRead().equals(Optional.of(42));
            });
            // The other thread must finish without needing our held global lock.
            assertTrue(progressed.get(3, TimeUnit.SECONDS),
                    "actor mailbox must not convoy behind unrelated select");
        } finally {
            coordinator.unlock();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void multipleProducersPreserveBoundedAdmissionAndLosslessDrain()
            throws Exception {
        int writers = 4;
        int each = 128;
        ChannelRuntime.Channel<Integer> mailbox =
                ChannelRuntime.Channel.runtimePrivateMailbox(writers * each);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch begin = new CountDownLatch(1);
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int p = 0; p < writers; p++) {
                int producer = p;
                futures.add(pool.submit(() -> {
                    try {
                        begin.await();
                        for (int i = 0; i < each; i++) {
                            if (!mailbox.tryWrite(producer * each + i))
                                throw new AssertionError("capacity lost");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }));
            }
            begin.countDown();
            for (Future<?> future : futures) future.get(4, TimeUnit.SECONDS);
        } finally {
            begin.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(writers * each, mailbox.size());
        assertFalse(mailbox.tryWrite(-1));
        Set<Integer> drained = new HashSet<>();
        while (mailbox.drainOne(value -> assertTrue(drained.add(value),
                "duplicate mailbox delivery"))) { }
        assertEquals(writers * each, drained.size());
        assertEquals(0, mailbox.size());
    }
}
