package dev.oreslang;

import dev.oreslang.runtime.LocalVirtualActorI64;
import dev.oreslang.runtime.ScalarStateContainer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutableVirtualActorStateContainerTest {
    @Test
    void checkedScalarStateUpdatesAndShutdown() {
        var container = new ScalarStateContainer(8, 8);
        assertEquals(8, container.tryRead().orElseThrow());
        assertEquals(13, container.tryAddChecked(5).orElseThrow());
        assertEquals(Long.MAX_VALUE, container.trySet(Long.MAX_VALUE).orElseThrow());
        assertThrows(ArithmeticException.class, () -> container.tryAddChecked(1));
        assertEquals(Long.MAX_VALUE, container.tryRead().orElseThrow());
        container.close();
        assertTrue(container.readyForReclamation());
        assertTrue(container.tryRead().isEmpty());
        assertTrue(container.tryAddChecked(1).isEmpty());
    }

    @Test
    void closeRacingScalarReadersAndWritersReturnsUnavailableWithoutLeakingLeases() throws Exception {
        for (int cycle = 0; cycle < 120; cycle++) {
            var container = new ScalarStateContainer(0, 3);
            var started = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                try {
                    started.await();
                    for (int i = 0; i < 200; i++) {
                        container.tryRead();
                        container.tryAddChecked(1);
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            worker.start();
            started.countDown();
            container.close();
            worker.join(5_000);
            assertFalse(worker.isAlive(), "scalar worker must not park at close");
            assertNull(failure.get(), () -> "close race raised: " + failure.get());
            assertTrue(container.readyForReclamation(), "leased state must quiesce after close");
        }
    }

    @Test
    void localVirtualActorSerializesAndReactivatesWithoutPersistentState() {
        var instances = new AtomicInteger();
        var total = new ArrayList<Long>();
        var notices = new ArrayList<LocalVirtualActorI64.Notice>();
        try (var runtime = new LocalVirtualActorI64("app", "Cart", 4, 2,
                identity -> {
                    instances.incrementAndGet();
                    final var state = new ScalarStateContainer(0, 1);
                    return value -> {
                        state.tryAddChecked(value).orElseThrow();
                        total.add(state.tryRead().orElseThrow());
                    };
                }, notices::add)) {
            var a = runtime.resolve("caf\u00e9");
            var b = runtime.resolve("cafe\u0301");
            assertEquals(a.identity(), b.identity());
            var first = runtime.try_send(a, 3);
            var second = runtime.try_send(b, 4);
            assertTrue(first.admitted());
            assertTrue(second.admitted());
            assertEquals(LocalVirtualActorI64.AdmissionKind.BACKPRESSURE,
                    runtime.try_send(a, 5).kind());
            assertThrows(IllegalStateException.class, () -> runtime.deactivate(a));
            assertTrue(runtime.run_one(a));
            assertTrue(runtime.run_one(b));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, first.completion().join());
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, second.completion().join());
            assertEquals(java.util.List.of(3L, 7L), total);
            assertEquals(1, instances.get());
            runtime.deactivate(a);
            var third = runtime.try_send(b, 6);
            assertTrue(third.admitted());
            assertTrue(runtime.run_one(b));
            assertEquals(java.util.List.of(3L, 7L, 6L), total);
            assertEquals(2, instances.get());
            assertTrue(notices.isEmpty());
        }
    }

    @Test
    void panicNotifiesSupervisorAndAccountsAllAcceptedTurns() {
        var notices = new ArrayList<LocalVirtualActorI64.Notice>();
        try (var runtime = new LocalVirtualActorI64("app", "Worker", 2, 3,
                ignored -> message -> { throw new IllegalArgumentException("bad turn"); },
                notices::add)) {
            var ref = runtime.resolve("one");
            var first = runtime.try_send(ref, 1);
            var second = runtime.try_send(ref, 2);
            assertTrue(runtime.run_one(ref));
            assertEquals(1, notices.size());
            assertEquals(LocalVirtualActorI64.NoticeKind.PANIC, notices.getFirst().kind());
            assertEquals(2, notices.getFirst().failedAcceptedCount());
            assertEquals(LocalVirtualActorI64.Outcome.FAILED_CONTROL_NOTIFIED,
                    first.completion().join());
            assertEquals(LocalVirtualActorI64.Outcome.FAILED_CONTROL_NOTIFIED,
                    second.completion().join());
            var next = runtime.try_send(ref, 3);
            assertTrue(next.admitted());
            assertTrue(runtime.run_one(ref));
            assertEquals(2, notices.size());
        }
    }

    @Test
    void failedControlNoticeRetainsMessageCreditsAndBlocksShutdown() {
        var runtime = new LocalVirtualActorI64("app", "Worker", 2, 1,
                ignored -> message -> { throw new IllegalStateException("panic"); },
                notice -> { throw new IllegalStateException("control unavailable"); });
        var ref = runtime.resolve("one");
        var receipt = runtime.try_send(ref, 1);
        assertThrows(IllegalStateException.class, () -> runtime.run_one(ref));
        assertFalse(receipt.completion().isDone());
        assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(LocalVirtualActorI64.AdmissionKind.UNAVAILABLE,
                runtime.try_send(ref, 2).kind());
    }
}
