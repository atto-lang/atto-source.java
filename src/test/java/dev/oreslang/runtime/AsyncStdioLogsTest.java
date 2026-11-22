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
    void lateFlushCannotOvertakeEarlierBarrierWhileRecordCompletionIsNotified() throws Exception {
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Writer sink = new Writer() {
            @Override public void write(char[] chars, int off, int len) throws IOException {
                sinkEntered.countDown();
                try {
                    if (!releaseSink.await(10, TimeUnit.SECONDS)) throw new IOException("sink timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try (AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(sink))) {
            OresFuture<Void> record = logs.logAsync("record\n");
            assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));
            record.whenCompleteRuntime((value, failure) -> {
                callbackEntered.countDown();
                try {
                    if (!releaseCallback.await(10, TimeUnit.SECONDS))
                        throw new AssertionError("callback timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            java.util.List<Integer> observed =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            CountDownLatch callbacksDelivered = new CountDownLatch(2);
            OresFuture<Void> first = logs.flushAsync();
            first.whenCompleteRuntime((value, failure) -> {
                observed.add(1);
                callbacksDelivered.countDown();
            });
            try {
                releaseSink.countDown();
                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
                OresFuture<Void> second = logs.flushAsync();
                second.whenCompleteRuntime((value, failure) -> {
                    observed.add(2);
                    callbacksDelivered.countDown();
                });
                assertFalse(first.isDone());
                assertFalse(second.isDone(), "late flush must wait for earlier callbacks");
                releaseCallback.countDown();
                first.get(3, TimeUnit.SECONDS);
                second.get(3, TimeUnit.SECONDS);
                assertTrue(callbacksDelivered.await(3, TimeUnit.SECONDS),
                        "both FIFO observer callbacks must run");
                assertEquals(java.util.List.of(1, 2), observed);
            } finally {
                releaseSink.countDown();
                releaseCallback.countDown();
            }
        } finally {
            releaseSink.countDown();
            releaseCallback.countDown();
        }
    }

    @Test
    void flushBarrierSubscribersReceiveFifoNotifications() throws Exception {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        Writer slow = new Writer() {
            @Override public void write(char[] chars, int off, int len) throws IOException {
                writerEntered.countDown();
                try {
                    if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("test sink blocked too long");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try (AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(slow, true))) {
            logs.logAsync("one pending record\n");
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS));
            var notifications = java.util.Collections.synchronizedList(
                    new java.util.ArrayList<Integer>());
            var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            try {
                for (int index = 0; index < 64; index++) {
                    final int registered = index;
                    logs.flushAsync().whenCompleteRuntime((unused, failure) -> {
                        if (failure != null) errors.compareAndSet(null, failure);
                        notifications.add(registered);
                    });
                }
            } finally {
                releaseWriter.countDown();
            }
            // The worker joins before assertions, so registration order is
            // observable without relying on sleep, polling or callback races.
            logs.close();
            assertNull(errors.get());
            assertEquals(java.util.stream.IntStream.range(0, 64).boxed().toList(),
                    notifications, "later flushes cannot notify before earlier flushes");
        } finally {
            releaseWriter.countDown();
        }
    }

    @Test
    void shutdownProducersMayEmitBeforeAdmissionSeals() throws Exception {
        // Models the admission window during physical actor retirement.
        // This is NOT an actual actor-generation/native-I/O retirement proof.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AsyncStdioLogs logs = new AsyncStdioLogs(
                new PrintWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8), true));
        CountDownLatch retiring = new CountDownLatch(1);
        CountDownLatch allowFinalLog = new CountDownLatch(1);
        var producer = java.util.concurrent.CompletableFuture.runAsync(() -> {
            retiring.countDown();
            try {
                if (!allowFinalLog.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("producer was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            logs.logAsync("actor-retirement\n");
        });
        try {
            logs.logAsync("normal-turn\n");
            assertTrue(retiring.await(2, TimeUnit.SECONDS));
            allowFinalLog.countDown();
            producer.get(2, TimeUnit.SECONDS);
            logs.sealAdmission();
            assertThrows(IllegalStateException.class, () -> logs.logAsync("too-late\n"));
            logs.close();
            assertEquals("normal-turn\nactor-retirement\n",
                    bytes.toString(StandardCharsets.UTF_8));
        } finally {
            allowFinalLog.countDown();
        }
    }

    @Test
    void sealingAdmissionNeverWaitsOnStuckStdoutWriter() throws Exception {
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Writer blocked = new Writer() {
            @Override public void write(char[] chars, int off, int len) throws IOException {
                enteredWrite.countDown();
                try {
                    if (!releaseSink.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("sink deadline");
                    }
                    bytes.write(new String(chars, off, len).getBytes(StandardCharsets.UTF_8));
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
            OresFuture<Void> accepted = logs.logAsync("accepted-before-seal\n");
            assertTrue(enteredWrite.await(2, TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture.runAsync(logs::sealAdmission)
                    .get(1, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> logs.logAsync("rejected-after-seal\n"));
            assertFalse(accepted.cancel(false), "accepted output cannot be retracted");
            releaseSink.countDown();
            logs.close();
            assertEquals("accepted-before-seal\n", bytes.toString(StandardCharsets.UTF_8));
        } finally {
            releaseSink.countDown();
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
        AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(blocked, true));
        try {
            logs.logAsync("blocked\n");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            // A worker owns the sink lock. close() must report incomplete
            // shutdown within its deadline without entering that monitor.
            IllegalStateException timeout = assertThrows(IllegalStateException.class, logs::close);
            assertTrue(timeout.getMessage().contains("shutdown deadline"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void synchronousOnlyOutputStillGetsFinalFlushFromDrainWorker() {
        java.io.StringWriter backing = new java.io.StringWriter();
        java.util.concurrent.atomic.AtomicReference<String> flushed = new java.util.concurrent.atomic.AtomicReference<>();
        Writer buffered = new Writer() {
            @Override public void write(char[] chars, int off, int len) {
                backing.write(chars, off, len);
            }
            @Override public void flush() {
                flushed.set(backing.toString());
            }
            @Override public void close() { }
        };
        PrintWriter output = new PrintWriter(buffered, false);
        AsyncStdioLogs logs = new AsyncStdioLogs(output);
        // No async records were ever submitted; the old implementation
        // skipped the worker and OresContext.close() synchronously flushed.
        output.print("sync-only");
        assertNull(flushed.get());
        logs.close();
        assertEquals("sync-only", flushed.get());
    }

    @Test
    void blockedSynchronousOnlyFinalFlushIsBounded() {
        CountDownLatch enteredFlush = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Writer blocked = new Writer() {
            @Override public void write(char[] chars, int off, int len) { }
            @Override public void flush() throws IOException {
                enteredFlush.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IOException("test sink flush timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            @Override public void close() { }
        };
        AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(blocked, false));
        try {
            IllegalStateException deadline = assertThrows(IllegalStateException.class, logs::close);
            assertTrue(enteredFlush.getCount() == 0L, "final flush must reach the worker");
            assertTrue(deadline.getMessage().contains("shutdown deadline"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void sealedWriterRejectsNewFlushBarrierEvenWhenNoRecordsArePending() throws Exception {
        AsyncStdioLogs logs = new AsyncStdioLogs(new PrintWriter(new java.io.StringWriter()));
        logs.sealAdmission();
        OresFuture<Void> late = logs.flushAsync();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> late.get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("closed"));
        assertThrows(IllegalStateException.class, () -> logs.logAsync("late\n"));
        logs.close();
    }

}
