import dev.oreslang.types.GuestClassSlots;
import java.util.LinkedHashMap;
import java.util.Map;

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
    public static void main(String[] args) {
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
        System.out.println("PASS " + checks + " assertions");
    }
}
