import dev.oreslang.types.ShallowSlotDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public final class SlotHarness {
    static int passed;
    static void check(boolean b) { if (!b) throw new AssertionError("check " + passed); passed++; }
    static void deny(String code, Runnable r) {
        try { r.run(); throw new AssertionError("expected " + code); }
        catch (IllegalArgumentException e) { check(e.getMessage().equals(code)); }
    }
    static ShallowSlotDomain.Owner empty(ShallowSlotDomain d) { return d.create(true, false, Map.of(), Set.of()); }
    static ShallowSlotDomain.Owner root(ShallowSlotDomain d) { return d.create(true, false, Map.of("count", 1), Set.of()); }
    public static void main(String[] args) throws Exception {
        var d = new ShallowSlotDomain(64, 32, 1000);
        var a = root(d); var ar = d.node(a); var child = root(d); var cr = d.node(child);
        d.moveInto(a, ar, "child", child); deny("E_COPY_STALE", () -> d.node(child));
        var b = d.shallowCopy(a); var br = d.node(b);
        check(ar != br); check(d.read(a, ar, "child") == d.read(b, br, "child"));
        deny("E_COPY_READONLY", () -> d.setScalar(a, cr, "count", 2));
        deny("E_COPY_READONLY", () -> d.setScalar(b, cr, "count", 2));
        d.setScalar(a, ar, "count", 2); d.setScalar(b, br, "count", 3);
        check(d.read(a, ar, "count").equals(2)); check(d.read(b, br, "count").equals(3));
        var loan = d.readLoan(b, cr); var donor = root(d); var dr = d.node(donor);
        d.moveInto(b, br, "child", donor); check(d.read(b, br, "child") == dr);
        check(d.canWrite(b, dr)); check(!d.canWrite(a, cr));
        deny("E_COPY_LOAN", () -> d.setScalar(a, cr, "count", 4));
        d.release(loan); d.setScalar(a, cr, "count", 4); check(d.read(a, cr, "count").equals(4));
        d.setScalar(b, dr, "count", 5); check(d.read(b, dr, "count").equals(5));
        // Multiple paths and three independent roots, symmetric original replacement.
        d.linkWithin(a, ar, "again", cr); var c = d.shallowCopy(a); var e = d.shallowCopy(c);
        d.setScalar(a, ar, "child", null); check(!d.canWrite(a, cr));
        d.remove(a, ar, "again"); check(!d.canWrite(c, cr));
        d.drop(e); check(d.canWrite(c, cr));
        deny("E_COPY_DOMAIN", () -> d.linkWithin(a, ar, "bad", cr));
        // Read-only slot distinct from read-only target.
        var fixed = d.create(false, false, Map.of("fixed", 1, "mutable", 2), Set.of("fixed")); var fr = d.node(fixed);
        var src = root(d); deny("E_COPY_READONLY_SLOT", () -> d.moveInto(fixed, fr, "fixed", src)); check(d.node(src) != null);
        deny("E_COPY_LAYOUT", () -> d.moveInto(fixed, fr, "new", src)); check(d.node(src) != null);
        d.moveInto(fixed, fr, "mutable", src); deny("E_COPY_STALE", () -> d.node(src));
        // Cycle: original root becomes shared, clone root remains editable.
        var cyc = empty(d); var cy = d.node(cyc); d.linkWithin(cyc, cy, "self", cy);
        var cp = d.shallowCopy(cyc); var cpnode = d.node(cp); check(!d.canWrite(cyc, cy));
        deny("E_COPY_READONLY", () -> d.remove(cyc, cy, "self")); d.remove(cp, cpnode, "self"); check(d.canWrite(cyc, cy));
        // Noncopyable and foreign domain denial.
        var protectedOwner = d.create(true, true, Map.of(), Set.of()); d.moveInto(a, ar, "protected", protectedOwner);
        deny("E_COPY_FORBIDDEN", () -> d.shallowCopy(a)); check(d.canWrite(a, ar));
        var foreign = new ShallowSlotDomain(2, 0, 10); var fo = empty(foreign);
        deny("E_COPY_STALE", () -> d.moveInto(a, ar, "foreign", fo)); check(foreign.node(fo) != null);
        // Budget failure retains old value and donor; no premature rights changes.
        var small = new ShallowSlotDomain(8, 1, 5); var s = root(small); var sr = small.node(s);
        var large = small.create(true, false, Map.of("a",1,"b",2,"c",3), Set.of());
        deny("E_COPY_BUDGET", () -> small.moveInto(s, sr, "count", large));
        check(small.read(s,sr,"count").equals(1)); check(small.node(large)!=null); check(small.canWrite(s,sr));
        // Exclusive loans protect all aliases, copy, move, detach, and drop.
        var exOwner = root(d); var exRoot = d.node(exOwner); var exChild = root(d); var exNode = d.node(exChild);
        d.moveInto(exOwner, exRoot, "child", exChild);
        var ex = d.exclusiveLoan(exOwner, exNode);
        deny("E_COPY_LOAN", () -> d.read(exOwner, exNode, "count"));
        deny("E_COPY_LOAN", () -> d.setScalar(exOwner, exNode, "count", 2));
        deny("E_COPY_LOAN", () -> d.shallowCopy(exOwner));
        deny("E_COPY_LOAN", () -> d.readLoan(exOwner, exRoot));
        deny("E_COPY_LOAN", () -> d.exclusiveLoan(exOwner, exRoot));
        deny("E_COPY_LOAN", () -> d.remove(exOwner, exRoot, "child"));
        var replacement = root(d);
        deny("E_COPY_LOAN", () -> d.moveInto(exOwner, exRoot, "child", replacement));
        check(d.node(replacement) != null);
        deny("E_COPY_LOAN", () -> d.drop(exOwner));
        deny("E_COPY_DOMAIN", () -> d.setExclusiveScalar(ex, exRoot, "count", 2));
        deny("E_COPY_LAYOUT", () -> d.setExclusiveScalar(ex, exNode, "new", 2));
        d.setExclusiveScalar(ex, exNode, "count", 99); check(d.readExclusive(ex, exNode, "count").equals(99));
        d.setScalar(exOwner, exRoot, "count", 3); check(d.read(exOwner, exRoot, "count").equals(3));
        d.release(ex); deny("E_COPY_STALE", () -> d.readExclusive(ex, exNode, "count"));
        check(d.read(exOwner, exNode, "count").equals(99));
        var exCopy = d.shallowCopy(exOwner); deny("E_COPY_READONLY", () -> d.exclusiveLoan(exOwner, exNode));
        d.drop(exCopy); d.drop(exOwner); d.drop(replacement);
        // Exact-budget scalar overwrite must not double-charge a slot.
        var tight = new ShallowSlotDomain(3, 1, 2); var t = root(tight); var tr = tight.node(t);
        for (int i = 0; i < 10000; i++) d.setScalar(a, ar, "count", i);
        tight.setScalar(t, tr, "count", 8); check(tight.read(t, tr, "count").equals(8));
        deny("E_COPY_BUDGET", () -> tight.setScalar(t, tr, "new", 2));
        check(tight.read(t, tr, "count").equals(8));
        // Race: copying and mutating descendant serialize under one domain monitor.
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i=0;i<1000;i++) {
                var x=empty(d); var xr=d.node(x); var y=root(d); var yr=d.node(y); d.moveInto(x,xr,"child",y);
                var gate = new CyclicBarrier(2);
                var copy = pool.submit(() -> {gate.await(); return d.shallowCopy(x);});
                var mutation = pool.submit(() -> {gate.await(); try {d.setScalar(x,yr,"count",2); return true;} catch(IllegalArgumentException failure){if(!failure.getMessage().equals("E_COPY_READONLY")) throw failure;return false;}});
                var z=copy.get(); boolean wrote=mutation.get(); var zr=d.node(z);
                check(d.read(z,yr,"count").equals(wrote?2:1)); check(!d.canWrite(x,yr));
                deny("E_COPY_READONLY",()->d.setScalar(x,yr,"count",3)); d.remove(z,zr,"child"); check(d.canWrite(x,yr));
                d.drop(z);d.drop(x);
            }
        }
        System.out.println("PASS " + passed + " assertions; 1000 synchronized copy/write races");
    }
}
