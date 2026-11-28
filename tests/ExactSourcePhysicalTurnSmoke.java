package dev.oreslang.runtime;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local, standard-library-only test of the TWO EXACT GitHub production class blobs. */
public final class ExactSourcePhysicalTurnSmoke {
    private static int assertions;
    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> type, Runnable fn) {
        assertions++;
        try { fn.run(); } catch (Throwable thrown) {
            if (type.isInstance(thrown)) return;
            throw new AssertionError("wrong failure: " + thrown, thrown);
        }
        throw new AssertionError("expected " + type.getName());
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(4, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void delayedStartAfterStop() {
        var gate = new ActorPhysicalTurnGate(1, 2);
        var region = new ReusableLongStateRegion(1);
        var delayed = new AtomicReference<Runnable>();
        var guest = new AtomicInteger();
        var rejected = new AtomicInteger();
        gate.dispatchPinned(region, guest::incrementAndGet, delayed::set,
            failure -> { check(failure instanceof RejectedExecutionException, "wrong late-start error"); rejected.incrementAndGet(); });
        check(region.activePins() == 1, "queued pin missing");
        region.beginRetire();
        check(!region.tryClose(), "closed queued region prematurely");
        delayed.get().run();
        check(region.activePins() == 0, "late rejection leaked pin");
        check(guest.get() == 0 && rejected.get() == 1, "late entry executed guest");
        check(region.tryClose(), "failed to close after queued rejection");
        gate.close();
    }
    private static void executorThrowsAfterPhysicalStart() throws Exception {
        var gate = new ActorPhysicalTurnGate(1, 2);
        var region = new ReusableLongStateRegion(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            expect(RejectedExecutionException.class, () -> gate.dispatchPinned(region,
                () -> { entered.countDown(); await(release); },
                task -> { pool.execute(task); await(entered); throw new RejectedExecutionException("after entry"); },
                failure -> { throw new AssertionError("already running turn rejected"); }));
            check(region.activePins() == 1, "submitter stole a live pin");
            region.beginRetire();
            check(!region.tryClose(), "closed while guest turn active");
            release.countDown();
            pool.submit(() -> {}).get(4, TimeUnit.SECONDS);
            check(region.activePins() == 0 && region.tryClose(), "physical completion did not release pin");
        } finally { release.countDown(); gate.close(); }
    }
    private static void queuedRejectionDoesNotReleaseRunning() throws Exception {
        var gate = new ActorPhysicalTurnGate(1, 2);
        var region = new ReusableLongStateRegion(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (var pool = Executors.newSingleThreadExecutor()) {
            gate.dispatchPinned(region, () -> { entered.countDown(); await(release); },
                pool::execute, failure -> { throw new AssertionError(failure); });
            await(entered);
            gate.dispatchPinned(region, second::incrementAndGet, pool::execute,
                failure -> rejected.incrementAndGet());
            check(region.activePins() == 2, "two physical pin receipts expected");
            region.beginRetire();
            gate.close();
            check(region.activePins() == 1, "queued revocation stole running pin");
            check(second.get() == 0 && rejected.get() == 1, "pending turn not revoked");
            check(!region.tryClose(), "close before active turn finished");
            release.countDown();
            pool.submit(() -> {}).get(4, TimeUnit.SECONDS);
            check(region.activePins() == 0 && region.tryClose(), "post-finish close denied");
        } finally { release.countDown(); gate.close(); }
    }
    private static void staleAfterEnqueueAndThrow() {
        var gate = new ActorPhysicalTurnGate(1, 2);
        var region = new ReusableLongStateRegion(1);
        var late = new AtomicReference<Runnable>();
        var guest = new AtomicInteger();
        var rejected = new AtomicInteger();
        gate.dispatchPinned(region, guest::incrementAndGet, task -> {
            late.set(task);
            throw new RejectedExecutionException("queued then threw");
        }, failure -> rejected.incrementAndGet());
        check(region.activePins() == 0, "failed admission leaked pin");
        late.get().run();
        check(guest.get() == 0 && rejected.get() == 1, "stale queued runnable executed");
        region.beginRetire();
        check(region.tryClose(), "failed admission prevents close");
        gate.close();
    }
    private static void acceptedReplayIsInert() {
        var gate = new ActorPhysicalTurnGate(1, 2);
        var region = new ReusableLongStateRegion(1);
        var accepted = new AtomicReference<Runnable>();
        var guest = new AtomicInteger();
        gate.dispatchPinned(region, guest::incrementAndGet,
            accepted::set, failure -> { throw new AssertionError(failure); });
        check(region.activePins() == 1, "accepted turn missing physical pin");
        accepted.get().run();
        check(region.activePins() == 0 && guest.get() == 1,
            "first completed turn should release exactly once");
        accepted.get().run();
        check(region.activePins() == 0 && guest.get() == 1,
            "duplicate callback reentered guest or released twice");
        check(region.tryClose(), "replayed task left pending ownership");
        gate.close();
    }
    private static void reentrantStopAbortsMutationWithoutStealingPin() {
        var region = new ReusableLongStateRegion(1);
        long scalar = region.insert(23);
        long pin = region.pin();
        expect(IllegalStateException.class, () -> region.update(
            scalar, old -> { region.beginRetire(); return old + 1; }));
        check(region.isRetiring(), "reentrant STOP was ignored");
        check(region.activePins() == 1, "STOP incorrectly released a Task");
        check(!region.tryClose(), "live physical pin was reclaimed");
        region.releasePin(pin);
        check(region.tryClose(), "final Task release failed to reclaim");
    }
    private static void competingStartAndRetire() throws Exception {
        for (int iteration = 0; iteration < 2000; iteration++) {
            var gate = new ActorPhysicalTurnGate(1, 2);
            var region = new ReusableLongStateRegion(1);
            var delayed = new AtomicReference<Runnable>();
            var guest = new AtomicInteger();
            var rejected = new AtomicInteger();
            gate.dispatchPinned(region, guest::incrementAndGet, delayed::set,
                failure -> { if (!(failure instanceof RejectedExecutionException))
                    throw new AssertionError(failure); rejected.incrementAndGet(); });
            var signal = new CountDownLatch(1);
            Thread runner = new Thread(() -> { await(signal); delayed.get().run(); });
            Thread stopper = new Thread(() -> { await(signal); region.beginRetire(); });
            runner.start(); stopper.start(); signal.countDown();
            runner.join(4000); stopper.join(4000);
            check(!runner.isAlive() && !stopper.isAlive(), "racing threads stalled");
            check(guest.get() + rejected.get() == 1, "no exactly-once outcome");
            check(region.activePins() == 0, "concurrent admission leaked lifetime pin");
            check(region.tryClose(), "retirement cannot reclaim completed region");
            gate.close();
        }
    }
    public static void main(String[] args) throws Exception {
        delayedStartAfterStop();
        executorThrowsAfterPhysicalStart();
        queuedRejectionDoesNotReleaseRunning();
        staleAfterEnqueueAndThrow();
        acceptedReplayIsInert();
        reentrantStopAbortsMutationWithoutStealingPin();
        competingStartAndRetire();
        System.out.printf("EXACT_SOURCE_JDK21_PASS assertions=%d racing_rounds=2000%n", assertions);
    }
}
