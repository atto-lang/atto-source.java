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
