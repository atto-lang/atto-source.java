package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ActorSpawnLiveReceiveSmoke {
    private static int checks;
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    private static void deny(String code, Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException ex) { check(code.equals(ex.getMessage())); return; }
        throw new AssertionError("expected " + code);
    }
    public static void main(String[] args) throws Exception {
        var limit = new ActorSpawnReservation.Charge(10, 20, 16, 10);
        var zero = new ActorSpawnReservation.Usage(0, 0, 0, 0);
        var gate = new ActorSpawnReservation(limit);
        deny("E_SPAWN_POLICY", () -> gate.reserve(limit, 0));
        check(gate.usage().equals(zero));
        var host = new ActorSpawnAdmission(limit);
        var schema = new ActorStartupSchema(List.of());
        var defaults = new ActorSpawnChargePlan.Defaults(ActorSpawnChargePlan.Affinity.STICKY, 10, 10, 10);
        var request = new ActorSpawnChargePlan.Request(null, null, null, null, null);
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (boolean serial : new boolean[]{true, false}) {
                var policy = new ActorSpawnChargePlan.Policy(serial, 1, 1, 2, 10, 10, 10, 16);
                int cap = serial ? 1 : 2;
                for (int round = 0; round < 100; round++) {
                    var admission = host.admit(schema, List.of(), policy, defaults, request);
                    check(host.tryEnterReceive(admission) == null);
                    check(host.completeInit(host.beginInit(admission), true));
                    var start = new CountDownLatch(1);
                    var pending = new ArrayList<Future<ActorSpawnReservation.ReceiveLease>>();
                    for (int i = 0; i < 16; i++) pending.add(pool.submit(() -> {
                        start.await(); return host.tryEnterReceive(admission);
                    }));
                    start.countDown();
                    var held = new ArrayList<ActorSpawnReservation.ReceiveLease>();
                    for (var future : pending) {
                        var lease = future.get(10, TimeUnit.SECONDS);
                        if (lease != null) held.add(lease);
                    }
                    check(held.size() == cap);
                    // Hold logical leases while no worker executes: suspension must not free a receive slot.
                    check(host.tryEnterReceive(admission) == null);
                    held.getFirst().close(); held.getFirst().close();
                    var replacement = host.tryEnterReceive(admission);
                    check(replacement != null);
                    check(host.tryEnterReceive(admission) == null);
                    host.retire(admission);
                    check(host.tryEnterReceive(admission) == null);
                    deny("E_SPAWN_IN_FLIGHT", () -> host.releaseAfterQuiescence(admission));
                    held.forEach(ActorSpawnReservation.ReceiveLease::close);
                    replacement.close(); host.releaseAfterQuiescence(admission);
                    check(host.usage().equals(zero));
                }
            }
        }
        System.out.println("SPAWN_LIVE_RECEIVE_PASS " + checks + " checks; 3200 concurrent attempts; host only");
    }
}
