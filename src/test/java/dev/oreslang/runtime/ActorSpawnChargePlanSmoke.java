package dev.oreslang.runtime;

import static dev.oreslang.runtime.ActorSpawnChargePlan.*;

final class ActorSpawnChargePlanSmoke {
    private static int checks;
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); checks++; }
    private static void deny(String code, Runnable action) {
        try { action.run(); } catch (IllegalArgumentException e) { check(code.equals(e.getMessage())); return; }
        throw new AssertionError("expected " + code);
    }
    public static void main(String[] args) {
        var policy = new Policy(false, 4, 2, 8, 1000, 100, 50, 4096);
        var defaults = new Defaults(Affinity.STICKY, 500, 50, 10);
        var absent = new Request(null, null, null, null, null);
        var plan = resolve(policy, defaults, absent);
        check(plan.carrierCap() == 2);
        check(plan.charge().equals(new ActorSpawnReservation.Charge(500, 400, 4096, 10)));
        var narrowed = resolve(policy, defaults, new Request(1, Affinity.PINNED, 300L, 20L, 3L));
        check(narrowed.carrierCap() == 1 && narrowed.affinity() == Affinity.PINNED);
        check(narrowed.charge().equals(new ActorSpawnReservation.Charge(300, 160, 4096, 3)));
        deny("E_SPAWN_POLICY", () -> resolve(policy, defaults, new Request(5, null, null, null, null)));
        deny("E_SPAWN_POLICY", () -> resolve(policy, defaults, new Request(0, null, null, null, null)));
        deny("E_SPAWN_POLICY", () -> resolve(policy, defaults, new Request(2, Affinity.PINNED, null, null, null)));
        var serial = new Policy(true, 1, 1, 8, 1000, 100, 50, 4096);
        check(resolve(serial, defaults, absent).carrierCap() == 1);
        deny("E_SPAWN_POLICY", () -> resolve(serial, defaults, new Request(2, null, null, null, null)));
        deny("E_SPAWN_POLICY", () -> new Policy(true, 2, 1, 8, 1000, 100, 50, 4096));
        for (long bad : new long[]{0, -1, 1001})
            deny("E_SPAWN_QUOTA", () -> resolve(policy, defaults, new Request(null, null, bad, null, null)));
        deny("E_SPAWN_QUOTA", () -> resolve(policy, defaults, new Request(null, null, null, 101L, null)));
        deny("E_SPAWN_QUOTA", () -> resolve(policy, defaults, new Request(null, null, null, null, 51L)));
        deny("E_SPAWN_QUOTA", () -> resolve(policy, new Defaults(Affinity.STICKY, 0, 50, 10), new Request(null, null, 10L, null, null)));
        var huge = new Policy(false, 1, 1, Long.MAX_VALUE, 1000, 100, 50, 4096);
        deny("E_SPAWN_QUOTA", () -> resolve(huge, defaults, absent));
        var edge = new Policy(false, 1, 1, Long.MAX_VALUE, 1, 1, 1, 1);
        check(resolve(edge, new Defaults(Affinity.NONE, 1, 1, 1), absent).charge().stacks() == Long.MAX_VALUE);
        var gate = new ActorSpawnReservation(plan.charge());
        var permit = gate.reserve(plan.charge());
        check(gate.usage().equals(new ActorSpawnReservation.Usage(500, 400, 4096, 10)));
        try { gate.reserve(narrowed.charge()); throw new AssertionError(); }
        catch (IllegalStateException e) { check("E_SPAWN_QUOTA".equals(e.getMessage())); }
        gate.retire(permit);
        gate.releaseAfterQuiescence(permit);
        check(gate.usage().equals(new ActorSpawnReservation.Usage(0, 0, 0, 0)));
        System.out.println("SPAWN_CHARGE_PLAN_PASS " + checks + " checks; host calculation only");
    }
}
