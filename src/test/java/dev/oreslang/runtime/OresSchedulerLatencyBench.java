package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Opt-in OresScheduler latency/throughput microbenchmark with JVM floors.
 * Not a JUnit gate: timing on shared CI runners is not reliable.
 *
 * <pre>
 * mvn -o -DskipTests test-compile
 * java -cp target/classes:target/test-classes:$(cat cp.txt) \
 *     dev.oreslang.runtime.OresSchedulerLatencyBench [--hops N] [--smoke]
 * </pre>
 *
 * Floors (no scheduler):
 *   floor_park  two platform threads ping-pong via LockSupport park/unpark:
 *               one kernel sleep + wake per hop.
 *   floor_spin  two platform threads ping-pong via one volatile field.
 *
 * Scheduler:
 *   sched_pingpong  two Tasks on one scheduler alternately complete each
 *                   other's await Future. Each hop is measured from
 *                   complete() to the start of the resumed Task turn.
 *   sched_cooperate N Tasks each yield K times (rt cooperate); turns/sec.
 */
public final class OresSchedulerLatencyBench {
    private OresSchedulerLatencyBench() { }

    private static void report(String scenario, String extra, long[] samples) {
        long[] s = samples.clone();
        Arrays.sort(s);
        double mean = Arrays.stream(s).average().orElse(0);
        int n = s.length;
        System.out.printf(
                "scenario=%s %s samples=%d p50_ns=%d p99_ns=%d p999_ns=%d max_ns=%d mean_ns=%.0f%n",
                scenario, extra, n, s[n / 2], s[(int) (n * 0.99)],
                s[Math.min(n - 1, (int) (n * 0.999))], s[n - 1], mean);
    }

    private static void floorPark(int hops) throws Exception {
        long[] samples = new long[hops];
        AtomicReference<Thread> main = new AtomicReference<>(Thread.currentThread());
        class Box { volatile long stamp; volatile int turn; }
        Box box = new Box();
        Thread peer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < hops; i++) {
                while (box.turn != 1) LockSupport.park();
                samples[i] = System.nanoTime() - box.stamp;
                box.turn = 0;
                LockSupport.unpark(main.get());
            }
        });
        for (int i = 0; i < hops; i++) {
            box.stamp = System.nanoTime();
            box.turn = 1;
            LockSupport.unpark(peer);
            while (box.turn != 0) LockSupport.park();
        }
        peer.join();
        report("floor_park", "kind=kernel_wake", samples);
    }

    private static void floorSpin(int hops) throws Exception {
        long[] samples = new long[hops];
        class Box { volatile long toPeer; volatile boolean toMain; }
        Box box = new Box();
        Thread peer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < hops; i++) {
                long stamp;
                while ((stamp = box.toPeer) == 0) Thread.onSpinWait();
                box.toPeer = 0;
                samples[i] = System.nanoTime() - stamp;
                box.toMain = true;
            }
        });
        for (int i = 0; i < hops; i++) {
            box.toPeer = System.nanoTime();
            while (!box.toMain) Thread.onSpinWait();
            box.toMain = false;
        }
        peer.join();
        report("floor_spin", "kind=cache_line", samples);
    }

    /** One side of the ping-pong: resumes with the peer's completion stamp. */
    private static final class Relay implements OresScheduler.Task<Void> {
        final AtomicReference<OresFuture<Long>> inbox = new AtomicReference<>(new OresFuture<>());
        Relay peer;
        final long[] samples;
        final AtomicInteger hop;
        final CountDownLatch done;

        Relay(long[] samples, AtomicInteger hop, CountDownLatch done) {
            this.samples = samples;
            this.hop = hop;
            this.done = done;
        }

        @Override
        public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
            if (!resume.initial()) {
                int i = hop.getAndIncrement();
                if (i >= samples.length) {
                    done.countDown();
                    return OresScheduler.done(null);
                }
                samples[i] = System.nanoTime() - (Long) resume.value();
                // Replace our inbox BEFORE waking the peer, so the peer
                // always finds the Future we are about to await.
                OresFuture<Long> next = new OresFuture<>();
                inbox.set(next);
                peer.inbox.get().completeFromRuntime(System.nanoTime());
                return OresScheduler.await(next);
            }
            return OresScheduler.await(inbox.get());
        }
    }

    private static void schedPingPong(int parallelism, int hops) throws Exception {
        long[] samples = new long[hops];
        AtomicInteger hop = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(parallelism)) {
            Relay a = new Relay(samples, hop, done);
            Relay b = new Relay(samples, hop, done);
            a.peer = b;
            b.peer = a;
            OresFuture<Void> fa = scheduler.start(a);
            OresFuture<Void> fb = scheduler.start(b);
            // Let both Tasks reach their first await before the kick.
            Thread.sleep(50);
            b.inbox.get().completeFromRuntime(System.nanoTime());
            if (!done.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("ping-pong stalled");
            // Release whichever side is still parked on its inbox.
            a.inbox.get().completeFromRuntime(System.nanoTime());
            b.inbox.get().completeFromRuntime(System.nanoTime());
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        }
        report("sched_pingpong", "parallelism=" + parallelism, samples);
    }

    private static void schedCooperate(int parallelism, int tasks, int yields) throws Exception {
        try (OresScheduler scheduler = new OresScheduler(parallelism)) {
            OresFuture<?>[] futures = new OresFuture<?>[tasks];
            long start = System.nanoTime();
            for (int t = 0; t < tasks; t++) {
                int[] left = {yields};
                futures[t] = scheduler.start(resume ->
                        --left[0] > 0 ? OresScheduler.cooperate() : OresScheduler.done(null));
            }
            for (OresFuture<?> f : futures) f.get(120, TimeUnit.SECONDS);
            long elapsed = System.nanoTime() - start;
            long turns = (long) tasks * yields;
            System.out.printf(
                    "scenario=sched_cooperate parallelism=%d tasks=%d turns=%d elapsed_ns=%d "
                            + "turns_per_sec=%.0f ns_per_turn=%.1f%n",
                    parallelism, tasks, turns, elapsed, turns * 1e9 / elapsed,
                    (double) elapsed / turns);
        }
    }

    public static void main(String[] args) throws Exception {
        int hops = 100_000;
        boolean smoke = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--smoke")) smoke = true;
            else if (args[i].equals("--hops") && i + 1 < args.length) hops = Integer.parseInt(args[++i]);
            else throw new IllegalArgumentException("usage: [--smoke] [--hops N]");
        }
        if (smoke) hops = 2_000;
        int yields = smoke ? 50 : 2_000;
        // Warm up JIT on every path before measuring.
        for (int w = 0; w < (smoke ? 0 : 2); w++) {
            schedPingPong(2, 20_000);
            schedCooperate(4, 64, 500);
        }
        System.out.println("--- measured ---");
        floorPark(hops);
        floorSpin(hops);
        schedPingPong(2, hops);
        schedPingPong(8, hops);
        schedCooperate(1, 64, yields);
        schedCooperate(4, 64, yields);
        schedCooperate(8, 256, yields);
    }
}
