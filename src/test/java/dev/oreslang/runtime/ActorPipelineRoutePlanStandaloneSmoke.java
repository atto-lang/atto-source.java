package dev.oreslang.runtime;

import java.util.ArrayList;
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

        System.out.println("ORES_ACTOR_PIPELINE_ROUTING_HOST_OK"
                + " cases=12 concurrent_selections=" + (writers * sendsEach));
    }
}
