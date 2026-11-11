package dev.oreslang.runtime;

import java.util.UUID;

/** Executable HOST-ONLY generation handover proof (no guest actor execution). */
public final class SupervisorHotUpgradeHostDemo {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }
    private static SupervisorHotUpgradeGateV1.Image image(char code) {
        return new SupervisorHotUpgradeGateV1.Image(sha(code), sha('1'), sha('2'), sha('3'));
    }

    public static void main(String[] args) {
        Object bootstrapCoordinator = new Object();
        UUID root = UUID.randomUUID();
        UUID childA = UUID.randomUUID();
        UUID childB = UUID.randomUUID();
        var registry = new SupervisorHotUpgradeGateV1.ChildRegistry();
        try (var gate = new SupervisorHotUpgradeGateV1(root, null, bootstrapCoordinator, registry, image('a'))) {
            gate.attachChild(childA);
            gate.attachChild(childB);
            var v1 = gate.snapshot();
            var queuedControl = gate.reserveControl(childA);
            var candidate = gate.prepare(bootstrapCoordinator, v1.epoch(), image('b'));
            check(!gate.publish(bootstrapCoordinator, candidate), "must wait for queued CONTROL");
            check(gate.snapshot().image().equals(image('a')), "old image still active");
            try {
                gate.reserveControl(childB);
                throw new AssertionError("new CONTROL admission while frozen");
            } catch (IllegalStateException expected) { /* correct */ }
            queuedControl.close();
            check(gate.publish(bootstrapCoordinator, candidate), "handover published");
            var v2 = gate.snapshot();
            check(v2.epoch() == v1.epoch() + 1, "generation advanced");
            check(v2.supervisorId().equals(root), "stable supervisor identity");
            check(v2.children().equals(v1.children()), "existing children survived");
            check(registry.parentOf(childA).equals(root), "single parent persisted");
            check(v2.image().equals(image('b')), "new image active");
            System.out.println("PASS supervisor v1->v2: old CONTROL drained, stable children, fenced epoch");
            var bad = new SupervisorHotUpgradeGateV1.Image(sha('c'), sha('4'), sha('2'), sha('3'));
            try {
                gate.prepare(bootstrapCoordinator, v2.epoch(), bad);
                throw new AssertionError("policy ABI drift must reject");
            } catch (SecurityException expected) { /* correct */ }
            check(gate.snapshot().image().equals(image('b')), "failed v3 kept v2");
            var rollback = gate.prepare(bootstrapCoordinator, v2.epoch(), image('c'));
            gate.abort(bootstrapCoordinator, rollback);
            check(gate.snapshot().image().equals(image('b')), "precommit abort kept v2");
            System.out.println("PASS incompatible v3 rejected; aborted replacement preserves v2");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
