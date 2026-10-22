package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in host microbenchmark. Run manually after compiling tests.
 *
 * <p>Not a JUnit gate: wall-clock timing and PMU counters on shared runners are
 * not stable enough for a release assertion. Use scripts/measure-dop-cache.sh
 * on pinned/quiescent hardware for per-layout perf counters.</p>
 */
public final class ActorShardLayoutBench {
    private ActorShardLayoutBench() { }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        for (int rows : config.rows()) {
            for (ActorShardColumns.Layout layout : config.layouts()) {
                run(rows, layout, config);
            }
        }
    }

    private static void run(
            int rows, ActorShardColumns.Layout layout, Config config) throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch complete = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<long[]> durations = new AtomicReference<>();
            AtomicReference<Double> checksum = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (ignored, context) -> {
                        try (ActorShardColumns shard = shard(layout, rows)) {
                            for (int i = 0; i < rows; i++) {
                                shard.write(i, 0, i);
                                shard.write(i, 1, 2);
                                shard.write(i, 2, -3);
                                shard.write(i, 3, 7);
                            }
                            for (int w = 0; w < config.warmupIterations(); w++) {
                                shard.integrate(0, 1, 1);
                            }
                            long[] samples = new long[config.samples()];
                            for (int sample = 0; sample < samples.length; sample++) {
                                long begin = System.nanoTime();
                                for (int i = 0; i < config.iterations(); i++) {
                                    shard.integrate(0, 1, 1);
                                }
                                samples[sample] = System.nanoTime() - begin;
                            }
                            double expected = rows - 1
                                    + 2.0 * (config.warmupIterations()
                                            + (long) config.samples() * config.iterations());
                            double actual = shard.read(rows - 1, 0);
                            if (actual != expected
                                    || shard.read(rows - 1, 2) != -3
                                    || shard.read(rows - 1, 3) != 7) {
                                throw new AssertionError(
                                        "invalid benchmark result for " + layout);
                            }
                            durations.set(samples);
                            checksum.set(actual);
                        } catch (Throwable e) {
                            failure.set(e);
                        } finally {
                            complete.countDown();
                        }
                    });
            actor.send(1);
            if (!complete.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("benchmark timed out");
            }
            if (failure.get() != null) throw new RuntimeException(failure.get());

            long[] sorted = durations.get().clone();
            Arrays.sort(sorted);
            long medianNanos = sorted[sorted.length / 2];
            double updates = config.iterations() * (double) rows;
            double nsPerUpdate = medianNanos / updates;
            double millionUpdatesPerSecond = updates / medianNanos * 1_000.0;
            System.out.printf(Locale.ROOT,
                    "DOP_BENCH rows=%d layout=%s lanes=%d group=%d "
                            + "iterations=%d warmup=%d samples=%d "
                            + "median_ns=%d ns_per_row_update=%.3f "
                            + "million_row_updates_per_sec=%.3f checksum=%.1f%n",
                    rows, layout, lanes(layout), groupWidth(layout),
                    config.iterations(), config.warmupIterations(), config.samples(),
                    medianNanos, nsPerUpdate, millionUpdatesPerSecond, checksum.get());
        }
    }

    private static ActorShardColumns shard(
            ActorShardColumns.Layout layout, int rows) {
        return new ActorShardColumns(
                layout, rows, 4, lanes(layout), groupWidth(layout));
    }

    private static int lanes(ActorShardColumns.Layout layout) {
        return layout == ActorShardColumns.Layout.AOSOA ? 8 : 1;
    }

    private static int groupWidth(ActorShardColumns.Layout layout) {
        return layout == ActorShardColumns.Layout.SOAOS ? 2 : 1;
    }

    private record Config(
            int[] rows,
            ActorShardColumns.Layout[] layouts,
            int iterations,
            int warmupIterations,
            int samples) {
        static Config parse(String[] args) {
            int[] rows = {1_024, 16_384, 131_072};
            ActorShardColumns.Layout[] layouts = ActorShardColumns.Layout.values();
            int iterations = 200;
            int warmup = 50;
            int samples = 5;
            for (String arg : args) {
                if (arg.startsWith("--rows=")) {
                    rows = parsePositiveInts(arg.substring("--rows=".length()));
                } else if (arg.startsWith("--layout=")) {
                    String value = arg.substring("--layout=".length());
                    if (!value.equalsIgnoreCase("all")) {
                        layouts = new ActorShardColumns.Layout[]{
                                ActorShardColumns.Layout.valueOf(
                                        value.toUpperCase(Locale.ROOT))};
                    }
                } else if (arg.startsWith("--iterations=")) {
                    iterations = positiveInt(arg, "--iterations=");
                } else if (arg.startsWith("--warmup=")) {
                    warmup = positiveInt(arg, "--warmup=");
                } else if (arg.startsWith("--samples=")) {
                    samples = positiveInt(arg, "--samples=");
                    if ((samples & 1) == 0) {
                        throw new IllegalArgumentException(
                                "samples must be odd so the median is unambiguous");
                    }
                } else {
                    throw new IllegalArgumentException(
                            "unknown benchmark argument: " + arg);
                }
            }
            return new Config(rows, layouts, iterations, warmup, samples);
        }

        private static int[] parsePositiveInts(String value) {
            String[] pieces = value.split(",");
            int[] result = new int[pieces.length];
            if (pieces.length == 0) throw new IllegalArgumentException("rows empty");
            for (int i = 0; i < pieces.length; i++) {
                try {
                    result[i] = Integer.parseInt(pieces[i]);
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("invalid rows", invalid);
                }
                if (result[i] <= 0) throw new IllegalArgumentException("rows must be positive");
            }
            return result;
        }

        private static int positiveInt(String argument, String prefix) {
            try {
                int value = Integer.parseInt(argument.substring(prefix.length()));
                if (value <= 0) throw new IllegalArgumentException(prefix + " must be positive");
                return value;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("invalid numeric benchmark argument", invalid);
            }
        }
    }
}
