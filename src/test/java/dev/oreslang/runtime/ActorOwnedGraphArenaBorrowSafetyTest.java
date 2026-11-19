package dev.oreslang.runtime;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Host-owned graph borrow and deterministic drop, not compiled .atto guest GC proof. */
final class ActorOwnedGraphArenaBorrowSafetyTest {
    @Test
    void borrowUsesOneHandleWithoutPerLoanAtomicBooleanAndPreservesOwnerCheck() {
        boolean releasedIsVolatile = false;
        for (var field : ActorOwnedGraphArena.Lease.class.getDeclaredFields()) {
            assertNotEquals(java.util.concurrent.atomic.AtomicBoolean.class, field.getType(),
                    "no managed AtomicBoolean allocation per graph borrow");
            if (field.getName().equals("released")) {
                assertEquals(boolean.class, field.getType());
                releasedIsVolatile = Modifier.isVolatile(field.getModifiers());
            }
        }
        assertTrue(releasedIsVolatile);
        AtomicInteger ownerChecks = new AtomicInteger();
        AtomicInteger destructors = new AtomicInteger();
        var graph = new ActorOwnedGraphArena<AutoCloseable>(
                1, 1, ownerChecks::incrementAndGet, g -> {});
        var id = graph.add(() -> destructors::incrementAndGet);
        var lease = graph.borrow(id);
        assertNotNull(lease.get());
        assertEquals(1, graph.activeLoans());
        lease.close();
        lease.close();
        assertEquals(0, graph.activeLoans());
        graph.close();
        assertTrue(graph.fullyClosed());
        assertEquals(1, destructors.get());
        assertTrue(ownerChecks.get() >= 3);
        assertThrows(IllegalStateException.class, () -> graph.borrow(id));
    }

    @Test
    void foreignCarrierDoubleCloseAndStopCannotDoubleDropGraphPayload() throws Exception {
        for (int round = 0; round < 64; round++) {
            AtomicInteger destructors = new AtomicInteger();
            AtomicInteger backingReleases = new AtomicInteger();
            var graph = new ActorOwnedGraphArena<AutoCloseable>(
                    1, 1, () -> {}, g -> backingReleases.incrementAndGet());
            var id = graph.add(() -> destructors::incrementAndGet);
            var loan = graph.borrow(id);
            var ready = new CountDownLatch(3);
            var begin = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Runnable close = () -> {
                ready.countDown();
                try {
                    if (!begin.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("close barrier expired");
                    loan.close();
                    loan.close();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            };
            Runnable stop = () -> {
                ready.countDown();
                try {
                    if (!begin.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("stop barrier expired");
                    graph.retireFromActor();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            };
            Thread a = Thread.ofPlatform().start(close);
            Thread b = Thread.ofPlatform().start(close);
            Thread c = Thread.ofPlatform().start(stop);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            begin.countDown();
            for (Thread t : List.of(a, b, c)) t.join(5000);
            assertFalse(a.isAlive());
            assertFalse(b.isAlive());
            assertFalse(c.isAlive());
            assertNull(unexpected.get());
            assertTrue(graph.fullyClosed(), "final loan physically retires graph");
            assertEquals(0, graph.activeLoans());
            assertEquals(1, destructors.get(), "payload destroyed once");
            assertEquals(1, backingReleases.get(), "backing released once");
            assertThrows(IllegalStateException.class, loan::get);
        }
    }

    @Test
    void retirementRunsAllDestructorsEvenAfterFailureAndLoanRelease() {
        List<String> order = new ArrayList<>();
        var graph = new ActorOwnedGraphArena<AutoCloseable>(3, 1, () -> {},
                g -> order.add("backing"));
        var first = graph.add(() -> () -> order.add("first"));
        graph.add(() -> () -> {
            order.add("second");
            throw new IllegalStateException("destructor failed");
        });
        graph.add(() -> () -> order.add("third"));
        var loan = graph.borrow(first);
        graph.retireFromActor();
        assertFalse(graph.fullyClosed());
        assertTrue(order.isEmpty());
        assertThrows(IllegalStateException.class, loan::get);
        loan.close();
        assertEquals(List.of("third", "second", "first", "backing"), order);
        assertEquals("destructor failed", graph.cleanupFailure().orElseThrow().getMessage());
        assertTrue(graph.fullyClosed());
    }

    @Test
    void hostAllocationCounterReportsUnavailableRatherThanInventingZero() {
        var graph = new ActorOwnedGraphArena<AutoCloseable>(
                1, 1, () -> {}, g -> {});
        var id = graph.add(() -> () -> {});
        for (int i = 0; i < 10_000; i++) graph.borrow(id).close();
        Long before = allocatedBytesOrNull();
        int measured = 20_000;
        for (int i = 0; i < measured; i++) graph.borrow(id).close();
        Long after = allocatedBytesOrNull();
        String bytes = before != null && after != null && after >= before
                ? Long.toString(after - before) : "UNAVAILABLE";
        System.out.println("ATTO_ACTOR_GRAPH_HOST_BORROW_ALLOC"
                + " borrowed_handles=" + measured
                + " allocated_bytes=" + bytes
                + " guest_compiled=false"
                + " native_image=false"
                + " zero_gc_claim=false");
        assertEquals(0, graph.activeLoans());
        graph.close();
        assertTrue(graph.fullyClosed());
    }

    private static Long allocatedBytesOrNull() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean counter
                    && counter.isThreadAllocatedMemorySupported()
                    && counter.isThreadAllocatedMemoryEnabled()) {
                long value = counter.getThreadAllocatedBytes(Thread.currentThread().threadId());
                if (value >= 0L) return value;
            }
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            // Native Image or a sandbox may not implement these JDK counters.
        }
        return null;
    }
}
