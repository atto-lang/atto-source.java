package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class SharedReactorPoolCheck {
    private static final ThreadLocal<Boolean> IN_POLL = ThreadLocal.withInitial(() -> false);
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void until(BooleanSupplier predicate) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!predicate.getAsBoolean()) {
            check(System.nanoTime() < deadline); Thread.yield();
        }
    }
    static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
    static final class Io implements SharedReactorPool.Reactor, AutoCloseable {
        final Selector selector;
        final Pipe pipe;
        final AtomicInteger active = new AtomicInteger(), delivered = new AtomicInteger(), wakes = new AtomicInteger();
        final Set<Thread> polls = ConcurrentHashMap.newKeySet(), executions = ConcurrentHashMap.newKeySet();
        volatile SharedReactorPool pool;
        int pending;
        Io() throws Exception {
            selector = Selector.open(); pipe = Pipe.open();
            pipe.source().configureBlocking(false);
            pipe.source().register(selector, SelectionKey.OP_READ);
        }
        @Override public void wake() { wakes.incrementAndGet(); selector.wakeup(); }
        void emit() throws Exception { check(pipe.sink().write(ByteBuffer.wrap(new byte[]{1})) == 1); }
        @Override public void poll(boolean mayWait) {
            check(active.incrementAndGet() == 1); IN_POLL.set(true);
            polls.add(Thread.currentThread());
            try {
                if (mayWait && pending == 0) selector.select(10); else selector.selectNow();
                if (!selector.selectedKeys().isEmpty()) {
                    selector.selectedKeys().clear();
                    int read = pipe.source().read(ByteBuffer.allocate(128));
                    check(read >= 0); pending += read;
                }
                while (pending != 0 && pool != null) {
                    if (!pool.try_submit(() -> {
                        check(!IN_POLL.get()); executions.add(Thread.currentThread()); delivered.incrementAndGet();
                    })) break;
                    --pending;
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally { IN_POLL.set(false); check(active.decrementAndGet() == 0); }
        }
        @Override public void close() throws java.io.IOException {
            pipe.source().close(); pipe.sink().close(); selector.close();
        }
    }
    @SuppressWarnings("try") // Explicit closes test idempotence and self-join rejection.
    public static void main(String[] args) throws Exception {
        try (Io io = new Io(); SharedReactorPool pool = new SharedReactorPool(io, 2, 4)) {
            io.pool = pool; check(pool.started_workers() == 2);
            for (int i = 0; i < 100; ++i) io.emit();
            until(() -> io.delivered.get() == 100); check(pool.started_workers() == 2);
            pool.execute(() -> { throw new IllegalStateException("expected"); });
            AtomicInteger selfDenied = new AtomicInteger();
            pool.execute(() -> {
                try { pool.close(); } catch (IllegalStateException expected) { selfDenied.incrementAndGet(); }
            });
            until(() -> selfDenied.get() == 1 && pool.failures() == 1);
            pool.ensure_workers(4); check(pool.started_workers() == 4);
            boolean denied = false;
            try { pool.ensure_workers(5); } catch (IllegalArgumentException expected) { denied = true; }
            check(denied);
            AtomicInteger done = new AtomicInteger();
            Thread[] producers = new Thread[4];
            for (int i = 0; i < producers.length; ++i) {
                producers[i] = new Thread(() -> {
                    for (int j = 0; j < 2000; ++j)
                        while (!pool.try_submit(done::incrementAndGet)) Thread.yield();
                });
                producers[i].start();
            }
            for (Thread producer : producers) producer.join();
            until(() -> done.get() == 8000); pool.close();
            check(pool.completed() == 8102 && !pool.try_submit(done::incrementAndGet));
            io.polls.addAll(io.executions); check(io.polls.size() <= 4);
        }
        try (Io io = new Io(); SharedReactorPool pool = new SharedReactorPool(io, 1, 1)) {
            io.pool = pool;
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            pool.execute(() -> { entered.countDown(); await(release); }); await(entered);
            AtomicInteger done = new AtomicInteger();
            int wakesBeforeHotQueue = io.wakes.get();
            for (int i = 0; i < 1024; ++i) check(pool.try_submit(done::incrementAndGet));
            check(io.wakes.get() == wakesBeforeHotQueue); // no poller while execution blocks
            check(!pool.try_submit(done::incrementAndGet));
            release.countDown(); pool.close(); check(done.get() == 1024);
        }
        try (Io io = new Io(); SharedReactorPool pool = new SharedReactorPool(io, 1, 1)) {
            io.pool = pool;
            AtomicInteger slices = new AtomicInteger();
            Runnable chain = new Runnable() {
                @Override public void run() {
                    if (slices.incrementAndGet() == 1) {
                        try { io.emit(); } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                    if (io.delivered.get() == 0) check(pool.try_submit(this));
                }
            };
            pool.execute(chain); until(() -> io.delivered.get() == 1);
            pool.close(); check(slices.get() >= 64);
            check(io.polls.size() == 1 && io.executions.equals(io.polls));
        }
        // Fault-injected reactor must seal admission, drain accepted work,
        // and never poll the failed reactor again while draining.
        AtomicInteger failPoll = new AtomicInteger();
        SharedReactorPool.Reactor failingPoll = new SharedReactorPool.Reactor() {
            @Override public void poll(boolean mayWait) {
                if (failPoll.get() != 0) throw new IllegalStateException("poll failure");
                if (mayWait) {
                    try { Thread.sleep(1); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                }
            }
            @Override public void wake() { }
        };
        try (SharedReactorPool pool = new SharedReactorPool(failingPoll, 1, 1)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            pool.execute(() -> { entered.countDown(); await(release); });
            await(entered);
            AtomicInteger done = new AtomicInteger();
            for (int i = 0; i < 128; ++i) check(pool.try_submit(done::incrementAndGet));
            failPoll.set(1); release.countDown();
            until(() -> pool.failures() == 1);
            pool.close();
            check(pool.failures() == 1 && done.get() == 128 && pool.completed() == 129);
            check(!pool.try_submit(done::incrementAndGet));
        }
        SharedReactorPool.Reactor brokenWake = new SharedReactorPool.Reactor() {
            @Override public void poll(boolean mayWait) {
                if (mayWait) try { Thread.sleep(1); } catch (InterruptedException ignored) { }
            }
            @Override public void wake() { throw new IllegalStateException("broken wake"); }
        };
        try (SharedReactorPool pool = new SharedReactorPool(brokenWake, 1, 1)) {
            AtomicInteger done = new AtomicInteger();
            check(pool.try_submit(done::incrementAndGet)); // accepted work must not look rejected
            until(() -> done.get() == 1); pool.close(); check(pool.failures() >= 1);
        }
        try (Io io = new Io()) {
            for (int[] limits : new int[][]{{0, 2}, {-1, 2}, {3, 2}, {1, 257}}) {
                boolean denied = false;
                try { new SharedReactorPool(io, limits[0], limits[1]).close(); }
                catch (IllegalArgumentException expected) { denied = true; }
                check(denied);
            }
        }
        // Concurrent close must linearize with admission: all accepted work
        // drains exactly once, and post-close submissions retain ownership.
        try (Io io = new Io(); SharedReactorPool pool = new SharedReactorPool(io, 2, 2)) {
            io.pool = pool;
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger finished = new AtomicInteger();
            AtomicInteger entered = new AtomicInteger();
            Thread[] racing = new Thread[4];
            for (int p = 0; p < racing.length; ++p) {
                racing[p] = new Thread(() -> {
                    entered.incrementAndGet();
                    for (int j = 0; j < 1000; ++j) {
                        if (pool.try_submit(finished::incrementAndGet)) accepted.incrementAndGet();
                        else Thread.yield();
                    }
                });
                racing[p].start();
            }
            until(() -> entered.get() == 4 && accepted.get() > 0);
            pool.close();
            for (Thread t : racing) t.join();
            check(accepted.get() == finished.get());
            check(!pool.try_submit(finished::incrementAndGet));
            check(io.active.get() == 0);
        }
        System.out.println("shared-reactor host: default-two, single-poller, real Pipe readiness, shared execution, "
                + "bounded queue, 8000 concurrent submissions, growth cap, self-join denial, drain PASS");
    }
}
