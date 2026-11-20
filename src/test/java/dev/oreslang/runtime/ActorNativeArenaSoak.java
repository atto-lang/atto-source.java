package dev.oreslang.runtime;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JNI-backed actor arena lifetime/charge soak, NOT an AttoLang guest or
 * a process-wide zero-GC certification. No System.gc or explicit GC calls.
 */
public final class ActorNativeArenaSoak {
    private static final int RESTARTS = 128;
    private static final int TURNS_PER_ACTOR = 800;
    private static final long SENTINEL = 0x5EC12E7L;

    private static ActorNativeArena create(AtomicLong charge) {
        return new ActorNativeArena(256 * 1024, () -> { }, bytes -> {
            charge.addAndGet(bytes);
            return () -> charge.addAndGet(-bytes);
        });
    }

    private static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    // OptionalLong rather than 0: Epsilon/JVM may expose no observable GC beans.
    private static long gcCountOrUnknown() {
        List<GarbageCollectorMXBean> list = ManagementFactory.getGarbageCollectorMXBeans();
        if (list.isEmpty()) return -1L;
        long count = 0L;
        for (GarbageCollectorMXBean bean : list) {
            long n = bean.getCollectionCount();
            if (n < 0) return -1L;
            count = Math.addExact(count, n);
        }
        return count;
    }

    private static long rssKiBOrUnknown() {
        if (!System.getProperty("os.name", "").equalsIgnoreCase("Linux")) return -1L;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (Exception ignored) {
            // Missing OS process telemetry is UNKNOWN, not zero.
        }
        return -1L;
    }

    public static void main(String[] args) {
        require(args.length == 1, "usage: ActorNativeArenaSoak /absolute/liboresarena.so");
        Path nativeLibrary = Path.of(args[0]).toAbsolutePath();
        System.setProperty("ores.thread.native.path", nativeLibrary.toString());
        System.load(nativeLibrary.toString());
        AtomicLong charged = new AtomicLong();
        long gcBefore = gcCountOrUnknown();
        long rssBefore = rssKiBOrUnknown();
        long peakRssKiB = rssBefore;
        long maxNativeCharge = 0L;

        ActorNativeArena survivor = create(charged);
        ActorNativeArena.Region intact = survivor.region(32);
        intact.writeLong(0, SENTINEL);
        long survivorCharge = charged.get();
        require(survivorCharge > 0L, "survivor arena had no native charge");
        ActorNativeArena.Region previous = null;

        for (int generation = 0; generation < RESTARTS; generation++) {
            ActorNativeArena next = create(charged);
            ActorNativeArena.Region region = next.region(32);
            require(region.readLong(0) == 0L, "new arena exposes old state");
            for (int turn = 0; turn < TURNS_PER_ACTOR; turn++) {
                long value = (long) generation * TURNS_PER_ACTOR + turn + 1;
                region.writeLong(0, value);
                require(region.readLong(0) == value, "actor turn lost native state");
            }
            if (previous != null) {
                ActorNativeArena.Region stale = previous;
                boolean refused = false;
                try { stale.readLong(0); }
                catch (IllegalStateException expected) { refused = true; }
                require(refused, "retired actor region stayed readable");
            }
            maxNativeCharge = Math.max(maxNativeCharge, charged.get());
            if (generation % 16 == 0) {
                try (var loan = region.leaseReadOnly(0, Long.BYTES)) {
                    boolean refused = false;
                    try { next.close(); }
                    catch (IllegalStateException expected) { refused = true; }
                    require(refused, "arena freed while read lease alive");
                    require(loan.readLong(0) > 0L, "retained loan lost state");
                }
            }
            next.close();
            require(charged.get() == survivorCharge,
                    "native budget not returned after actor retirement: "+charged.get());
            require(intact.readLong(0) == SENTINEL, "neighbor actor state corrupted");
            previous = region;
            if (generation % 16 == 15) {
                long sample = rssKiBOrUnknown();
                if (sample >= 0) peakRssKiB = Math.max(peakRssKiB, sample);
            }
        }
        survivor.close();
        require(charged.get() == 0L, "native budget not returned after final retirement");

        long gcAfter = gcCountOrUnknown();
        long rssAfter = rssKiBOrUnknown();
        boolean gcObservable = gcBefore >= 0 && gcAfter >= gcBefore;
        if (Boolean.getBoolean("atto.require.no.collections")) {
            require(gcObservable, "strict no-collection mode has no observable GC counters");
            require(gcAfter == gcBefore, "GC occurred in strict no-collection mode");
        }
        if (System.getProperty("atto.max.rss.kib") != null) {
            long limit = Long.parseLong(System.getProperty("atto.max.rss.kib"));
            require(peakRssKiB >= 0L && peakRssKiB <= limit,
                    "peak RSS unavailable or above configured absolute budget");
        }
        System.out.println("ATTO_NATIVE_ARENA_SOAK=PASS");
        System.out.println("actor_operations=" + (RESTARTS * TURNS_PER_ACTOR));
        System.out.println("actor_arena_restarts=" + RESTARTS);
        System.out.println("tenant_restarts=NOT_TESTED");
        System.out.println("native_charge_after=0");
        System.out.println("peak_native_charge_bytes=" + maxNativeCharge);
        System.out.println("gc_observed=" + gcObservable);
        System.out.println("gc_collection_delta=" + (gcObservable ? gcAfter - gcBefore : "UNKNOWN"));
        System.out.println("rss_start_kib=" + (rssBefore < 0 ? "UNKNOWN" : rssBefore));
        System.out.println("rss_peak_sampled_kib=" + (peakRssKiB < 0 ? "UNKNOWN" : peakRssKiB));
        System.out.println("rss_end_kib=" + (rssAfter < 0 ? "UNKNOWN" : rssAfter));
        System.out.println("EVIDENCE_SCOPE=JNI_NATIVE_ARENA_HOST_ONLY_NO_ATTO_GUEST_OR_GRAAL_EPSILON_PROOF");
    }
}
