import dev.oreslang.compiler.GpuLaunchFootprints;
import dev.oreslang.compiler.GpuLaunchFootprints.*;
import java.util.*;

/**
 * Dependency-free, host-only smoke for CI runner recovery. The verifier is
 * NOT an AttoLang compiler test or a physical GPU execution test.
 */
public final class GpuLaunchFootprintsSmoke {
    static final QueueScope QUEUE = new QueueScope("tenant", "gpu-0", "queue", 1);
    static final Limits BUDGET = new Limits(256, 256 * 1024);
    static int accepted, rejected, assertions;

    static void yes(boolean b) {
        assertions++;
        if (!b) throw new AssertionError("assertion " + assertions);
    }

    static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | SecurityException expected) {
            rejected++;
            return;
        }
        throw new AssertionError("expected GPU-LAUNCH rejection");
    }

    static boolean conflicting(List<View> views) {
        for (int i = 0; i < views.size(); i++)
            for (int j = i + 1; j < views.size(); j++) {
                View a = views.get(i), b = views.get(j);
                if (a.allocationId().equals(b.allocationId())
                        && (a.writes() || b.writes())
                        && a.byteOffset() < b.endExclusive()
                        && b.byteOffset() < a.endExclusive()) return true;
            }
        return false;
    }

    static View view(String id, long gen, long start, long length, Access mode) {
        return new View("tenant", "gpu-0", id, gen, 1024,
                start, length, 4, mode);
    }

    public static void main(String[] args) {
        SplittableRandom rng = new SplittableRandom(0xA770C0DEL);
        for (int trial = 0; trial < 2048; trial++) {
            List<View> views = new ArrayList<>();
            int count = 1 + rng.nextInt(48);
            for (int j = 0; j < count; j++) {
                String id = "allocation-" + rng.nextInt(4);
                long start = 4L * rng.nextInt(232);
                long length = 4L * (1 + rng.nextInt(16));
                Access mode = Access.values()[rng.nextInt(Access.values().length)];
                views.add(view(id, 17, start, length, mode));
            }
            boolean expected = conflicting(views), failed = false;
            try {
                GpuLaunchFootprints.validate(QUEUE, views, BUDGET);
            } catch (IllegalArgumentException e) {
                failed = true;
            }
            yes(failed == expected);
            if (failed) rejected++; else accepted++;
        }

        GpuLaunchFootprints.validate(QUEUE, List.of(
                view("same", 1, 0, 128, Access.READ),
                view("same", 1, 64, 128, Access.READ),
                view("same", 1, 192, 64, Access.WRITE)), BUDGET);
        accepted++;

        rejects(() -> GpuLaunchFootprints.validate(QUEUE, List.of(
                view("same", 1, 0, 128, Access.WRITE),
                view("same", 1, 64, 128, Access.READ)), BUDGET));
        rejects(() -> GpuLaunchFootprints.validate(QUEUE, List.of(
                view("same", 1, 0, 128, Access.READ),
                view("same", 2, 128, 128, Access.READ)), BUDGET));
        rejects(() -> GpuLaunchFootprints.validate(QUEUE, List.of(
                new View("foreign", "gpu-0", "a", 1, 1024,
                        0, 16, 4, Access.READ)), BUDGET));
        rejects(() -> GpuLaunchFootprints.validate(QUEUE, List.of(
                view("same", 1, 0, 1024, Access.READ)),
                new Limits(256, 1023)));
        rejects(() -> new View("tenant", "gpu-0", "a", 1,
                Long.MAX_VALUE, Long.MAX_VALUE - 3, 8, 1, Access.READ));

        List<View> adjacent = new ArrayList<>();
        for (int i = 0; i < 256; i++)
            adjacent.add(view("same", 1, i * 4L, 4, Access.WRITE));
        GpuLaunchFootprints.validate(QUEUE, adjacent, BUDGET);
        accepted++;
        adjacent.add(view("same", 1, 0, 4, Access.READ));
        rejects(() -> GpuLaunchFootprints.validate(QUEUE, adjacent, BUDGET));

        System.out.println("PASS exact-blob Java host checker: 2048 randomized comparisons, "
                + accepted + " accepted, " + rejected + " rejected, "
                + assertions + " oracle assertions");
    }
}
