package dev.oreslang.runtime;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the same commit-phase implementation used by NativeHttp.State. */
public final class HttpResponseWirePhaseCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] ignored) throws Exception {
        HttpResponseWirePhase unreserved = new HttpResponseWirePhase();
        check(!unreserved.begin(false, false), "cannot start without reservation");
        check(!unreserved.begin(true, true), "cannot start after abort");
        check(unreserved.maySendError(false, false), "unstarted response can send an error");
        check(!unreserved.maySendError(false, true), "busy request cannot send concurrent error");
        try {
            unreserved.markSent();
            throw new AssertionError("headers cannot be confirmed without beginning");
        } catch (IllegalStateException expected) { }

        HttpResponseWirePhase unsuccessfulSend = new HttpResponseWirePhase();
        check(unsuccessfulSend.begin(true, false), "first attempt must start");
        check(!unsuccessfulSend.begin(true, false), "no second header attempt");
        check(!unsuccessfulSend.maySendError(false, false), "failed attempt cannot send second status");
        check(!unsuccessfulSend.sent(), "begin is not proof of successful send");
        unsuccessfulSend.markSent();
        check(unsuccessfulSend.sent(), "successful send is recorded");
        try {
            unsuccessfulSend.markSent();
            throw new AssertionError("headers sent twice");
        } catch (IllegalStateException expected) { }

        HttpResponseWirePhase.requireLiveFinish(false);
        try {
            HttpResponseWirePhase.requireLiveFinish(true);
            throw new AssertionError("aborted request was completed successfully");
        } catch (IOException expected) {
            check(expected.getMessage().contains("aborted"), "abort reason preserved");
        }

        // The owner monitor is deliberately the SAME lock for cancellation
        // and beginning headers. Exactly one of these actions can win.
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int iteration = 0; iteration < 1000; iteration++) {
                Object monitor = new Object();
                HttpResponseWirePhase phase = new HttpResponseWirePhase();
                AtomicBoolean terminal = new AtomicBoolean();
                AtomicBoolean started = new AtomicBoolean();
                AtomicBoolean sendError = new AtomicBoolean();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> emit = workers.submit(() -> {
                    ready.countDown();
                    if (!go.await(3, TimeUnit.SECONDS)) throw new AssertionError("emit timeout");
                    synchronized (monitor) {
                        started.set(phase.begin(true, terminal.get()));
                    }
                    if (started.get()) {
                        synchronized (monitor) { phase.markSent(); }
                    }
                    return null;
                });
                Future<?> abort = workers.submit(() -> {
                    ready.countDown();
                    if (!go.await(3, TimeUnit.SECONDS)) throw new AssertionError("abort timeout");
                    synchronized (monitor) {
                        sendError.set(phase.maySendError(terminal.get(), false));
                        terminal.set(true);
                    }
                    return null;
                });
                check(ready.await(3, TimeUnit.SECONDS), "both tasks started");
                go.countDown();
                emit.get(3, TimeUnit.SECONDS);
                abort.get(3, TimeUnit.SECONDS);
                check(!started.get() || !sendError.get(),
                        "wire attempt and alternate HTTP error both authorized");
                check(phase.started() == started.get(), "phase records start");
                check(phase.sent() == started.get(), "phase records confirmed send");
                check(terminal.get(), "abort always terminates request");
                try {
                    HttpResponseWirePhase.requireLiveFinish(terminal.get());
                    throw new AssertionError("aborted completion escaped under race");
                } catch (IOException expected) { }
            }
        }
        System.out.println("PASS: 1000 synchronized abort/header races, single wire attempt and aborted-finish guard");
    }
}
