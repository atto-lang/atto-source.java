package dev.oreslang.runtime;

final class SupervisorReclamationGuardV1Standalone {
    private static void check(boolean value) {
        if (!value) throw new AssertionError("reclamation guard invariant");
    }
    private static void denied(SupervisorReclamationGuardV1 g, long id) {
        try { g.requireFresh(id); throw new AssertionError("stale identity admitted"); }
        catch (IllegalStateException expected) { }
    }
    public static void main(String[] args) {
        var guard = new SupervisorReclamationGuardV1(true);
        guard.issued(1);
        for (long id = 2; id <= 100001; id++) {
            guard.requireFresh(id);
            guard.issued(id);
            denied(guard, id);
            denied(guard, 1);
        }
        // Exhaustively vary all Boolean retirement conditions and outstanding DATA.
        int accepted = 0;
        for (int bits = 0; bits < 1024; bits++) {
            boolean[] v = new boolean[10];
            for (int i = 0; i < v.length; i++) v[i] = (bits & (1 << i)) != 0;
            for (int inFlight = 0; inFlight < 2; inFlight++) {
                boolean ok = guard.canReclaim(v[0], v[1], v[2], v[3], v[4], inFlight,
                        v[5], v[6], v[7], v[8], v[9]);
                check(ok == (bits == 964 && inFlight == 0));
                if (ok) accepted++;
            }
        }
        check(accepted == 1);
        guard.issued(Long.MAX_VALUE);
        denied(guard, Long.MAX_VALUE);
        denied(guard, Long.MIN_VALUE);
        var legacy = new SupervisorReclamationGuardV1(false);
        legacy.issued(2);
        legacy.requireFresh(2);
        check(!legacy.canReclaim(false, false, true, false, false, 0,
                false, true, true, true, true));
        System.out.println("PASS ISSUANCE_CYCLES=100000 RECLAMATION_GATE_CASES=2048 ACCEPTED=1 NO_WRAP=1 LEGACY_RECLAMATION_DISABLED=1");
    }
}
