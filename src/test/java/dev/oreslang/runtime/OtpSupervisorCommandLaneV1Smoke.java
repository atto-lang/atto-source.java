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

    private static void quarantinesUnsettledCommandsOnTermination() {
        var lane = new OtpSupervisorCommandLaneV1(2);
        lane.submit(target(1), 7, OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
        lane.submit(target(2), 7, OtpSupervisorCommandLaneV1.Kind.QUIESCE_CHECK, DIGEST);
        var active = lane.poll().orElseThrow();
        check(lane.quarantineOnTermination() == 2, "terminal command count");
        check(lane.quarantineOnTermination() == 0, "double quarantine");
        check(lane.snapshot().quarantined() == 2, "terminal metric");
        check(lane.snapshot().acknowledged() == 0, "termination is not ACK");
        check(lane.snapshot().outstanding() == 0, "terminal target index leak");
        check(lane.snapshot().queued() == 0 && lane.snapshot().inFlight() == 0,
                "unsettled terminal command");
        try { active.ack(); throw new AssertionError("stale command ACK after stop"); }
        catch (IllegalStateException expected) { }
        active.close();
        try {
            lane.submit(target(3), 8, OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
            throw new AssertionError("terminated lane admitted new work");
        } catch (IllegalStateException expected) { }
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


    private static void terminalStopRacesPhysicalLeaseSettlement() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 512; i++) {
                var lane = new OtpSupervisorCommandLaneV1(2);
                var command = lane.submit(target(1), 7,
                        OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
                lane.submit(target(2), 7,
                        OtpSupervisorCommandLaneV1.Kind.QUIESCE_CHECK, DIGEST);
                var lease = lane.poll().orElseThrow();
                var start = new CountDownLatch(1);
                var ack = pool.submit(() -> {
                    start.await();
                    try { lease.ack(); } catch (IllegalStateException revoked) { }
                    return null;
                });
                var stop = pool.submit(() -> {
                    start.await();
                    return lane.quarantineOnTermination();
                });
                start.countDown();
                ack.get(5, TimeUnit.SECONDS);
                int quarantined = stop.get(5, TimeUnit.SECONDS);
                var snapshot = lane.snapshot();
                check(snapshot.outstanding() == 0
                        && snapshot.inFlight() == 0 && snapshot.queued() == 0,
                        "terminal race left ghost command or lease");
                check(snapshot.acknowledged() + quarantined == 2,
                        "terminal race lost or double-counted CONTROL command");
                check(snapshot.quarantined() == quarantined,
                        "quarantine count mismatch");
                check(lane.quarantineOnTermination() == 0,
                        "terminal stop must be idempotent");
                try {
                    lane.submit(target(3), 8,
                            OtpSupervisorCommandLaneV1.Kind.GRACEFUL_STOP, null);
                    throw new AssertionError("stopped lane accepted new command");
                } catch (IllegalStateException expected) { }
                lease.close();
                check(lane.snapshot().quarantined() == quarantined,
                        "closing a revoked lease mutated terminal count");
                check(command.sequence() == 1, "initial command identity changed");
            }
        }
    }


    private static void auditCountersNeverWrapNegative() throws Exception {
        var lane = new OtpSupervisorCommandLaneV1(2);
        for (String name : new String[] {"sent", "acknowledged", "retried",
                "aborted", "admissionRejected", "duplicateTargetRejected",
                "quarantined"}) {
            var field = OtpSupervisorCommandLaneV1.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(lane, Long.MAX_VALUE - 1);
        }
        var a = lane.submit(target(1), 9,
                OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
        var b = lane.submit(target(2), 9,
                OtpSupervisorCommandLaneV1.Kind.PREPARE_UPGRADE, DIGEST);
        check(a.sequence() == 1 && b.sequence() == 2,
                "telemetry saturation changed authoritative sequence numbers");
        try {
            lane.submit(target(1), 9,
                    OtpSupervisorCommandLaneV1.Kind.QUIESCE_CHECK, DIGEST);
            throw new AssertionError("duplicate accepted");
        } catch (IllegalStateException expected) { }
        try {
            lane.submit(target(3), 9,
                    OtpSupervisorCommandLaneV1.Kind.QUIESCE_CHECK, DIGEST);
            throw new AssertionError("full command lane accepted");
        } catch (java.util.concurrent.RejectedExecutionException expected) { }
        try (var retry = lane.poll().orElseThrow()) { retry.retry(); }
        try (var ack = lane.poll().orElseThrow()) { ack.ack(); }
        check(lane.abortAfterRollback(target(1), 9).isPresent(),
                "outstanding retried command could not be rolled back");
        var snapshot = lane.snapshot();
        check(snapshot.sent() == Long.MAX_VALUE
                        && snapshot.retried() == Long.MAX_VALUE
                        && snapshot.acknowledged() == Long.MAX_VALUE
                        && snapshot.aborted() == Long.MAX_VALUE
                        && snapshot.admissionRejected() == Long.MAX_VALUE
                        && snapshot.duplicateTargetRejected() == Long.MAX_VALUE,
                "CONTROL telemetry silently wrapped instead of saturating");
        lane.submit(target(3), 10,
                OtpSupervisorCommandLaneV1.Kind.GRACEFUL_STOP, null);
        lane.submit(target(4), 10,
                OtpSupervisorCommandLaneV1.Kind.GRACEFUL_STOP, null);
        check(lane.quarantineOnTermination() == 2,
                "terminal quarantine count mismatch");
        check(lane.snapshot().quarantined() == Long.MAX_VALUE,
                "quarantine metric overflowed instead of saturating");
    }

    public static void main(String[] args) throws Exception {
        lifecycleAndCapacity();
        epochAndAbort();
        invalidInput();
        retryFairnessBetweenActors();
        quarantinesUnsettledCommandsOnTermination();
        abortRace();
        terminalStopRacesPhysicalLeaseSettlement();
        auditCountersNeverWrapNegative();
        System.out.println("PASS: lease idempotence/retry/duplicate admission/capacity/epoch fencing");
        System.out.println("PASS: 512 concurrent ack-vs-explicit-abort races");
        System.out.println("PASS: 512 concurrent ACK-vs-terminal-quarantine races");
        System.out.println("LIMIT: host-only supervisor command queue, not actor dispatch or hot route publication");
    }
}
