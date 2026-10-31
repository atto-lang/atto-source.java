package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncStdioLogsTest {
    @Test
    void fifoFlushIsABarrierForAlreadyAcceptedRecords() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (AsyncStdioLogs logs = new AsyncStdioLogs(
                new PrintWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8), true))) {
            logs.logAsync("one\n");
            logs.logAsync("two\n");
            logs.flushAsync().get(2, TimeUnit.SECONDS);
            assertEquals("one\ntwo\n", bytes.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void closedWriterRejectsNewRecordsAndFlushBarriers() throws Exception {
        AsyncStdioLogs neverStarted = new AsyncStdioLogs(new PrintWriter(new java.io.StringWriter()));
        neverStarted.close();
        assertThrows(IllegalStateException.class, () -> neverStarted.logAsync("late\n"));
        assertThrows(IllegalStateException.class, neverStarted::flushAsync);

        AsyncStdioLogs drained = new AsyncStdioLogs(new PrintWriter(new java.io.StringWriter()));
        drained.logAsync("accepted\n").get(2, TimeUnit.SECONDS);
        drained.close();
        assertThrows(IllegalStateException.class, () -> drained.logAsync("late\n"));
        assertThrows(IllegalStateException.class, drained::flushAsync);
        drained.close(); // repeat success remains idempotent
    }

    @Test
    void expiredDrainDeadlineClosesFlushAdmissionBeforeSinkRecovers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Writer blocked = new Writer() {
            @Override public void write(char[] chars, int start, int length) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("test sink timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(blocked, true));
        try {
            logs.logAsync("record\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, logs::close);
            assertThrows(IllegalStateException.class, logs::flushAsync);
            assertThrows(IllegalStateException.class, () -> logs.logAsync("late\n"));
        } finally {
            release.countDown();
        }
        logs.close(); // explicit retry once the blocked sink is released
    }

    @Test
    void acceptedFlushBarrierOutlivesCloseAdmissionRace() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Writer blocked = new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("test sink timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(blocked, true));
        java.util.concurrent.CompletableFuture<Void> closer = null;
        OresFuture<Void> acceptedRecord = null;
        OresFuture<Void> acceptedBarrier = null;
        try {
            acceptedRecord = logs.logAsync("accepted\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            acceptedBarrier = logs.flushAsync();
            // Stable references for assertions after a successful drain.
            OresFuture<Void> record = acceptedRecord;
            OresFuture<Void> barrier = acceptedBarrier;
            closer = java.util.concurrent.CompletableFuture.runAsync(logs::close);

            // Do not rely on timing to guess whether close() fenced admission.
            // While the sink remains blocked, poll only until its explicit
            // closed error is observed, never before releasing the test latch.
            boolean admissionClosed = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!admissionClosed && System.nanoTime() < deadline) {
                try {
                    logs.flushAsync();
                } catch (IllegalStateException closed) {
                    admissionClosed = closed.getMessage().contains("closed");
                    if (!admissionClosed) throw closed;
                }
                if (!admissionClosed) Thread.sleep(5);
            }
            assertTrue(admissionClosed, "close must promptly fence new flush admission");
            assertFalse(record.isDone(), "sink has not completed the accepted record");
            assertFalse(barrier.isDone(), "existing barrier must wait for the record");
            assertThrows(IllegalStateException.class, () -> logs.logAsync("late\n"));
        } finally {
            release.countDown();
            if (closer != null) closer.get(8, TimeUnit.SECONDS);
        }
        // Once close wins, existing accepted Futures still settle normally.
        assertNotNull(acceptedRecord);
        assertNotNull(acceptedBarrier);
        acceptedRecord.get(2, TimeUnit.SECONDS);
        acceptedBarrier.get(2, TimeUnit.SECONDS);
        // A second close must be idempotent after physical drain completion.
        logs.close();
    }

    @Test
    void callerNeverWaitsForBlockedOutputSink() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        Writer slow = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) throws IOException {
                entered.countDown();
                try {
                    if (!unblock.await(3, TimeUnit.SECONDS)) throw new IOException("test sink timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try (AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(slow, true))) {
            logs.logAsync("first\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                var producer = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> logs.logAsync("second\n"));
                producer.get(1, TimeUnit.SECONDS);
                assertFalse(logs.flushAsync().isDone());
            } finally {
                unblock.countDown();
            }
            logs.flushAsync().get(2, TimeUnit.SECONDS);
        } finally {
            unblock.countDown();
        }
    }

    @Test
    void overloadFailsFastAndRecordsCannotBeSilentlyDropped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        Writer slow = new Writer() {
            @Override public void write(char[] c, int off, int len) throws IOException {
                entered.countDown();
                try {
                    if (!unblock.await(5, TimeUnit.SECONDS)) throw new IOException("test sink timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try (AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(slow, true))) {
            logs.logAsync("blocked\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                for (int i = 0; i < 1024; i++) logs.logAsync("queued\n");
                IllegalStateException error = assertThrows(IllegalStateException.class,
                        () -> logs.logAsync("over capacity\n"));
                assertTrue(error.getMessage().contains("queue is full"));
            } finally {
                unblock.countDown();
            }
            logs.flushAsync().get(3, TimeUnit.SECONDS);
        } finally {
            unblock.countDown();
        }
    }

    @Test
    void utf8LimitCountsEncodedBytesRatherThanUtf16Characters() throws Exception {
        try (AsyncStdioLogs logs = new AsyncStdioLogs(
                new PrintWriter(new java.io.StringWriter(), true))) {
            logs.logAsync("é".repeat(32_768)).get(2, TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,
                    () -> logs.logAsync("é".repeat(32_769)));
            assertThrows(IllegalArgumentException.class,
                    () -> logs.logAsync("😀".repeat(16_385)));
        }
    }

    @Test
    void pendingByteBudgetIncludesTheBlockedInFlightRecord() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Writer slow = new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("test sink timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        String record = "x".repeat(65_536);
        try (AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(slow, true))) {
            logs.logAsync(record);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                for (int i = 1; i < 128; i++) logs.logAsync(record);
                IllegalStateException full = assertThrows(IllegalStateException.class,
                        () -> logs.logAsync(record));
                assertTrue(full.getMessage().contains("pending byte budget"));
                assertFalse(logs.flushAsync().isDone());
            } finally {
                release.countDown();
            }
            logs.flushAsync().get(5, TimeUnit.SECONDS);
            // The quota is released on completed output, not on dequeue.
            logs.logAsync(record).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void sinkFailuresPropagateIntoAwaitAndGracefulShutdown() throws Exception {
        Writer broken = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("sink-broken");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(broken, true));
        OresFuture<Void> future = logs.logAsync("not-delivered\n");
        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, logs::close);
    }

    @Test
    void terminalFailureInOneContextCannotPoisonAnotherContextWriter() throws Exception {
        Writer failed = new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                throw new IOException("isolated sink failure");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        ByteArrayOutputStream healthyBytes = new ByteArrayOutputStream();
        AsyncStdioLogs bad = new AsyncStdioLogs(new PrintWriter(failed, true));
        try (AsyncStdioLogs healthy = new AsyncStdioLogs(new PrintWriter(
                new OutputStreamWriter(healthyBytes, StandardCharsets.UTF_8), true))) {
            OresFuture<Void> failedRecord = bad.logAsync("bad\n");
            assertThrows(ExecutionException.class, () -> failedRecord.get(2, TimeUnit.SECONDS));
            healthy.logAsync("healthy\n").get(2, TimeUnit.SECONDS);
            assertEquals("healthy\n", healthyBytes.toString(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class, bad::close);
            healthy.logAsync("still-healthy\n").get(2, TimeUnit.SECONDS);
            assertEquals("healthy\nstill-healthy\n",
                    healthyBytes.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void blockedSinkCannotAccumulateUnboundedFlushBarriers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Writer blocked = new Writer() {
            @Override public void write(char[] chars, int off, int length) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("test sink timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try (AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(blocked, true))) {
            logs.logAsync("blocked\\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            java.util.List<OresFuture<Void>> accepted = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 1024; i++) accepted.add(logs.flushAsync());
                IllegalStateException overflow = assertThrows(IllegalStateException.class,
                        logs::flushAsync);
                assertTrue(overflow.getMessage().contains("barriers exhausted"));
                assertFalse(accepted.get(0).isDone());
                assertFalse(accepted.get(accepted.size() - 1).isDone());
            } finally {
                release.countDown();
            }
            accepted.get(0).get(3, TimeUnit.SECONDS);
            accepted.get(accepted.size() - 1).get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void timedOutDrainMustNotReenterBlockedWriterForFinalFlush() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Writer blocked = new Writer() {
            @Override public void write(char[] chars, int off, int length) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("test sink timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        PrintWriter output = new PrintWriter(blocked, true);
        AsyncStdioLogs logs = new AsyncStdioLogs(output);
        try {
            logs.logAsync("blocked\\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            IllegalStateException timeout = assertThrows(IllegalStateException.class, logs::close);
            assertTrue(timeout.getMessage().contains("shutdown deadline"));
            // The worker still owns the writer lock here; this must return
            // without attempting PrintWriter.flush().
            var skipped = java.util.concurrent.CompletableFuture.runAsync(
                    () -> OresContext.flushOutputAfterAsyncDrain(output, false));
            skipped.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        // A recovered sink must let the quarantined caller retry the drain,
        // rather than leaving the worker join permanently marked timed out.
        logs.close();

        java.util.concurrent.atomic.AtomicBoolean flushed = new java.util.concurrent.atomic.AtomicBoolean();
        PrintWriter healthy = new PrintWriter(new java.io.StringWriter()) {
            @Override public void flush() {
                flushed.set(true);
                super.flush();
            }
        };
        OresContext.flushOutputAfterAsyncDrain(healthy, true);
        assertTrue(flushed.get(), "successful async close must preserve final stdout flush");
    }

}
