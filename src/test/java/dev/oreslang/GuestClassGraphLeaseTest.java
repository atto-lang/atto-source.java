package dev.oreslang;

import dev.oreslang.types.GuestClassSlots;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class GuestClassGraphLeaseTest {
    private static GuestClassSlots store(GuestClassSlots.Domain d, Map<String,Object> slots) {
        var s = new GuestClassSlots(d, slots);
        s.finishInitialization();
        return s;
    }
    private static void deny(String code, Runnable r) {
        var e = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(e.getMessage().startsWith(code), e::toString);
    }

    @Test void diamondAndMultipleCopiesProtectIdentityOncePerLease() {
        var d = new GuestClassSlots.Domain();
        var child = store(d, Map.of("n", 10));
        var a = store(d, Map.of("left", child, "right", child));
        var b = store(d, a.view());
        var one = a.retainDescendants(1, 3);
        var two = b.retainDescendants(1, 3);
        assertEquals(1, one.protectedCount());
        assertFalse(a.isReadOnly());
        deny("E_COPY_READONLY", () -> child.replace("n", 11));
        var fresh = store(d, Map.of("n", 20));
        b.replace("left", fresh);
        b.replace("right", fresh);
        fresh.replace("n", 21);
        assertSame(child, a.get("left"));
        one.close();
        assertTrue(child.isReadOnly());
        two.close();
        child.replace("n", 12);
        assertEquals(12, child.get("n"));
        assertThrows(IllegalStateException.class, two::close);
    }

    @Test void cycleIncludesOriginalRootAndTraversalIsIterative() {
        var d = new GuestClassSlots.Domain();
        var nodes = new GuestClassSlots[10000];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = store(d, Map.of("next", 0));
        }
        for (int i = 0; i < nodes.length; i++) nodes[i].replace("next", nodes[(i+1)%nodes.length]);
        try (var lease = nodes[0].retainDescendants(10000, 10001)) {
            assertEquals(10000, lease.protectedCount());
            deny("E_COPY_READONLY", () -> nodes[0].replace("next", 0));
            deny("E_COPY_READONLY", () -> nodes[9999].replace("next", 0));
        }
        assertFalse(nodes[0].isReadOnly());
        assertFalse(nodes[9999].isReadOnly());
    }

    @Test void preflightFailureNeverFreezesEarlierValidChild() {
        var d = new GuestClassSlots.Domain();
        var good = store(d, Map.of("n", 1));
        var foreign = store(new GuestClassSlots.Domain(), Map.of("n", 2));
        var root = new GuestClassSlots(d, Map.of("first", good));
        root.initialize("second", foreign);
        root.finishInitialization();
        deny("E_COPY_DOMAIN", () -> root.retainDescendants(10, 10));
        assertFalse(good.isReadOnly());
        root.replace("second", new Object());
        deny("E_COPY_UNPROVEN", () -> root.retainDescendants(10, 10));
        assertFalse(good.isReadOnly());
        root.replace("second", new GuestClassSlots(d, Map.of()));
        assertThrows(IllegalStateException.class, () -> root.retainDescendants(10, 10));
        assertFalse(good.isReadOnly());
        root.replace("second", good);
        deny("E_COPY_BUDGET", () -> root.retainDescendants(0, 10));
        deny("E_COPY_BUDGET", () -> root.retainDescendants(10, 2));
        assertFalse(good.isReadOnly());
        good.replace("n", 3);
    }

    @Test void saturatedChildRejectsWithoutPartialRetention() {
        var d = new GuestClassSlots.Domain();
        var a = store(d, Map.of("n", 1));
        var b = store(d, Map.of("n", 2));
        var root = new GuestClassSlots(d, Map.of("a", a));
        root.initialize("b", b); root.finishInitialization();
        var leases = new GuestClassSlots.ReadOnlyLease[65536];
        for (int i=0; i<leases.length; i++) leases[i]=b.retainReadOnly();
        deny("E_COPY_BUDGET", () -> root.retainDescendants(2,4));
        assertFalse(a.isReadOnly());
        for (var l:leases) l.close();
        assertFalse(b.isReadOnly());
    }

    @Test void snapshotRootReplacementAndLexicalRelease() {
        var d = new GuestClassSlots.Domain();
        var child = store(d, Map.of("n", 10));
        var original = store(d, Map.of("data", child, "count", 1));
        var fresh = store(d, Map.of("n", 20));
        var snapshot = original.shallowSnapshot(1,3);
        assertSame(child, snapshot.get("data"));
        assertEquals(1, snapshot.protectedCount());
        snapshot.replace("count",2);
        original.replace("count",3);
        assertEquals(2,snapshot.get("count"));
        assertEquals(3,original.get("count"));
        deny("E_COPY_READONLY",()->child.replace("n",11));
        snapshot.replace("data",fresh);
        fresh.replace("n",21);
        assertSame(child,original.get("data"));
        assertSame(fresh,snapshot.get("data"));
        // Conservative scope lease: per-edge detachment is a separate gate.
        deny("E_COPY_READONLY",()->child.replace("n",12));
        snapshot.close();
        child.replace("n",12);
        assertEquals(12,child.get("n"));
        assertThrows(IllegalStateException.class,()->snapshot.get("data"));
        assertThrows(IllegalStateException.class,()->snapshot.replace("count",4));
        assertThrows(IllegalStateException.class,snapshot::close);
        deny("E_COPY_BUDGET",()->original.shallowSnapshot(0,3));
        assertFalse(child.isReadOnly());
    }

    @Test void snapshotRootAndLeaseObserveSameConcurrentReplacement() throws Exception {
        var d = new GuestClassSlots.Domain();
        try(var pool=Executors.newFixedThreadPool(2)){
            for(int i=0;i<1000;i++){
                var old=store(d,Map.of("n",1));
                var fresh=store(d,Map.of("n",2));
                var original=store(d,Map.of("data",old));
                var barrier=new CyclicBarrier(2);
                var copy=pool.submit(()->{
                    barrier.await(5,TimeUnit.SECONDS);
                    return original.shallowSnapshot(1,2);
                });
                var update=pool.submit(()->{
                    barrier.await(5,TimeUnit.SECONDS);
                    original.replace("data",fresh);
                    return true;
                });
                try(var snapshot=copy.get(10,TimeUnit.SECONDS)){
                    assertTrue(update.get(10,TimeUnit.SECONDS));
                    Object held=snapshot.get("data");
                    assertTrue(held==old||held==fresh);
                    assertEquals(held==old,old.isReadOnly());
                    assertEquals(held==fresh,fresh.isReadOnly());
                    assertSame(fresh,original.get("data"));
                }
                assertFalse(old.isReadOnly());
                assertFalse(fresh.isReadOnly());
            }
        }
    }

    @Test void graphFreezeAndConcurrentEdgeReplacementLinearize() throws Exception {
        var d = new GuestClassSlots.Domain();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i=0; i<1000; i++) {
                var old = store(d, Map.of("n",1));
                var fresh = store(d, Map.of("n",2));
                var child = store(d, Map.of("edge",old));
                var root = store(d, Map.of("child",child));
                var barrier = new CyclicBarrier(2);
                var freeze = pool.submit(() -> {
                    barrier.await(5,TimeUnit.SECONDS);
                    return root.retainDescendants(3,4);
                });
                var write = pool.submit(() -> {
                    barrier.await(5,TimeUnit.SECONDS);
                    try { child.replace("edge",fresh); return true; }
                    catch (IllegalArgumentException e) {
                        if (!e.getMessage().startsWith("E_COPY_READONLY")) throw e;
                        return false;
                    }
                });
                try (var lease = freeze.get(10,TimeUnit.SECONDS)) {
                    boolean wrote = write.get(10,TimeUnit.SECONDS);
                    assertEquals(2,lease.protectedCount());
                    assertTrue(child.isReadOnly());
                    assertEquals(wrote,fresh.isReadOnly());
                    assertEquals(!wrote,old.isReadOnly());
                }
                assertFalse(child.isReadOnly());
                assertFalse(old.isReadOnly());
                assertFalse(fresh.isReadOnly());
            }
        }
    }
}
