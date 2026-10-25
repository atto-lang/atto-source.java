package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ActorMailboxChannelTest {
    @Test void mailboxProgressDoesNotWaitForUnrelatedSelectCoordinator() throws Exception {
        var field = ChannelRuntime.class.getDeclaredField("COORDINATOR");
        field.setAccessible(true);
        ReentrantLock coordinator = (ReentrantLock) field.get(null);
        var mailbox = ChannelRuntime.<Integer>mailbox(2);
        var result = new CompletableFuture<Integer>();
        coordinator.lock();
        Thread worker = null;
        try {
            worker = Thread.ofPlatform().start(() -> {
                try {
                    assertTrue(mailbox.tryWrite(7));
                    result.complete(mailbox.tryRead().orElseThrow());
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            assertEquals(7, result.get(2, TimeUnit.SECONDS));
        } finally { coordinator.unlock(); if (worker != null) worker.join(); }
        assertThrows(ChannelRuntime.SelectAdmissionException.class, () -> ChannelRuntime.read(mailbox));
        assertThrows(ChannelRuntime.SelectAdmissionException.class, () -> ChannelRuntime.write(mailbox, 8));
        mailbox.close();
    }

    @Test void concurrentProducersPreserveBoundedFifoAndCloseDrainsCommittedValues() throws Exception {
        var mailbox = ChannelRuntime.<Integer>mailbox(16);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var producers = new java.util.ArrayList<Thread>();
        for (int p = 0; p < 4; p++) {
            int producer = p;
            producers.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int n = 0; n < 2000; n++) {
                        while (!mailbox.tryWrite(producer * 2000 + n)) Thread.yield();
                    }
                } catch (Throwable error) { failure.set(error); }
            }));
        }
        int[] next = new int[4];
        for (int n = 0; n < 8000;) {
            var value = mailbox.tryRead();
            if (value.isEmpty()) { Thread.yield(); continue; }
            int p = value.get() / 2000;
            assertEquals(next[p]++, value.get() % 2000); n++;
        }
        for (Thread producer : producers) producer.join();
        assertNull(failure.get()); assertEquals(0, mailbox.size());
        assertTrue(mailbox.tryWrite(9));
        mailbox.close(new IllegalStateException("test-close"));
        assertThrows(ChannelRuntime.ChannelClosedException.class, () -> mailbox.tryWrite(10));
        assertEquals(9, mailbox.tryRead().orElseThrow());
        var closed = assertThrows(ChannelRuntime.ChannelClosedException.class, mailbox::tryRead);
        assertEquals("test-close", closed.getCause().getMessage());
    }
}
