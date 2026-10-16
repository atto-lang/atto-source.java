package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic start-gated ownership races. A physical buffer must never
 * be resurrected after close, moved twice, or closed beneath an active loan.
 */
final class DopHostBufferOwnerRaceTest {
    private static DataOrientedLayout.Plan shape() {
        return new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOSOA, 7, 3, 4, 16, 4);
    }

    @Test
    void concurrentTakeAndCloseNeverResurrectStorage() throws Exception {
        for (int trial = 0; trial < 128; trial++) {
            var owner = new DopKernelIR.HostBuffer(shape());
            var token = owner.regionToken();
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var moved = new AtomicReference<DopKernelIR.HostBuffer>();
            var error = new AtomicReference<Throwable>();
            var transfer = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("transfer timed out");
                    }
                    moved.set(owner.take());
                } catch (IllegalStateException expected) {
                    // close won
                } catch (Throwable unexpected) {
                    error.compareAndSet(null, unexpected);
                }
            });
            var closer = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("close timed out");
                    }
                    owner.close();
                } catch (Throwable unexpected) {
                    error.compareAndSet(null, unexpected);
                }
            });
            transfer.start();
            closer.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            transfer.join(5000);
            closer.join(5000);
            assertFalse(transfer.isAlive(), "transfer thread stuck");
            assertFalse(closer.isAlive(), "close thread stuck");
            assertNull(error.get(), "unexpected ownership race failure");
            if (moved.get() == null) {
                assertFalse(token.isLive(), "closed owner must retire storage");
            } else {
                assertSame(token, moved.get().regionToken());
                assertTrue(token.isLive(), "moved-from close retired successor");
                moved.get().write(0, 0, 123f);
                assertEquals(123f, moved.get().read(0, 0));
                moved.get().close();
                assertFalse(token.isLive(), "successor close must retire allocation");
            }
            assertThrows(IllegalStateException.class, owner::regionToken);
        }
    }

    @Test
    void concurrentWriteLoanAndCloseNeverExposeRetiredStorage() throws Exception {
        for (int trial = 0; trial < 128; trial++) {
            var owner = new DopKernelIR.HostBuffer(shape());
            var token = owner.regionToken();
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var admitted = new AtomicReference<DopKernelIR.WriteLoan>();
            var error = new AtomicReference<Throwable>();
            var borrower = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("borrow timed out");
                    }
                    admitted.set(owner.borrowWrite());
                } catch (IllegalStateException expected) {
                    // close won
                } catch (Throwable unexpected) {
                    error.compareAndSet(null, unexpected);
                }
            });
            var closer = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("close timed out");
                    }
                    owner.close();
                } catch (IllegalStateException expected) {
                    // live exclusive write loan won
                } catch (Throwable unexpected) {
                    error.compareAndSet(null, unexpected);
                }
            });
            borrower.start();
            closer.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            borrower.join(5000);
            closer.join(5000);
            assertFalse(borrower.isAlive(), "borrow thread stuck");
            assertFalse(closer.isAlive(), "close thread stuck");
            assertNull(error.get(), "unexpected borrow/close failure");
            var writer = admitted.get();
            if (writer != null) {
                assertTrue(token.isLive(), "closed beneath active writer");
                writer.write(1, 2, 31f);
                assertThrows(IllegalStateException.class, owner::close);
                writer.close();
                assertEquals(31f, owner.read(1, 2));
                owner.close();
            }
            assertFalse(token.isLive());
            assertThrows(IllegalStateException.class, owner::borrowWrite);
        }
    }
}
