package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class ActorSpawnAdmissionSmoke {
    private static int checks;
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    private static void deny(String code, Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException e) { check(code.equals(e.getMessage())); return; }
        throw new AssertionError("expected " + code);
    }
    public static void main(String[] args) throws Exception {
        var schema = new ActorStartupSchema(List.of(new ActorStartupSchema.Field("seed", ActorStartupSchema.Kind.INT64)));
        var supplied = List.of(new ActorStartupSchema.Supplied("seed", 42L));
        var policy = new ActorSpawnChargePlan.Policy(true, 1, 1, 2, 10, 10, 10, 16);
        var defaults = new ActorSpawnChargePlan.Defaults(ActorSpawnChargePlan.Affinity.STICKY, 10, 10, 10);
        var request = new ActorSpawnChargePlan.Request(null, null, null, null, null);
        var limit = new ActorSpawnReservation.Charge(10, 20, 16, 10);
        var zero = new ActorSpawnReservation.Usage(0, 0, 0, 0);
        var host = new ActorSpawnAdmission(limit);
        var foreign = new ActorSpawnAdmission(limit);
        deny("E_SPAWN_INIT_SCHEMA", () -> host.admit(schema, List.of(), policy, defaults, request));
        deny("E_SPAWN_POLICY", () -> host.admit(schema, supplied, policy, defaults,
                new ActorSpawnChargePlan.Request(2, null, null, null, null)));
        check(host.usage().equals(zero));
        var admission = host.admit(schema, supplied, policy, defaults, request);
        check(admission.plan().charge().equals(limit));
        check(!host.mayReceive(admission));
        check(host.tryEnterReceive(admission) == null);
        deny("E_SPAWN_QUOTA", () -> host.admit(schema, supplied, policy, defaults, request));
        deny("E_SPAWN_INIT_AUTHORITY", () -> foreign.beginInit(admission));
        var ticket = host.beginInit(admission);
        deny("E_SPAWN_INIT_AUTHORITY", () -> host.beginInit(admission));
        deny("E_SPAWN_INIT_AUTHORITY", () -> foreign.takeStartup(ticket));
        check(host.takeStartup(ticket).equals(List.of(42L)));
        deny("E_SPAWN_INIT_AUTHORITY", () -> host.takeStartup(ticket));
        check(host.completeInit(ticket, true));
        deny("E_SPAWN_INIT_AUTHORITY", () -> host.completeInit(ticket, true));
        var lease = host.tryEnterReceive(admission);
        check(lease != null);
        host.retire(admission);
        deny("E_SPAWN_IN_FLIGHT", () -> host.releaseAfterQuiescence(admission));
        check(host.tryEnterReceive(admission) == null);
        lease.close(); host.releaseAfterQuiescence(admission);
        check(host.usage().equals(zero));
        deny("E_SPAWN_STALE_PERMIT", () -> host.beginInit(admission));
        var cancelled = host.admit(schema, supplied, policy, defaults, request);
        var ct = host.beginInit(cancelled);
        host.retire(cancelled);
        deny("E_SPAWN_INIT_AUTHORITY", () -> host.takeStartup(ct));
        deny("E_SPAWN_IN_FLIGHT", () -> host.releaseAfterQuiescence(cancelled));
        check(!host.completeInit(ct, true));
        host.releaseAfterQuiescence(cancelled);
        check(host.usage().equals(zero));
        var failed = host.admit(schema, supplied, policy, defaults, request);
        var ft = host.beginInit(failed);
        check(!host.completeInit(ft, false));
        deny("E_SPAWN_INIT_AUTHORITY", () -> host.takeStartup(ft));
        host.releaseAfterQuiescence(failed);
        check(host.usage().equals(zero));
        var skipped = host.admit(schema, supplied, policy, defaults, request);
        var st = host.beginInit(skipped);
        check(host.completeInit(st, true));
        deny("E_SPAWN_INIT_AUTHORITY", () -> host.takeStartup(st));
        host.retire(skipped); host.releaseAfterQuiescence(skipped);
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2000; i++) {
                var a = host.admit(schema, supplied, policy, defaults, request);
                var t = host.beginInit(a);
                var start = new CountDownLatch(1);
                var take = pool.submit(() -> {
                    start.await();
                    try { return host.takeStartup(t); }
                    catch (IllegalStateException e) {
                        if (!"E_SPAWN_INIT_AUTHORITY".equals(e.getMessage())) throw e;
                        return null;
                    }
                });
                var stop = pool.submit(() -> { start.await(); host.retire(a); return null; });
                start.countDown();
                var values = take.get(10, TimeUnit.SECONDS); stop.get(10, TimeUnit.SECONDS);
                check(values == null || values.equals(List.of(42L)));
                deny("E_SPAWN_INIT_AUTHORITY", () -> host.takeStartup(t));
                check(!host.completeInit(t, true));
                check(!host.mayReceive(a));
                host.releaseAfterQuiescence(a);
                check(host.usage().equals(zero));
            }
        }
        System.out.println("SPAWN_ADMISSION_HOST_PASS " + checks + " checks; 2000 take/retire race pairs; no guest execution");
    }
}
