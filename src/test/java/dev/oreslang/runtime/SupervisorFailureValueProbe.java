package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Zero-dependency, source-local host-value probe. This is intentionally
 * runnable without Maven or GraalVM when GitHub Actions runners are not
 * admitted. It is NOT source-compiled .atto guest/CONTROL delivery evidence.
 */
public final class SupervisorFailureValueProbe {
    private SupervisorFailureValueProbe() { }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private static ActorFailureTrace.Snapshot snapshot(
            UUID actor, UUID supervisor, long generation) {
        return ActorFailureTrace.capture(
                ActorFailureTrace.FailureKind.UNCLASSIFIED,
                actor, generation, List.of(), List.of(supervisor), false, true);
    }

    private static void rejectTopology(
            List<ActorFailureTrace.Boundary> boundaries,
            boolean associationKnown) {
        try {
            new ActorFailureTrace.Snapshot(
                    ActorFailureTrace.FailureKind.UNCLASSIFIED,
                    boundaries, false, associationKnown);
            throw new AssertionError("invalid diagnostic topology was accepted");
        } catch (IllegalArgumentException expected) {
            // Fail-closed topology validation.
        }
    }

    public static void main(String[] args) throws Exception {
        UUID actor = UUID.randomUUID();
        UUID supervisor = UUID.randomUUID();
        var root = new ActorFailureTrace.Boundary(
                ActorFailureTrace.BoundaryKind.ACTOR_TERMINAL,
                actor.toString(), 7);
        var parent = new ActorFailureTrace.Boundary(
                ActorFailureTrace.BoundaryKind.PARENT_ACTOR,
                UUID.randomUUID().toString(), 0);
        var direct = new ActorFailureTrace.Boundary(
                ActorFailureTrace.BoundaryKind.SUPERVISOR_ASSOCIATION,
                supervisor.toString(), 0);
        rejectTopology(List.of(root, root), false);
        rejectTopology(List.of(root, parent, parent), false);
        rejectTopology(List.of(root, direct, parent), true);
        rejectTopology(List.of(root, direct), false);
        rejectTopology(List.of(root), true);

        var mutable = new ArrayList<>(List.of(root, parent, direct));
        var value = new ActorFailureTrace.Snapshot(
                ActorFailureTrace.FailureKind.UNCLASSIFIED,
                mutable, true, true);
        mutable.clear();
        require(value.boundaries().size() == 3,
                "detached snapshot changed when input collection mutated");
        require(value.truncated(), "explicit truncation was lost");
        require(!value.sourceFramesAvailable(),
                "Java host frames cannot masquerade as guest source frames");

        var enormous = new ArrayList<ActorFailureTrace.Boundary>();
        for (int i = 0; i < 100_000; i++) enormous.add(root);
        for (int i = 0; i < 1_000; i++) rejectTopology(enormous, false);

        var inbox = new SupervisorFailureInbox(supervisor, 1);
        ExecutorService publishers = Executors.newFixedThreadPool(24);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                UUID next = UUID.randomUUID();
                var terminal = snapshot(next, supervisor, iteration + 1L);
                List<Future<Boolean>> offers = new ArrayList<>();
                for (int publisher = 0; publisher < 24; publisher++) {
                    offers.add(publishers.submit(() -> inbox.offerTrusted(terminal)));
                }
                for (Future<Boolean> offered : offers) {
                    require(offered.get(10, TimeUnit.SECONDS),
                            "duplicate terminal failure denied");
                }
                require(inbox.outstandingCount() == 1
                                && inbox.queuedCount() == 1,
                        "more than one terminal receipt admitted");
                require(!inbox.offerTrusted(snapshot(
                                UUID.randomUUID(), supervisor, 1)),
                        "quota overflow silently evicted active receipt");

                var first = inbox.pollHost().orElseThrow();
                require(first.claimAttempt() == 1,
                        "first claim epoch is not 1");
                require(inbox.requeueUnacknowledgedHost() == 1,
                        "unacknowledged claim was not requeued");
                require(inbox.requeueUnacknowledgedHost() == 0,
                        "duplicate requeue created a second candidate");
                var retry = inbox.pollHost().orElseThrow();
                require(retry.sequence() == first.sequence()
                                && retry.claimAttempt() == 2,
                        "replay changed sequence or failed to advance epoch");
                require(!inbox.acknowledgeHost(first.sequence(),
                                next.toString(), iteration + 1L,
                                first.claimAttempt()),
                        "stale coordinator claim acknowledged new attempt");
                require(inbox.acknowledgeHost(retry.sequence(),
                                next.toString(), iteration + 1L,
                                retry.claimAttempt()),
                        "current claim acknowledgement denied");
                require(!inbox.acknowledgeHost(retry.sequence(),
                                next.toString(), iteration + 1L,
                                retry.claimAttempt()),
                        "duplicate acknowledgement accepted");
            }
        } finally {
            publishers.shutdownNow();
            require(publishers.awaitTermination(10, TimeUnit.SECONDS),
                    "stress publishers did not terminate");
        }
        require(inbox.outstandingCount() == 0,
                "host receipt quota leaked after all acknowledgements");
        require(!new SupervisorFailureInbox(UUID.randomUUID(), 1)
                        .offerTrusted(snapshot(actor, supervisor, 1)),
                "unrelated supervisor accepted direct supervisor receipt");
        System.out.println("PASS supervisor-failure-value: 2,400 concurrent offers,"
                + " 1,000 oversized snapshots, replay/epoch/quota/topology");
    }
}
