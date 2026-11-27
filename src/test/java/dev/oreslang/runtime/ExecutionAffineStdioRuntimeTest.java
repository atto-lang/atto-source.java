package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ExecutionAffineStdioRuntimeTest {
    private static void eventually(java.util.function.BooleanSupplier ready) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(ready.getAsBoolean());
    }
    @Test void oneCarrierProgressAndOwnedCancellationWhileSinkBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        StringBuilder out = new StringBuilder();
        Writer sink = new Writer() {
            @Override public void write(char[] c, int offset, int count) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("gate timeout");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new IOException(error);
                }
                out.append(c, offset, count);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try (AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(sink));
             OresScheduler scheduler = new OresScheduler(1)) {
            try {
                String frame = "x".repeat(65_536);
                for (int i = 0; i < 64; i++) logs.admitBuffered(frame).get(2, TimeUnit.SECONDS);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                AtomicInteger turns = new AtomicInteger();
                AtomicReference<ExecutionContext> owner = new AtomicReference<>();
                OresFuture<Integer> kept = scheduler.start(resume -> {
                    if (turns.getAndIncrement() == 0) {
                        owner.set(OresScheduler.currentExecutionContext());
                        return OresScheduler.stdioAdmission(logs, "KEEP");
                    }
                    assertSame(owner.get(), OresScheduler.currentExecutionContext());
                    assertNull(resume.failure());
                    return OresScheduler.done(42);
                });
                eventually(() -> logs.pendingAdmissionCount() == 1);
                TaskHandle<Integer> dropped = scheduler.startTracked(resume ->
                        OresScheduler.stdioAdmission(logs, "DROP"));
                eventually(() -> logs.pendingAdmissionCount() == 2);
                assertEquals(7, scheduler.startSync(() -> 7).get(2, TimeUnit.SECONDS));
                assertTrue(dropped.cancel("retire pending output"));
                dropped.getDone().get(5, TimeUnit.SECONDS);
                eventually(() -> logs.pendingAdmissionCount() == 1);
                assertEquals(1, turns.get());
                release.countDown();
                assertEquals(42, kept.get(5, TimeUnit.SECONDS));
                logs.flushAsync().get(5, TimeUnit.SECONDS);
                assertEquals(2, turns.get());
                assertEquals(64L * 65_536 + 4, out.length());
                assertTrue(out.toString().endsWith("KEEP"));
            } finally { release.countDown(); }
        }
    }
}
