import dev.oreslang.types.GuestClassSlots;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

public final class GuestClassSlotsHarness {
    private static int checks;
    private static void check(boolean result) {
        if (!result) throw new AssertionError("check " + checks);
        checks++;
    }
    private static void deny(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); throw new AssertionError("expected " + type); }
        catch (RuntimeException e) { check(type.isInstance(e)); }
    }
    public static void main(String[] args) throws Exception {
        var initial = new LinkedHashMap<String, Object>();
        var marker = new Object();
        initial.put("child", marker);
        initial.put("count", 1);
        var slots = new GuestClassSlots(initial);
        initial.clear();
        check(slots.size() == 2);
        check(slots.get("child") == marker);
        slots.initialize("optional", null);
        check(slots.containsKey("optional"));
        check(slots.get("optional") == null);
        slots.replace("count", 2);
        slots.finishInitialization();
        deny(IllegalStateException.class, () -> slots.initialize("new", 3));
        deny(IllegalArgumentException.class, () -> slots.replace("missing", 3));
        check(slots.size() == 3);
        Map<String, Object> view = slots.view();
        deny(UnsupportedOperationException.class, () -> view.put("count", 3));
        deny(UnsupportedOperationException.class, () -> view.entrySet().iterator().next().setValue(3));
        deny(UnsupportedOperationException.class, () -> view.keySet().remove("count"));
        deny(UnsupportedOperationException.class, () -> view.values().remove(marker));
        deny(UnsupportedOperationException.class, () -> view.values().iterator().remove());
        deny(UnsupportedOperationException.class, () -> view.replaceAll((k, v) -> 3));
        deny(UnsupportedOperationException.class, () -> view.compute("count", (k, v) -> 3));
        deny(UnsupportedOperationException.class, view::clear);
        var clone = new GuestClassSlots(view);
        clone.finishInitialization();
        var replacement = new Object();
        clone.replace("child", replacement);
        check(slots.get("child") == marker);
        check(clone.get("child") == replacement);
        check(view.get("count").equals(2));
        for (int i = 0; i < 10000; i++) {
            slots.replace("count", i);
            check(view.get("count").equals(i));
            check(clone.get("count").equals(2));
        }
        // Actual slot stores: the child is frozen, parent slots stay writable.
        var child = new GuestClassSlots(Map.of("n", 10));
        deny(IllegalStateException.class, child::retainReadOnly);
        child.finishInitialization();
        var old = new GuestClassSlots(Map.of("data", child)); old.finishInitialization();
        var copied = new GuestClassSlots(old.view()); copied.finishInitialization();
        var first = child.retainReadOnly(); var second = child.retainReadOnly();
        check(child.isReadOnly());
        deny(IllegalArgumentException.class, () -> child.replace("n", 11));
        var fresh = new GuestClassSlots(Map.of("n", 20)); fresh.finishInitialization();
        copied.replace("data", fresh); fresh.replace("n", 21);
        check(old.get("data") == child && copied.get("data") == fresh);
        check(child.get("n").equals(10) && fresh.get("n").equals(21));
        first.close(); check(child.isReadOnly());
        deny(IllegalStateException.class, first::close);
        deny(IllegalArgumentException.class, () -> child.replace("n", 12));
        second.close(); check(!child.isReadOnly()); child.replace("n", 30);
        check(child.get("n").equals(30));
        var leases = new GuestClassSlots.ReadOnlyLease[65536];
        for (int i = 0; i < leases.length; i++) leases[i] = child.retainReadOnly();
        deny(IllegalArgumentException.class, child::retainReadOnly);
        for (var lease : leases) lease.close();
        check(!child.isReadOnly());
        // Freeze and write are serialized by the same monitor. Hold the
        // acquired lease until the competing writer completes.
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 1000; i++) {
                child.replace("n", 0);
                var barrier = new CyclicBarrier(2);
                var freeze = pool.submit(() -> { barrier.await(); return child.retainReadOnly(); });
                var write = pool.submit(() -> { barrier.await(); try {
                    child.replace("n", 1); return true;
                } catch (IllegalArgumentException ex) {
                    if (!ex.getMessage().startsWith("E_COPY_READONLY")) throw ex;
                    return false;
                }});
                var lease = freeze.get(5, TimeUnit.SECONDS);
                boolean wrote = write.get(5, TimeUnit.SECONDS);
                check(child.get("n").equals(wrote ? 1 : 0));
                deny(IllegalArgumentException.class, () -> child.replace("n", 2));
                lease.close(); child.replace("n", 3); check(child.get("n").equals(3));
            }
        }
        System.out.println("PASS " + checks + " assertions; 1000 lease/write races");
    }
}
