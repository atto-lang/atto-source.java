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
        var zero = new ActorSpawnReservation.Usage(0, 0, 0, 0);
        var gate = new ActorSpawnReservation(unit);
        var p = gate.reserve(unit);
        check(!gate.mayReceive(p)); check(gate.tryEnterReceive(p) == null);
        deny(() -> gate.completeInit(null, true), "E_SPAWN_INIT_AUTHORITY");
        var init = gate.beginInit(p);
        deny(() -> gate.beginInit(p), "E_SPAWN_INIT_AUTHORITY");
        var other = new ActorSpawnReservation(unit);
        deny(() -> other.completeInit(init, true), "E_SPAWN_STALE_PERMIT");
        check(gate.completeInit(init, true)); check(gate.mayReceive(p));
        deny(() -> gate.completeInit(init, true), "E_SPAWN_INIT_AUTHORITY");
        deny(() -> gate.releaseAfterQuiescence(p), "E_SPAWN_NOT_RETIRED");
        var a = gate.tryEnterReceive(p); var b = gate.tryEnterReceive(p);
        check(a != null && b != null);
        gate.retire(p); check(!gate.mayReceive(p)); check(gate.tryEnterReceive(p) == null);
        deny(() -> gate.releaseAfterQuiescence(p), "E_SPAWN_IN_FLIGHT");
        a.close(); a.close();
        deny(() -> gate.releaseAfterQuiescence(p), "E_SPAWN_IN_FLIGHT");
        deny(() -> gate.reserve(unit), "E_SPAWN_QUOTA");
        b.close(); gate.releaseAfterQuiescence(p); b.close();
        deny(() -> gate.releaseAfterQuiescence(p), "E_SPAWN_STALE_PERMIT");
        var q = gate.reserve(unit); check(q.generation > p.generation);
        deny(() -> gate.beginInit(p), "E_SPAWN_STALE_PERMIT");
        var qi = gate.beginInit(q); gate.retire(q);
        deny(() -> gate.releaseAfterQuiescence(q), "E_SPAWN_IN_FLIGHT");
        check(!gate.completeInit(qi, true));
        deny(() -> gate.completeInit(qi, true), "E_SPAWN_INIT_AUTHORITY");
        gate.releaseAfterQuiescence(q);
        var failed = gate.reserve(unit);
        check(!gate.completeInit(gate.beginInit(failed), false));
        check(gate.tryEnterReceive(failed) == null); gate.releaseAfterQuiescence(failed);
        check(gate.usage().equals(zero));
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
        // Race stop against admission and init completion, with real host threads.
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2000; i++) {
                var x = gate.reserve(unit); var xi = gate.beginInit(x);
                var start = new CountDownLatch(1);
                var completion = pool.submit(() -> { start.await(); return gate.completeInit(xi, true); });
                var stop = pool.submit(() -> { start.await(); gate.retire(x); return null; });
                start.countDown(); completion.get(10, TimeUnit.SECONDS); stop.get(10, TimeUnit.SECONDS);
                check(!gate.mayReceive(x)); check(gate.tryEnterReceive(x) == null); gate.releaseAfterQuiescence(x);
                var y = gate.reserve(unit); check(gate.completeInit(gate.beginInit(y), true));
                var race = new CountDownLatch(1);
                var receive = pool.submit(() -> { race.await(); return gate.tryEnterReceive(y); });
                var retire = pool.submit(() -> { race.await(); gate.retire(y); return null; });
                race.countDown(); var lease = receive.get(10, TimeUnit.SECONDS); retire.get(10, TimeUnit.SECONDS);
                if (lease != null) { deny(() -> gate.releaseAfterQuiescence(y), "E_SPAWN_IN_FLIGHT"); lease.close(); }
                check(gate.tryEnterReceive(y) == null); gate.releaseAfterQuiescence(y);
            }
        }
        for (int i = 0; i < 1_000_000; i++) {
            var x = gate.reserve(unit); var xi = gate.beginInit(x); gate.retire(x);
            check(!gate.completeInit(xi, true)); gate.releaseAfterQuiescence(x);
        }
        for (var charge : new ActorSpawnReservation.Charge[] {
            new ActorSpawnReservation.Charge(2,1,1,1), new ActorSpawnReservation.Charge(1,2,1,1),
            new ActorSpawnReservation.Charge(1,1,2,1), new ActorSpawnReservation.Charge(1,1,1,2)
        }) deny(() -> gate.reserve(charge), "E_SPAWN_QUOTA");
        check(gate.usage().equals(zero));
        System.out.println("SPAWN_RESERVATION_HOST_PASS 32 contenders; 4000 race pairs; 1000000 rollback cycles; no guest execution");
    }
}
