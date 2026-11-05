package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Deterministic, zero-dependency differential receipt state-machine probe.
 * Host-only. This is not an AttoLang guest test or a CONTROL execution test.
 */
public final class InboxModelFuzz {
    private InboxModelFuzz() { }

    private static void require(boolean condition, String context) {
        if (!condition) throw new AssertionError(context);
    }

    private record State(UUID actor, long generation, long attempt, boolean claimed) { }

    public static void main(String[] args) {
        final int seeds = 50;
        final int operations = 4_000;
        final int capacity = 8;

        for (int seed = 0; seed < seeds; seed++) {
            SplittableRandom rng = new SplittableRandom(seed);
            UUID supervisor = new UUID(rng.nextLong(), rng.nextLong());
            SupervisorFailureInbox inbox = new SupervisorFailureInbox(supervisor, capacity);
            Map<Long, State> active = new HashMap<>();
            ArrayDeque<Long> queued = new ArrayDeque<>();
            long next = 1;

            for (int iteration = 0; iteration < operations; iteration++) {
                String where = "seed=" + seed + " iteration=" + iteration;
                int command = rng.nextInt(100);
                if (command < 35) {
                    UUID actor = new UUID(rng.nextLong(), rng.nextLong());
                    long generation = 1 + rng.nextInt(400);
                    if (!active.isEmpty() && rng.nextBoolean()) {
                        long sequence = new ArrayList<>(active.keySet())
                                .get(rng.nextInt(active.size()));
                        State previous = active.get(sequence);
                        actor = previous.actor();
                        generation = previous.generation();
                    }
                    boolean prior = false;
                    for (State state : active.values()) {
                        if (state.actor().equals(actor)
                                && state.generation() == generation) prior = true;
                    }
                    boolean expected = prior || active.size() < capacity;
                    boolean actual = inbox.offerTrusted(ActorFailureTrace.capture(
                            ActorFailureTrace.FailureKind.UNCLASSIFIED,
                            actor, generation, List.of(), List.of(supervisor), false, true));
                    require(actual == expected, "offer " + where);
                    if (actual && !prior) {
                        active.put(next, new State(actor, generation, 0, false));
                        queued.addLast(next++);
                    }
                } else if (command < 68) {
                    var actual = inbox.pollHost();
                    if (queued.isEmpty()) {
                        require(actual.isEmpty(), "unexpected poll " + where);
                    } else {
                        long sequence = queued.removeFirst();
                        State state = active.get(sequence);
                        require(actual.isPresent(), "missed receipt " + where);
                        var receipt = actual.orElseThrow();
                        require(receipt.sequence() == sequence
                                        && receipt.claimAttempt() == state.attempt() + 1
                                        && receipt.actorIdentity().equals(state.actor().toString())
                                        && receipt.actorGeneration() == state.generation(),
                                "receipt mismatch " + where);
                        active.put(sequence, new State(
                                state.actor(), state.generation(), state.attempt() + 1, true));
                    }
                } else if (command < 84) {
                    long sequence = active.isEmpty() ? -1
                            : new ArrayList<>(active.keySet()).get(rng.nextInt(active.size()));
                    State state = active.get(sequence);
                    boolean valid = state != null && state.claimed() && rng.nextInt(5) != 0;
                    boolean actual = inbox.acknowledgeHost(sequence,
                            state == null ? UUID.randomUUID().toString()
                                    : state.actor().toString(),
                            state == null ? 1 : state.generation(),
                            state == null ? 1
                                    : valid ? state.attempt() : state.attempt() + 1);
                    require(actual == valid, "ack " + where);
                    if (actual) active.remove(sequence);
                } else {
                    var claims = new ArrayList<Long>();
                    for (var entry : active.entrySet()) {
                        if (entry.getValue().claimed()) claims.add(entry.getKey());
                    }
                    claims.sort(Long::compareTo);
                    int actual = inbox.requeueUnacknowledgedHost();
                    require(actual == claims.size(), "retry count " + where);
                    var nextQueued = new ArrayDeque<Long>();
                    for (long sequence : claims) {
                        State state = active.get(sequence);
                        active.put(sequence, new State(
                                state.actor(), state.generation(), state.attempt(), false));
                        nextQueued.addLast(sequence);
                    }
                    nextQueued.addAll(queued);
                    queued = nextQueued;
                }
                require(inbox.outstandingCount() == active.size(),
                        "outstanding count " + where);
                require(inbox.queuedCount() == queued.size(), "queued count " + where);
            }
        }
        System.out.println("PASS host-only differential inbox model: "
                + seeds + " seeds, " + (seeds * operations) + " operations");
    }
}
