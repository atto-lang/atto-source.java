package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Cancellation cleanup and admission release must survive executor shutdown. */
public final class HttpResponseCleanupDispatcherCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger cleaned = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        AtomicBoolean fallbackVirtual = new AtomicBoolean();
        CountDownLatch fallbackDone = new CountDownLatch(1);
        HttpResponseCleanupDispatcher.dispatch(task -> {
            throw new RejectedExecutionException("I/O executor already closed");
        }, () -> {
            try {
                fallbackVirtual.set(Thread.currentThread().isVirtual());
                cleaned.incrementAndGet();
            } finally {
                released.incrementAndGet();
                fallbackDone.countDown();
            }
        });
        check(fallbackDone.await(3, TimeUnit.SECONDS), "rejected cleanup never ran");
        check(fallbackVirtual.get(), "fallback must avoid blocking the supervisor carrier");
        check(cleaned.get() == 1 && released.get() == 1, "rejected cleanup or admission released incorrectly");

        // Defensive once-only guard for an executor which incorrectly invokes
        // a task synchronously and then reports rejection.
        CountDownLatch strangeDone = new CountDownLatch(1);
        AtomicInteger strangeInvocations = new AtomicInteger();
        HttpResponseCleanupDispatcher.dispatch(task -> {
            task.run();
            throw new RejectedExecutionException("ran, then rejected");
        }, () -> {
            strangeInvocations.incrementAndGet();
            strangeDone.countDown();
        });
        check(strangeDone.await(3, TimeUnit.SECONDS), "synchronous execution disappeared");
        Thread.sleep(30);
        check(strangeInvocations.get() == 1, "fallback executed cleanup twice");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch normalDone = new CountDownLatch(1);
            HttpResponseCleanupDispatcher.dispatch(executor, normalDone::countDown);
            check(normalDone.await(3, TimeUnit.SECONDS), "normal cleanup never ran");
        }
        int count = 300;
        CountDownLatch all = new CountDownLatch(count);
        AtomicInteger completed = new AtomicInteger();
        for (int i = 0; i < count; i++) {
            HttpResponseCleanupDispatcher.dispatch(task -> {
                throw new RejectedExecutionException("shutdown");
            }, () -> {
                completed.incrementAndGet();
                all.countDown();
            });
        }
        check(all.await(5, TimeUnit.SECONDS), "shutdown cleanup was lost");
        check(completed.get() == count, "shutdown cleanup executed more or fewer than once");
        System.out.println("PASS: 300 rejected cleanup tasks, async virtual fallback and exactly-once release");
    }
}
