package dev.oreslang.runtime;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;

/**
 * Reproducible smoke microbenchmark, NOT a CI performance gate.
 *
 * Run with JDK 25 after test classes are compiled:
 * java -cp target/test-classes:target/classes dev.oreslang.runtime.SelectPlanMicrobenchmark 100000
 *
 * Compares baseline per-iteration arm/SelectSet reconstruction with explicit
 * immutable plans, automatic-static-plan runtime plumbing, and dynamic plans.
 * All paths write/read the same number of scalar values and verify the result.
 * Records GC event counts but does not treat those as per-operation allocation
 * bytes. Run with fixed JVM flags, warmup, CPU affinity, and repeated forks
 * before claiming performance wins.
 */
public final class SelectPlanMicrobenchmark {
    private static volatile long sink;
    private SelectPlanMicrobenchmark() { }

    @FunctionalInterface
    private interface Workload {
        long run(int operations);
    }

    private record Scenario(String name, Workload workload) { }

    private static long gcCollections() {
        long total = 0;
        for (GarbageCollectorMXBean bean :
                ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = bean.getCollectionCount();
            if (value >= 0) total += value;
        }
        return total;
    }

    private static void record(String label, Workload workload, int operations) {
        final int warmups = 4;
        final int samples = 7;
        long[] elapsed = new long[samples];
        long[] gcEvents = new long[samples];

        for (int repeat = -warmups; repeat < samples; repeat++) {
            long gcStart = gcCollections();
            long start = System.nanoTime();
            long checksum = workload.run(operations);
            long nanos = System.nanoTime() - start;
            long collections = gcCollections() - gcStart;
            sink = checksum;
            if (repeat >= 0) {
                elapsed[repeat] = nanos;
                gcEvents[repeat] = collections;
            }
        }
        Arrays.sort(elapsed);
        Arrays.sort(gcEvents);
        double medianNsOp = (double) elapsed[samples / 2] / operations;
        double throughput = 1_000_000_000.0 / medianNsOp;
        System.out.printf(
                "%-25s n=%d median-ns/op=%.1f ops/s=%.0f median-GC-events=%d checksum=%d%n",
                label, operations, medianNsOp, throughput,
                gcEvents[samples / 2], sink);
    }

    private static long reconstructEveryIteration(int operations) {
        var inbox = new ChannelRuntime.Channel<Integer>(1);
        long checksum = 0L;
        for (int i = 0; i < operations; i++) {
            if (!inbox.tryWrite(i)) throw new AssertionError("write failed");
            var set = new ChannelRuntime.SelectSet(
                    List.of(ChannelRuntime.read(inbox)), i);
            var result = set.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
            checksum += (Integer) result.value();
        }
        return checksum;
    }

    private static long immutableReusablePlan(int operations) {
        var inbox = new ChannelRuntime.Channel<Integer>(1);
        var plan = ChannelRuntime.SelectPlan.of(ChannelRuntime.read(inbox));
        long checksum = 0L;
        for (int i = 0; i < operations; i++) {
            if (!inbox.tryWrite(i)) throw new AssertionError("write failed");
            var result = plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
            checksum += (Integer) result.value();
        }
        return checksum;
    }

    private static long compilerCachedStaticPlan(int operations) {
        var inbox = new ChannelRuntime.Channel<Integer>(1);
        var set = ChannelRuntime.SelectSet.of(ChannelRuntime.read(inbox));
        long checksum = 0L;
        for (int i = 0; i < operations; i++) {
            if (!inbox.tryWrite(i)) throw new AssertionError("write failed");
            var result = set.tryStaticSelect(ChannelRuntime.SelectPolicy.FAIR, i).orElseThrow();
            checksum += (Integer) result.value();
        }
        return checksum;
    }

    private static long stableDynamicPlan(int operations) {
        var inbox = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("inbox", ChannelRuntime.read(inbox));
        long checksum = 0L;
        for (int i = 0; i < operations; i++) {
            if (!inbox.tryWrite(i)) throw new AssertionError("write failed");
            var result = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
            if (!"inbox".equals(result.key())) throw new AssertionError("wrong key");
            checksum += (Integer) result.selection().value();
        }
        return checksum;
    }

    public static void main(String[] args) {
        int operations = args.length == 0 ? 100_000 : Integer.parseInt(args[0]);
        if (operations < 100 || operations > 2_000_000) {
            throw new IllegalArgumentException("operation count must be 100..2000000");
        }
        long expected = (long) operations * (operations - 1L) / 2L;
        Scenario[] scenarios = {
            new Scenario("rebuild-every-iteration", SelectPlanMicrobenchmark::reconstructEveryIteration),
            new Scenario("explicit-select-plan", SelectPlanMicrobenchmark::immutableReusablePlan),
            new Scenario("compiler-static-cache", SelectPlanMicrobenchmark::compilerCachedStaticPlan),
            new Scenario("dynamic-keyed-plan", SelectPlanMicrobenchmark::stableDynamicPlan)
        };
        for (Scenario scenario : scenarios) {
            long got = scenario.workload().run(operations);
            if (got != expected) {
                throw new AssertionError(scenario.name()
                        + ": expected checksum " + expected + " got " + got);
            }
        }
        for (Scenario scenario : scenarios) {
            record(scenario.name(), scenario.workload(), operations);
        }
    }
}
