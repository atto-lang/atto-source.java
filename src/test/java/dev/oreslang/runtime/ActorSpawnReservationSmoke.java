package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

final class ActorSpawnReservationSmoke {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    private static void deny(Runnable f, String code) {
        try { f.run(); } catch (IllegalStateException e) { check(code.equals(e.getMessage())); return; }
        throw new AssertionError("expected " + code);
    }
    public static void main(String[] args) throws Exception {
        var unit = new ActorSpawnReservation.Charge(1, 1, 1, 1);
        var gate = new ActorSpawnReservation(unit);
        var p = gate.reserve(unit);
        check(!gate.mayReceive(p));
        deny(() -> gate.completeInit(p), "E_SPAWN_INIT_AUTHORITY");
        gate.beginInit(p);
        deny(() -> gate.beginInit(p), "E_SPAWN_INIT_AUTHORITY");
        check(gate.completeInit(p)); check(gate.mayReceive(p));
        deny(() -> gate.completeInit(p), "E_SPAWN_INIT_AUTHORITY");
        deny(() -> gate.releaseAfterQuiescence(p), "E_SPAWN_NOT_RETIRED");
        gate.retire(p); check(!gate.mayReceive(p));
        deny(() -> gate.reserve(unit), "E_SPAWN_QUOTA");
        gate.releaseAfterQuiescence(p);
        deny(() -> gate.releaseAfterQuiescence(p), "E_SPAWN_STALE_PERMIT");
        var q = gate.reserve(unit); check(q.generation > p.generation);
        deny(() -> gate.beginInit(p), "E_SPAWN_STALE_PERMIT");
        var other = new ActorSpawnReservation(unit);
        deny(() -> other.beginInit(q), "E_SPAWN_STALE_PERMIT");
        gate.beginInit(q); gate.retire(q); check(!gate.completeInit(q));
        gate.releaseAfterQuiescence(q);
        check(gate.usage().equals(new ActorSpawnReservation.Usage(0, 0, 0, 0)));
        // Concurrent admission must never overbook a one-slot quota. Hold winner until all attempt.
        var go = new CountDownLatch(1); var attempted = new CountDownLatch(32);
        var release = new CountDownLatch(1); var winners = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(32)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 32; i++) futures.add(pool.submit(() -> {
                go.await(); ActorSpawnReservation.Permit permit = null;
                try { permit = gate.reserve(unit); winners.incrementAndGet(); }
                catch (IllegalStateException e) { check("E_SPAWN_QUOTA".equals(e.getMessage())); }
                finally { attempted.countDown(); }
                if (permit != null) { release.await(); gate.retire(permit); gate.releaseAfterQuiescence(permit); }
                return null;
            }));
            try { go.countDown(); check(attempted.await(10, TimeUnit.SECONDS)); check(winners.get() == 1); }
            finally { release.countDown(); }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        check(gate.usage().equals(new ActorSpawnReservation.Usage(0, 0, 0, 0)));
        // Test repeated reservation/retirement and independent resource dimensions.
        for (int i = 0; i < 100_000; i++) {
            var a = gate.reserve(unit); gate.beginInit(a); gate.retire(a);
            check(!gate.completeInit(a)); gate.releaseAfterQuiescence(a);
        }
        for (var charge : new ActorSpawnReservation.Charge[] {
            new ActorSpawnReservation.Charge(2,1,1,1), new ActorSpawnReservation.Charge(1,2,1,1),
            new ActorSpawnReservation.Charge(1,1,2,1), new ActorSpawnReservation.Charge(1,1,1,2)
        }) deny(() -> gate.reserve(charge), "E_SPAWN_QUOTA");
        check(gate.usage().equals(new ActorSpawnReservation.Usage(0, 0, 0, 0)));
        System.out.println("SPAWN_RESERVATION_HOST_PASS 32-way admission; 100000 rollback cycles; no guest execution");
    }
}
