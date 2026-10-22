package dev.oreslang.runtime;

/**
 * Informal JDK layout microbenchmark: metadata walks ONLY, not JMH, guest
 * recovery, end-to-end latency, or Native Image performance.
 *
 * <p>All layouts execute an identical reverse scan with an effect mask and
 * target-id read. Run with an appropriately warm JVM and repeat in randomized
 * order before using results to change the production representation.
 */
public final class RecoveryLayoutBenchmark {
    private static volatile long observed;
    private static final int BLOCK = 16;
    private record Target(int id) { }
    private record Entry(byte mask, Target target) { }
    private record Payload(Target target) { }
    private record Chunk(byte[] masks, Target[] targets) { }
    private interface Layout { long walk(int dispatchMask); }

    private static Layout aos(int n) {
        Entry[] a = new Entry[n];
        for (int i = 0; i < n; i++) {
            a[i] = new Entry((byte) ((i % 3 == 0) ? 1 : 2), new Target(i + 1));
        }
        return request -> {
            long sum = 0;
            for (int i = a.length - 1; i >= 0; i--) {
                if ((a[i].mask() & request) != 0) sum += a[i].target().id();
            }
            return sum;
        };
    }

    private static Layout soa(int n) {
        byte[] masks = new byte[n];
        Target[] targets = new Target[n];
        for (int i = 0; i < n; i++) {
            masks[i] = (byte) ((i % 3 == 0) ? 1 : 2);
            targets[i] = new Target(i + 1);
        }
        return request -> {
            long sum = 0;
            for (int i = n - 1; i >= 0; i--) {
                if ((masks[i] & request) != 0) sum += targets[i].id();
            }
            return sum;
        };
    }

    private static Layout aosoa(int n) {
        Chunk[] chunks = new Chunk[(n + BLOCK - 1) / BLOCK];
        for (int b = 0; b < chunks.length; b++) {
            int length = Math.min(BLOCK, n - b * BLOCK);
            byte[] masks = new byte[length];
            Target[] targets = new Target[length];
            for (int j = 0; j < length; j++) {
                int i = b * BLOCK + j;
                masks[j] = (byte) ((i % 3 == 0) ? 1 : 2);
                targets[j] = new Target(i + 1);
            }
            chunks[b] = new Chunk(masks, targets);
        }
        return request -> {
            long sum = 0;
            for (int b = chunks.length - 1; b >= 0; b--) {
                Chunk c = chunks[b];
                byte[] masks = c.masks();
                Target[] targets = c.targets();
                for (int j = masks.length - 1; j >= 0; j--) {
                    if ((masks[j] & request) != 0) sum += targets[j].id();
                }
            }
            return sum;
        };
    }

    private static Layout soaos(int n) {
        byte[] masks = new byte[n];
        Payload[] payloads = new Payload[n];
        for (int i = 0; i < n; i++) {
            masks[i] = (byte) ((i % 3 == 0) ? 1 : 2);
            payloads[i] = new Payload(new Target(i + 1));
        }
        return request -> {
            long sum = 0;
            for (int i = n - 1; i >= 0; i--) {
                if ((masks[i] & request) != 0) sum += payloads[i].target().id();
            }
            return sum;
        };
    }

    private static Layout ecs(int n) {
        byte[] masks = new byte[n];
        int[] entities = new int[n];
        Target[] denseTargets = new Target[n];
        for (int i = 0; i < n; i++) {
            masks[i] = (byte) ((i % 3 == 0) ? 1 : 2);
            entities[i] = n - 1 - i;
            denseTargets[n - 1 - i] = new Target(i + 1);
        }
        return request -> {
            long sum = 0;
            for (int i = n - 1; i >= 0; i--) {
                if ((masks[i] & request) != 0) {
                    sum += denseTargets[entities[i]].id();
                }
            }
            return sum;
        };
    }

    private static long run(Layout layout, int repetitions) {
        long sum = 0;
        for (int i = 0; i < repetitions; i++) {
            sum += layout.walk((i & 1) == 0 ? 1 : 2);
        }
        observed = sum;
        return sum;
    }

    private static int[] shuffledOrder(int n, java.util.SplittableRandom random) {
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int chosen = random.nextInt(i + 1);
            int temporary = indices[i];
            indices[i] = indices[chosen];
            indices[chosen] = temporary;
        }
        return indices;
    }

    public static void main(String[] args) {
        // Deterministic permutation seed for reproducibility. Still NOT JMH:
        // compare this with forked JVMs and performance counters before a
        // code-layout decision.
        java.util.SplittableRandom random = new java.util.SplittableRandom(0x0E5A1234L);
        for (int n : new int[] {4, 8, 64, 256}) {
            Layout[] layouts = {aos(n), soa(n), aosoa(n), soaos(n), ecs(n)};
            String[] names = {"AoS", "SoA", "AoSoA-16", "SoAoS", "ECS-indirect"};
            int repetitions = Math.max(120_000, 5_000_000 / n);
            long expected = run(layouts[0], 128);
            for (Layout layout : layouts) {
                if (run(layout, 128) != expected) {
                    throw new AssertionError("layout mismatch");
                }
            }
            for (int warmup = 0; warmup < 3; warmup++) {
                for (int i : shuffledOrder(layouts.length, random)) {
                    run(layouts[i], repetitions / 2);
                }
            }
            long expectedTotal = run(layouts[0], repetitions);
            double[][] samples = new double[layouts.length][5];
            for (int trial = 0; trial < 5; trial++) {
                for (int i : shuffledOrder(layouts.length, random)) {
                    long start = System.nanoTime();
                    long actual = run(layouts[i], repetitions);
                    if (actual != expectedTotal) {
                        throw new AssertionError("checksum drift");
                    }
                    samples[i][trial] = (System.nanoTime() - start) / (double) repetitions;
                }
            }
            for (int i = 0; i < layouts.length; i++) {
                java.util.Arrays.sort(samples[i]);
                System.out.printf("depth=%d %-13s %.2f ns/scan (median of 5)%n",
                        n, names[i], samples[i][2]);
            }
        }
    }
}
