import dev.oreslang.types.GuestClassSlots;
import java.util.Map;
import java.util.concurrent.*;

public final class ShallowSnapshotHarness {
    private static int checks;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("check " + checks);
        checks++;
    }
    private static GuestClassSlots store(GuestClassSlots.Domain d, Map<String,Object> slots) {
        var s = new GuestClassSlots(d, slots);
        s.finishInitialization();
        return s;
    }
    private static void deny(String code, Runnable action) {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (IllegalArgumentException | IllegalStateException ex) {
            check(ex.getMessage().startsWith(code));
        }
    }
    public static void main(String[] args) throws Exception {
        var d = new GuestClassSlots.Domain();
        var shared = store(d, Map.of("n", 10));
        var source = store(d, Map.of("data", shared, "count", 1));
        var donor = store(d, Map.of("edge", 0));
        var foreign = store(new GuestClassSlots.Domain(), Map.of("n", 2));
        var pending = new GuestClassSlots(d, Map.of("n", 3));
        var outside = store(d, Map.of("n", 4));
        try (var snap = source.shallowSnapshot(4, 8); var external = outside.retainReadOnly()) {
            check(external != null && outside.isReadOnly());
            donor.replace("edge", foreign);
            deny("E_COPY_DOMAIN", () -> snap.replace("data", donor));
            donor.replace("edge", pending);
            deny("E_COPY_LAYOUT", () -> snap.replace("data", donor));
            donor.replace("edge", new Object());
            deny("E_COPY_UNPROVEN", () -> snap.replace("data", donor));
            donor.replace("edge", source);
            deny("E_COPY_SHALLOW_ALIAS", () -> snap.replace("data", donor));
            deny("E_COPY_SHALLOW_ALIAS", () -> snap.replace("data", source));
            donor.replace("edge", outside);
            deny("E_COPY_SHALLOW_ALIAS", () -> snap.replace("data", donor));
            check(snap.get("data") == shared && source.get("data") == shared);
            check(shared.isReadOnly() && !donor.isReadOnly() && !foreign.isReadOnly());
            donor.replace("edge", shared);
            snap.replace("data", donor);
            check(snap.get("data") == donor);
            deny("E_COPY_READONLY", () -> shared.replace("n", 99));
            donor.replace("edge", 7);
            snap.replace("count", 2); source.replace("count", 3);
            check(snap.get("count").equals(2) && source.get("count").equals(3));
        }
        check(!shared.isReadOnly() && !outside.isReadOnly());
        var a = store(d, Map.of("edge", 0));
        var b = store(d, Map.of("edge", a));
        a.replace("edge", b);
        try (var snap = source.shallowSnapshot(1, 3)) {
            deny("E_COPY_BUDGET", () -> snap.replace("data", a));
            check(snap.get("data") == shared && !a.isReadOnly() && !b.isReadOnly());
        }
        try (var snap = source.shallowSnapshot(2, 3)) {
            snap.replace("data", a);
            check(snap.get("data") == a && a.get("edge") == b && b.get("edge") == a);
        }
        var right = store(d, Map.of("data", 2));
        try (var first = source.shallowSnapshot(4, 8); var second = right.shallowSnapshot(4, 8)) {
            deny("E_COPY_SHALLOW_ALIAS", () -> second.replace("data", shared));
            check(first.protectedCount() == 1 && second.get("data").equals(2));
        }
        check(!shared.isReadOnly());
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 1000; i++) {
                var child = store(d, Map.of("n", 1));
                var root = store(d, Map.of("data", child));
                var other = store(d, Map.of("data", 2));
                var first = root.shallowSnapshot(2, 4);
                var second = other.shallowSnapshot(2, 4);
                var barrier = new CyclicBarrier(2);
                var release = pool.submit(() -> { barrier.await(); root.replace("data", 0); first.close(); return true; });
                var replace = pool.submit(() -> {
                    barrier.await();
                    try { second.replace("data", child); return true; }
                    catch (IllegalArgumentException ex) {
                        if (!ex.getMessage().startsWith("E_COPY_SHALLOW_ALIAS")) throw ex;
                        return false;
                    }
                });
                check(release.get(5, TimeUnit.SECONDS));
                boolean wrote = replace.get(5, TimeUnit.SECONDS);
                check(!child.isReadOnly());
                check(wrote ? second.get("data") == child : second.get("data").equals(2));
                second.close();
                deny("E_COPY_STALE", () -> second.get("data"));
                child.replace("n", 3);
            }
        }
        System.out.println("PASS " + checks + " assertions; 1000 lease-release/replacement races; HOST ONLY");
    }
}
