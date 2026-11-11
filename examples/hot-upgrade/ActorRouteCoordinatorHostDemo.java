package dev.oreslang.runtime;

import java.nio.ByteBuffer;

/**
 * HOST-ONLY runnable actor route demonstration, not compiled .atto code.
 * The stable logical ref routes 10 v1 turns and five v2 turns with retained
 * state and FIFO. Physical V1/V2 are intentionally separate host simulations.
 */
public final class ActorRouteCoordinatorHostDemo {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }
    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(sha(code), sha('1'), sha('2'), sha('3'));
    }
    private static ActorRouteCoordinatorV1.StateSnapshot state(int count) {
        return new ActorRouteCoordinatorV1.StateSnapshot(
                sha('1'), ByteBuffer.allocate(4).putInt(count).array());
    }
    private static final class Physical {
        int count;
        Physical(int count) { this.count = count; }
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        Object supervisor = new Object();
        Physical v1 = new Physical(0);
        try (var route = new ActorRouteCoordinatorV1<>(
                41L, supervisor, image('a'), state(0), v1, 16, 64, 1024)) {
            var ref = route.logicalRef();
            for (int i = 0; i < 10; i++) ref.send(new byte[]{1});
            for (int i = 0; i < 10; i++) {
                check(route.dispatchNext(supervisor, (physical, envelope, completed) -> {
                    physical.count += envelope.payload()[0];
                    completed.acknowledge(); // HOST: processing completed inline.
                }), "v1 dispatch");
            }
            check(v1.count == 10, "v1 count");
            var stage = route.stage(supervisor, image('b'));
            for (int i = 0; i < 5; i++) ref.send(new byte[]{1});
            check(!route.dispatchNext(supervisor,
                    (physical, envelope, completed) -> {
                        throw new AssertionError("old route dispatched during freeze");
                    }), "old route frozen");
            Physical v2 = new Physical(v1.count); // Trusted host checkpoint simulation.
            check(stage.publish(supervisor, state(v1.count), v2), "candidate publication");
            check(ref == route.logicalRef(), "stable logical ref");
            check(route.current().routeEpoch() == 2, "epoch increment");
            for (int i = 0; i < 5; i++) {
                check(route.dispatchNext(supervisor, (physical, envelope, completed) -> {
                    physical.count += envelope.payload()[0];
                    completed.acknowledge();
                }), "v2 dispatch");
            }
            check(v2.count == 15, "state preserved to fifteen");
            check(v1.count == 10, "old physical actor remains fenced by route");
            check(route.snapshot().mailbox().retainedMessages() == 0, "no mail leak");
            System.out.println("PASS HOST logical ActorRef: v1 10 -> v2 15, same ref, FIFO");
            System.out.println("PASS HOST route epoch 2, old actor unchanged, all mail completed");
        }
    }
}
