package dev.oreslang.runtime;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Source-only isolated JDK21 checks, not a full actor runtime test. */
public final class OtpSupervisorCommandLaneV1Smoke {
    private static final String DIGEST = "a".repeat(64);
    private static OtpSupervisorCommandLaneV1.Target target(int generation) {
        return new OtpSupervisorCommandLaneV1.Target(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), generation);
    }
    private static void check(boolean ok, String detail) {
        if (!ok) throw new AssertionError(detail);
    }

    private static void lifecycleAndCapacity() {
        var lane = new OtpSupervisorCommandLaneV1(1);
        var command = lane.submit(target(1), 1,
                OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
        try {
            lane.submit(target(2), 1, OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
            throw new AssertionError("missing capacity rejection");
        } catch (java.util.concurrent.RejectedExecutionException expected) { }
        try {
            lane.submit(target(1), 1, OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
            throw new AssertionError("missing duplicate target rejection");
        } catch (IllegalStateException expected) { }
        try (var lease = lane.poll().orElseThrow()) {
            check(lease.command() == command, "command identity changed");
            // Missing explicit ack => automatic retry on lease close.
        }
        try (var retry = lane.poll().orElseThrow()) {
            check(retry.command() == command, "retry must preserve sequence and ticket");
            retry.ack();
            try { retry.ack(); throw new AssertionError("double ack"); }
            catch (IllegalStateException expected) { }
        }
        check(lane.snapshot().outstanding() == 0, "failed to retire command");
        check(lane.snapshot().retried() == 1, "missing retry metric");
        check(lane.snapshot().acknowledged() == 1, "missing ack metric");
    }

    private static void epochAndAbort() {
        var lane = new OtpSupervisorCommandLaneV1(2);
        var a = lane.submit(target(1), 4, OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
        var b = lane.submit(target(2), 5, OtpSupervisorCommandLaneV1.Kind.QUIESCE_CHECK, DIGEST);
        var first = lane.poll().orElseThrow();
        try {
            lane.abortAfterRollback(target(1), 3);
            throw new AssertionError("stale abort admitted");
        } catch (IllegalStateException expected) { }
        check(lane.abortAfterRollback(target(1), 4).orElseThrow() == a, "wrong aborted command");
        try { first.ack(); throw new AssertionError("revoked in-flight lease reused"); }
        catch (IllegalStateException expected) { }
        try (var second = lane.poll().orElseThrow()) {
            check(second.command() == b, "wrong command after abort");
            second.ack();
        }
        check(lane.snapshot().outstanding() == 0, "leaked abort target");
        check(lane.snapshot().aborted() == 1, "missing abort metric");
        check(lane.submit(target(1), 5, OtpSupervisorCommandLaneV1.Kind.GRACEFUL_STOP,
                null).routeEpoch() == 5, "valid stop rejected");
    }

    private static void retryFairnessBetweenActors() {
        var lane = new OtpSupervisorCommandLaneV1(2);
        var first = lane.submit(target(1), 2,
                OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
        var second = lane.submit(target(2), 2,
                OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
        try (var lease = lane.poll().orElseThrow()) {
            check(lease.command() == first, "FIFO initial order violated");
            lease.retry();
        }
        try (var lease = lane.poll().orElseThrow()) {
            check(lease.command() == second, "repeated retry starved unrelated actor");
            lease.ack();
        }
        try (var lease = lane.poll().orElseThrow()) {
            check(lease.command() == first, "retry must retain exact command identity");
            lease.ack();
        }
        check(lane.snapshot().outstanding() == 0, "fair retry leaked credits");
    }

    private static void invalidInput() {
        var lane = new OtpSupervisorCommandLaneV1(2);
        try { lane.submit(target(1), 1, OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE,
                "bad-digest"); throw new AssertionError("accepted bad code hash"); }
        catch (IllegalArgumentException expected) { }
        try { lane.submit(target(1), 0, OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE,
                DIGEST); throw new AssertionError("accepted bad epoch"); }
        catch (IllegalArgumentException expected) { }
        check(lane.snapshot().outstanding() == 0, "invalid input consumed reservation");
    }

    private static void abortRace() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 512; i++) {
                var lane = new OtpSupervisorCommandLaneV1(1);
                lane.submit(target(1), 3, OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
                var lease = lane.poll().orElseThrow();
                var start = new CountDownLatch(1);
                AtomicInteger winners = new AtomicInteger();
                var ack = pool.submit(() -> {
                    start.await();
                    try { lease.ack(); winners.incrementAndGet(); }
                    catch (IllegalStateException revoked) { }
                    return null;
                });
                var abort = pool.submit(() -> {
                    start.await();
                    if (lane.abortAfterRollback(target(1), 3).isPresent()) winners.incrementAndGet();
                    return null;
                });
                start.countDown();
                ack.get(5, TimeUnit.SECONDS);
                abort.get(5, TimeUnit.SECONDS);
                check(winners.get() == 1, "abort/ack did not settle exactly once");
                check(lane.snapshot().outstanding() == 0, "abort/ack leaked command");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        lifecycleAndCapacity();
        epochAndAbort();
        invalidInput();
        retryFairnessBetweenActors();
        abortRace();
        System.out.println("PASS: lease idempotence/retry/duplicate admission/capacity/epoch fencing");
        System.out.println("PASS: 512 concurrent ack-vs-explicit-abort races");
        System.out.println("LIMIT: host-only supervisor command queue, not actor dispatch or hot route publication");
    }
}
