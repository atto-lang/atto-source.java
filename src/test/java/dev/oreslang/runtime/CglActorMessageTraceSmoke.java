package dev.oreslang.runtime;

import java.util.UUID;

public final class CglActorMessageTraceSmoke {
    private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        UUID actor = UUID.randomUUID();
        CglActorMessageTrace trace = new CglActorMessageTrace(2);
        require(trace.record(actor, 1, 10, 13, true));
        require(trace.record(actor, 2, 20, 23, false));
        require(!trace.record(actor, 3, 30, 33, true));
        require(!trace.record(actor, -1, 40, 50, true));
        require(!trace.snapshot().hasCompleteCapture(2));
        trace.sealAfterActorsStopped();
        var snapshot = trace.snapshot();
        require(snapshot.samples().size() == 2);
        require(snapshot.samples().get(0).sourceActor());
        require(!snapshot.samples().get(1).sourceActor());
        require(snapshot.dropped() == 1 && snapshot.invalid() == 1);
        require(!snapshot.hasCompleteCapture(2));
        var clean = new CglActorMessageTrace(1);
        require(clean.record(actor, 0, -10, -9, true));
        clean.sealAfterActorsStopped();
        require(clean.snapshot().hasCompleteCapture(1));
        require(!clean.record(actor, 2, 1, 2, true));
        require(clean.snapshot().invalid() == 1);
        for (int invalid : new int[] {0, -1, 4097}) {
            try { new CglActorMessageTrace(invalid); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { /* reject */ }
        }
    }
}
