package dev.oreslang.compiler;

/**
 * Executable source-to-STOP compiler subset proof. This is not the general
 * Oreslang source evaluator and cannot mint a guest SupervisorRef.
 * Independent runner builds the real Parser/Ast/SourceSupervisorLowering.
 */
public final class SourceSupervisorCompilerProof {
    private SourceSupervisorCompilerProof() { }

    private static int accepted;
    private static int rejected;

    private static SourceSupervisorLowering.StopOnlySupervisor accept(String source) {
        var plan = SourceSupervisorLowering.compile(source);
        if (!"Root".equals(plan.sourceName())) {
            throw new AssertionError("wrong nominal source supervisor identity");
        }
        accepted++;
        return plan;
    }

    private static void deny(String source) {
        try {
            SourceSupervisorLowering.compile(source);
        } catch (IllegalArgumentException expected) {
            rejected++;
            return;
        }
        throw new AssertionError("unsupported supervisor execution was admitted: " + source);
    }

    public static void runAll() {
        accepted = 0;
        rejected = 0;
        String wrapper = "define supervisor Root as on_panic(f: Failure): void { ";
        String ending = " } end";
        var sync = accept(wrapper + "self.stop(f); return;" + ending);
        if (!sync.explicitlyBoundFailure()
                || sync.awaitsStopAcknowledgment() || sync.targetsLogicalChild()) {
            throw new AssertionError("incorrect synchronous STOP decision plan");
        }
        var async = accept("define supervisor Root as "
                + "pub async on_panic(f: Failure): void { await self.stop(f); return; } end");
        if (!async.awaitsStopAcknowledgment() || !async.explicitlyBoundFailure()) {
            throw new AssertionError("host STOP acknowledgment was erased by lowering");
        }
        var child = accept(wrapper + "self.stop(f.child); return;" + ending);
        if (!child.targetsLogicalChild()) {
            throw new AssertionError("checked Failure.child target was erased");
        }
        for (String body : new String[] {
                "self.restart(f); return;",
                "self.stop(f.actor); return;",
                "self.stop(other); return;",
                "other.stop(f); return;",
                "self.stop(f); self.stop(f); return;",
                "self.stop(f); print(42); return;",
                "self.stop(f); await self.stop(f); return;"
        }) deny(wrapper + body + ending);
        deny("define supervisor Root as async on_panic(f: Failure): void "
                + "{ self.stop(f); return; } end");
        deny("define supervisor Root as on_panic(f: Failure): void "
                + "{ await self.stop(f); return; } end");
        deny("define supervisor Root as "
                + "on_start(): void { return; } "
                + "on_panic(f: Failure): void { self.stop(f); return; } end");
        if (accepted != 3 || rejected != 10) {
            throw new AssertionError("bad proof totals accepted=" + accepted
                    + " rejected=" + rejected);
        }
    }

    public static void main(String[] args) {
        runAll();
        System.out.println("JAVA_SUPERVISOR_SOURCE_COMPILER=PASS accepted="
                + accepted + " rejected=" + rejected);
    }
}
