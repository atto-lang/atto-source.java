package dev.oreslang.runtime;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** No Maven/Graal dependency: executable deterministic host routing proof. */
public final class ActorPipelineRoutePlanStandaloneSmoke {
    private ActorPipelineRoutePlanStandaloneSmoke() { }

    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }

    private static void rejects(Runnable operation, String why) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(why);
    }

    private static void rejectsMutation(Runnable operation, String why) {
        try { operation.run(); }
        catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError(why);
    }

    private static ActorPipelineRoutePlan makePlan() {
        var stages = List.of(
                new ActorPipelineRoutePlan.Stage(
                        "producer-1", "Producer", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                new ActorPipelineRoutePlan.Stage(
                        "worker-a", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                new ActorPipelineRoutePlan.Stage(
                        "worker-b", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""));
        var edges = List.of(
                new ActorPipelineRoutePlan.Edge("producer-1", "worker-a", "Event"),
                new ActorPipelineRoutePlan.Edge("producer-1", "worker-b", "Event"));
        return new ActorPipelineRoutePlan(stages, edges);
    }

    public static void main(String[] args) throws InterruptedException {
        ActorPipelineRoutePlan plan = makePlan();

        // A List's Count/size is NOT a bound on its iterator. The trusted
        // graph parser must read only each indexed slot, once, and must not
        // revisit caller-owned lists after taking its immutable snapshot.
        var indexedStages = List.of(
                new ActorPipelineRoutePlan.Stage("index-source", "Producer",
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                new ActorPipelineRoutePlan.Stage("index-sink", "Worker",
                        ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""));
        var indexedEdges = List.of(
                new ActorPipelineRoutePlan.Edge("index-source", "index-sink", "Event"));
        AtomicInteger stageReads = new AtomicInteger();
        AtomicInteger edgeReads = new AtomicInteger();
        List<ActorPipelineRoutePlan.Stage> hostileStages = new AbstractList<>() {
            @Override public int size() { return indexedStages.size(); }
            @Override public ActorPipelineRoutePlan.Stage get(int index) {
                stageReads.incrementAndGet();
                return indexedStages.get(index);
            }
            @Override public Iterator<ActorPipelineRoutePlan.Stage> iterator() {
                throw new AssertionError("actor DAG stages unexpectedly iterated");
            }
        };
        List<ActorPipelineRoutePlan.Edge> hostileEdges = new AbstractList<>() {
            @Override public int size() { return indexedEdges.size(); }
            @Override public ActorPipelineRoutePlan.Edge get(int index) {
                edgeReads.incrementAndGet();
                return indexedEdges.get(index);
            }
            @Override public Iterator<ActorPipelineRoutePlan.Edge> iterator() {
                throw new AssertionError("actor DAG edges unexpectedly iterated");
            }
        };
        var bounded = new ActorPipelineRoutePlan(hostileStages, hostileEdges);
        check(bounded.forward("index-source").target().equals("index-sink"),
                "bounded index-only graph snapshot chose wrong destination");
        check(stageReads.get() == 2 && edgeReads.get() == 1,
                "actor DAG read caller-owned slot more than once");
        // The same 3-consumer selector fixtures are also used by .net,
        // .wasm and .forge; this is a host-only selector conformance test.
        var three = new ActorPipelineRoutePlan(
                List.of(
                    new ActorPipelineRoutePlan.Stage("source", "Producer",
                            ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                    new ActorPipelineRoutePlan.Stage("worker-a", "Worker",
                            ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                    new ActorPipelineRoutePlan.Stage("worker-b", "Worker",
                            ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                    new ActorPipelineRoutePlan.Stage("worker-c", "Worker",
                            ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                List.of(
                    new ActorPipelineRoutePlan.Edge("source", "worker-a", "Event"),
                    new ActorPipelineRoutePlan.Edge("source", "worker-b", "Event"),
                    new ActorPipelineRoutePlan.Edge("source", "worker-c", "Event")));
        String[] fixtureKeys = {"customer-7", "alpha", "beta", "🌍", "", "\0"};
        String[] fixtureTargets = {"worker-a", "worker-a", "worker-c", "worker-a", "worker-c", "worker-a"};
        for (int i = 0; i < fixtureKeys.length; i++) {
            check(three.forwardByKey("source", fixtureKeys[i]).target().equals(fixtureTargets[i]),
                    "five-backend canonical FNV-1a64 vector mismatch");
        }
        for (int i = 0; i < 999; i++) {
            String expected = new String[]{"worker-a", "worker-b", "worker-c"}[i % 3];
            check(three.forwardRoundRobin("source").target().equals(expected),
                    "five-backend three-consumer round robin mismatch");
        }
        check(three.forwardAll("source").size() == 3,
                "five-backend three-consumer fanout changed");
        check(plan.forwardAll("producer-1").size() == 2, "fan-out omitted a consumer");
        check(plan.forwardAll("producer-1") == plan.forwardAll("producer-1"),
                "fan-out results must retain stable immutable edge order");
        rejectsMutation(() -> plan.forwardAll("producer-1").clear(),
                "fan-out graph must be immutable");
        rejects(() -> plan.forward("producer-1"), "ambiguous forwarding must fail");
        rejects(() -> plan.forwardAll("worker-a"), "sink has no outbound authority");
        rejects(() -> plan.forwardTo("worker-a", "worker-b"),
                "sink cannot masquerade as a source");
        rejects(() -> plan.forwardTo("producer-1", "arbitrary-worker"),
                "unconnected receiver must be rejected");
        check(plan.forwardTo("producer-1", "worker-b").target().equals("worker-b"),
                "named routing selected wrong destination");

        for (int i = 0; i < 1000; ++i) {
            String expected = i % 2 == 0 ? "worker-a" : "worker-b";
            check(plan.forwardRoundRobin("producer-1").target().equals(expected),
                    "round-robin fairness failed");
        }

        final String key = "partition-🌍";
        String stable = plan.forwardByKey("producer-1", key).target();
        for (int i = 0; i < 100; i++) {
            check(plan.forwardByKey("producer-1", key).target().equals(stable),
                    "key affinity changed without graph reconfiguration");
        }
        // Independent FNV-1a 64-bit known-slot vectors shared with LLVM.
        // Producer edges are ordered [worker-a, worker-b].
        check(plan.forwardByKey("producer-1", "partition-🌍").target().equals("worker-a"),
                "UTF-8 partition Unicode fixture changed hash slot");
        check(plan.forwardByKey("producer-1", "café").target().equals("worker-b"),
                "UTF-8 accented Unicode fixture changed hash slot");
        check(plan.forwardByKey("producer-1", "device-42").target().equals("worker-a"),
                "ASCII partition fixture changed hash slot");
        rejects(() -> plan.forwardByKey("producer-1", String.valueOf((char) 0xD800)),
                "unpaired surrogate must fail strict UTF-8 encoding");
        rejects(() -> plan.forwardByKey("producer-1", "x".repeat(4097)),
                "oversized affinity key must fail");
        rejects(() -> plan.forwardByKey("worker-b", "valid"),
                "key cannot authorize unconnected source");

        var wrong = new ArrayList<>(plan.edges());
        wrong.add(wrong.getFirst());
        rejects(() -> new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Stage(
                        "producer-1", "Producer", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                new ActorPipelineRoutePlan.Stage(
                        "worker-a", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                new ActorPipelineRoutePlan.Stage(
                        "worker-b", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")), wrong),
                "duplicate edge should fail");

        // Cross-backend canonical graph strings use strict UTF-8 byte budgets,
        // not Java UTF-16 code-unit lengths or replacement characters.
        var source = new ActorPipelineRoutePlan.Stage(
                "source", "Source", ActorPipelineRoutePlan.Role.READABLE, "", "Event");
        var sink = new ActorPipelineRoutePlan.Stage(
                "sink", "Sink", ActorPipelineRoutePlan.Role.WRITABLE, "Event", "");
        var simpleEdge = new ActorPipelineRoutePlan.Edge("source", "sink", "Event");
        rejects(() -> new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Stage("é".repeat(129), "Source",
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event"), sink),
                List.of(simpleEdge)), "UTF-8 multibyte instance ID exceeded 256 bytes");
        rejects(() -> new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Stage("source", "é".repeat(129),
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event"), sink),
                List.of(simpleEdge)), "UTF-8 multibyte nominal type exceeded 256 bytes");
        rejects(() -> new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Stage("source", "Source",
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event".repeat(52)), sink),
                List.of(simpleEdge)), "oversized output port tag accepted");
        rejects(() -> new ActorPipelineRoutePlan(List.of(source,
                new ActorPipelineRoutePlan.Stage("sink", "Sink",
                        ActorPipelineRoutePlan.Role.WRITABLE, "Event".repeat(52), "")),
                List.of(simpleEdge)), "oversized input port tag accepted");
        rejects(() -> new ActorPipelineRoutePlan(List.of(source, sink), List.of(
                new ActorPipelineRoutePlan.Edge("source", "sink", "E\\0vent"))),
                "NUL in link payload type accepted");
        rejects(() -> new ActorPipelineRoutePlan(List.of(source, sink), List.of(
                new ActorPipelineRoutePlan.Edge("source\\0alias", "sink", "Event"))),
                "NUL in link source accepted");
        rejects(() -> new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Stage("source", "Source",
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                new ActorPipelineRoutePlan.Stage("sink", "Sink",
                        ActorPipelineRoutePlan.Role.WRITABLE, "\\uD800", "")),
                List.of(simpleEdge)), "unpaired surrogate in port type accepted");
        rejects(() -> new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Stage("source\\0alias", "Source",
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event"), sink),
                List.of(simpleEdge)), "NUL in actor instance ID accepted");

        var cycle = List.of(
                new ActorPipelineRoutePlan.Stage(
                        "a", "A", ActorPipelineRoutePlan.Role.TRANSFORM, "E", "E"),
                new ActorPipelineRoutePlan.Stage(
                        "b", "B", ActorPipelineRoutePlan.Role.TRANSFORM, "E", "E"));
        rejects(() -> new ActorPipelineRoutePlan(cycle, List.of(
                new ActorPipelineRoutePlan.Edge("a", "b", "E"),
                new ActorPipelineRoutePlan.Edge("b", "a", "E"))),
                "a real two-node cycle must fail");

        rejects(() -> new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Stage(
                        "source", "Source", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                new ActorPipelineRoutePlan.Stage(
                        "target", "Target", ActorPipelineRoutePlan.Role.WRITABLE, "Wrong", "")),
                List.of(new ActorPipelineRoutePlan.Edge("source", "target", "Event"))),
                "nominal payload mismatch admitted");

        // Selector state remains fair even when multiple host threads attempt
        // routes concurrently. This is not a claim of parallel guest execution.
        var concurrent = makePlan();
        AtomicIntegerArray counts = new AtomicIntegerArray(2);
        int writers = 8;
        int sendsEach = 500;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[writers];
        for (int i = 0; i < writers; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < sendsEach; n++) {
                        String chosen = concurrent.forwardRoundRobin("producer-1").target();
                        counts.incrementAndGet(chosen.equals("worker-a") ? 0 : 1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        check(counts.get(0) == writers * sendsEach / 2
                        && counts.get(1) == writers * sendsEach / 2,
                "concurrent round-robin lost selections");

        // Shared canonical v1 selector fixture: one producer, canonicalThree
        // independently owned Worker instances of the same nominal type.
        var canonicalThree = new ActorPipelineRoutePlan(
                List.of(
                        new ActorPipelineRoutePlan.Stage(
                                "producer-1", "Producer", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                        new ActorPipelineRoutePlan.Stage(
                                "worker-a", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                        new ActorPipelineRoutePlan.Stage(
                                "worker-b", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                        new ActorPipelineRoutePlan.Stage(
                                "worker-c", "Worker", ActorPipelineRoutePlan.Role.WRITABLE, "Event", "")),
                List.of(
                        new ActorPipelineRoutePlan.Edge("producer-1", "worker-a", "Event"),
                        new ActorPipelineRoutePlan.Edge("producer-1", "worker-b", "Event"),
                        new ActorPipelineRoutePlan.Edge("producer-1", "worker-c", "Event")));
        check(canonicalThree.forwardAll("producer-1").stream()
                .map(ActorPipelineRoutePlan.Edge::target).toList()
                .equals(List.of("worker-a", "worker-b", "worker-c")),
                "canonicalThree-consumer fan-out publication order");
        for (int i = 0; i < 6; ++i) {
            check(canonicalThree.forwardRoundRobin("producer-1").target().equals(
                    List.of("worker-a", "worker-b", "worker-c").get(i % 3)),
                    "canonical RR sequence differs from other backends");
        }
        // Long-sequence fairness from the exact v1 three-consumer graph;
        // the source-side cursor wraps without choosing a different endpoint.
        for (int i = 0; i < 12000; ++i) {
            String expected = List.of("worker-a", "worker-b", "worker-c").get(i % 3);
            check(canonicalThree.forwardRoundRobin("producer-1").target().equals(expected),
                    "12k canonical round-robin parity/fairness regression");
        }
        String[] keys = {"customer-7", "alpha", "beta", "🌍", "",
                String.valueOf((char) 0)};
        String[] destinations = {"worker-a", "worker-a", "worker-c", "worker-a", "worker-c", "worker-a"};
        for (int i = 0; i < keys.length; i++) {
            check(canonicalThree.forwardByKey("producer-1", keys[i]).target()
                    .equals(destinations[i]),
                    "canonical FNV-1a64 key destination disagrees across backends");
        }
        check(canonicalThree.forwardTo("producer-1", "worker-b").target().equals("worker-b"),
                "canonicalThree-consumer targeted route mismatch");
        rejects(() -> canonicalThree.forward("producer-1"),
                "multi-consumer forward silently picked a target");
        rejects(() -> canonicalThree.forwardTo("producer-1", "worker-z"),
                "unknown canonical destination was accepted");
        // Deny an unauthorised source before attempting Unicode/key parsing:
        // callers must not learn key parsing behavior through unknown edges.
        try {
            canonicalThree.forwardByKey("unconnected-source",
                    String.valueOf((char) 0xD800));
            throw new AssertionError("unknown keyed route admitted");
        } catch (IllegalArgumentException rejectedSource) {
            check(rejectedSource.getMessage().contains("source has no connected"),
                    "key validation ran before source routing admission");
        }
        var identityStages = List.of(
                new ActorPipelineRoutePlan.Stage(
                        "a:b", "Producer", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                new ActorPipelineRoutePlan.Stage(
                        "a", "Producer", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                new ActorPipelineRoutePlan.Stage(
                        "c", "Sink", ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""),
                new ActorPipelineRoutePlan.Stage(
                        "b:c", "Sink", ActorPipelineRoutePlan.Role.WRITABLE, "Event", ""));
        var identityEdges = List.of(
                new ActorPipelineRoutePlan.Edge("a:b", "c", "Event"),
                new ActorPipelineRoutePlan.Edge("a", "b:c", "Event"));
        var identityPlan = new ActorPipelineRoutePlan(identityStages, identityEdges);
        check(identityPlan.forward("a:b").target().equals("c")
                        && identityPlan.forward("a").target().equals("b:c"),
                "delimiter-bearing endpoint tuples collided");
        rejects(() -> new ActorPipelineRoutePlan(identityStages,
                List.of(identityEdges.get(0), identityEdges.get(1), identityEdges.get(0))),
                "duplicated endpoint pair accepted");
        var composed = List.of(
                new ActorPipelineRoutePlan.Stage(
                        "é", "P", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                new ActorPipelineRoutePlan.Stage(
                        "e\u0301", "P", ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                identityStages.get(2), identityStages.get(3));
        var composedEdges = List.of(
                new ActorPipelineRoutePlan.Edge("é", "c", "Event"),
                new ActorPipelineRoutePlan.Edge("e\u0301", "b:c", "Event"));
        var normalized = new ActorPipelineRoutePlan(composed, composedEdges);
        check(normalized.forward("é").target().equals("c")
                        && normalized.forward("e\u0301").target().equals("b:c"),
                "actor instance keys were incorrectly Unicode-normalized");
        rejects(() -> new ActorPipelineRoutePlan(
                java.util.Collections.nCopies(1025, identityStages.getFirst()),
                identityEdges), "vertex budget bypassed");
        rejects(() -> new ActorPipelineRoutePlan(identityStages,
                java.util.Collections.nCopies(4097, identityEdges.getFirst())),
                "edge budget bypassed");
        String exact256 = "🌍".repeat(64);
        var boundStages = List.of(
                new ActorPipelineRoutePlan.Stage(exact256, "P",
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                identityStages.get(1), identityStages.get(2), identityStages.get(3));
        var boundEdges = List.of(
                new ActorPipelineRoutePlan.Edge(exact256, "c", "Event"),
                identityEdges.get(1));
        check(new ActorPipelineRoutePlan(boundStages, boundEdges)
                .forward(exact256).target().equals("c"),
                "exactly 256-byte UTF-8 actor ID rejected");
        rejects(() -> new ActorPipelineRoutePlan(List.of(
                new ActorPipelineRoutePlan.Stage(exact256 + "🌍", "P",
                        ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                identityStages.get(1), identityStages.get(2), identityStages.get(3)),
                boundEdges), "257+ byte actor ID admitted");

        // Declaring an outbound type does not make a terminal transform
        // executable: it must have a real downstream edge.
        rejects(() -> new ActorPipelineRoutePlan(
                List.of(
                        new ActorPipelineRoutePlan.Stage("src", "Producer",
                                ActorPipelineRoutePlan.Role.READABLE, "", "Event"),
                        new ActorPipelineRoutePlan.Stage("worker", "Worker",
                                ActorPipelineRoutePlan.Role.TRANSFORM, "Event", "Event")),
                List.of(new ActorPipelineRoutePlan.Edge("src", "worker", "Event"))),
                "terminal transform without outbound connection accepted");
        System.out.println("ORES_ACTOR_PIPELINE_ROUTING_HOST_OK"
                + " cases=24 concurrent_selections=" + (writers * sendsEach));
    }
}
