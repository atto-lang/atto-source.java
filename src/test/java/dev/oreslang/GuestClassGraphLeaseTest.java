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

    @Test void shallowSnapshotRejectsRootBackEdgesWithoutFreezingAnySlots() {
        var d = new GuestClassSlots.Domain();
        var child = store(d, Map.of("next", 0));
        var root = store(d, Map.of("child", child, "count", 1));
        child.replace("next", root);
        deny("E_COPY_SHALLOW_ALIAS", () -> root.shallowSnapshot(2, 3));
        assertFalse(root.isReadOnly());
        assertFalse(child.isReadOnly());
        root.replace("count", 2);
        child.replace("next", 0);
        try (var snapshot = root.shallowSnapshot(1, 3)) {
            snapshot.replace("count", 3);
            root.replace("count", 4);
            assertEquals(3, snapshot.get("count"));
            assertEquals(4, root.get("count"));
            deny("E_COPY_READONLY", () -> child.replace("next", 1));
        }
        // Direct self-edge is subject to the same fail-atomic policy.
        root.replace("child", root);
        deny("E_COPY_SHALLOW_ALIAS", () -> root.shallowSnapshot(1, 4));
        assertFalse(root.isReadOnly());
        root.replace("child", child);
        assertFalse(child.isReadOnly());
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

    @Test void snapshotReplacementRejectsInvalidRootsWithoutChangingSlotOrLease() {
        var d = new GuestClassSlots.Domain();
        var child = store(d, Map.of("n", 10));
        var original = store(d, Map.of("data", child));
        var foreign = store(new GuestClassSlots.Domain(), Map.of("n", 20));
        var unpublished = new GuestClassSlots(d, Map.of("n", 30));
        try (var snapshot = original.shallowSnapshot(1, 2)) {
            deny("E_COPY_DOMAIN", () -> snapshot.replace("data", foreign));
            assertThrows(IllegalStateException.class, () -> snapshot.replace("data", unpublished));
            deny("E_COPY_UNPROVEN", () -> snapshot.replace("data", new Object()));
            assertSame(child, snapshot.get("data"));
            assertSame(child, original.get("data"));
            assertTrue(child.isReadOnly());
            assertFalse(foreign.isReadOnly());
            unpublished.finishInitialization();
            snapshot.replace("data", unpublished);
            unpublished.replace("n", 31);
            assertEquals(31, unpublished.get("n"));
            deny("E_COPY_READONLY", () -> child.replace("n", 11));
        }
        child.replace("n", 12);
        assertEquals(12, child.get("n"));
    }

    @Test void twoSnapshotsAndOriginalReplacementPreserveOtherLoan() {
        var d = new GuestClassSlots.Domain();
        var child = store(d, Map.of("n", 10));
        var original = store(d, Map.of("data", child));
        var first = original.shallowSnapshot(1, 2);
        try (var second = original.shallowSnapshot(1, 2)) {
            var fresh = store(d, Map.of("n", 20));
            original.replace("data", fresh);
            fresh.replace("n", 22);
            first.replace("data", child); // same shared identity, not a thaw
            deny("E_COPY_READONLY", () -> child.replace("n", 99));
            first.close();
            assertSame(child, second.get("data"));
            assertSame(fresh, original.get("data"));
            deny("E_COPY_READONLY", () -> child.replace("n", 99));
        }
        child.replace("n", 12);
        assertEquals(12, child.get("n"));
    }

    @Test void replacementGraphValidationIsTransitiveAndFailAtomic() {
        var d = new GuestClassSlots.Domain();
        var shared = store(d, Map.of("n", 10));
        var original = store(d, Map.of("data", shared, "count", 1));
        var donor = store(d, Map.of("edge", 0));
        var foreign = store(new GuestClassSlots.Domain(), Map.of("n", 2));
        var unpublished = new GuestClassSlots(d, Map.of("n", 3));
        var outside = store(d, Map.of("n", 4));
        try (var snapshot = original.shallowSnapshot(4, 8);
             var external = outside.retainReadOnly()) {
            donor.replace("edge", foreign);
            deny("E_COPY_DOMAIN", () -> snapshot.replace("data", donor));
            donor.replace("edge", unpublished);
            assertThrows(IllegalStateException.class, () -> snapshot.replace("data", donor));
            donor.replace("edge", new Object());
            deny("E_COPY_UNPROVEN", () -> snapshot.replace("data", donor));
            donor.replace("edge", original);
            deny("E_COPY_SHALLOW_ALIAS", () -> snapshot.replace("data", donor));
            deny("E_COPY_SHALLOW_ALIAS", () -> snapshot.replace("data", original));
            donor.replace("edge", outside);
            deny("E_COPY_SHALLOW_ALIAS", () -> snapshot.replace("data", donor));
            assertSame(shared, original.get("data"));
            assertSame(shared, snapshot.get("data"));
            assertTrue(shared.isReadOnly());
            assertFalse(donor.isReadOnly());
            assertFalse(foreign.isReadOnly());
            assertTrue(outside.isReadOnly());
            // Already retained shared descendants remain protected even when
            // reached through a newly introduced, independently owned parent.
            donor.replace("edge", shared);
            snapshot.replace("data", donor);
            assertSame(donor, snapshot.get("data"));
            deny("E_COPY_READONLY", () -> shared.replace("n", 99));
            donor.replace("edge", 7);
            assertSame(shared, original.get("data"));
        }
        assertFalse(shared.isReadOnly());
        assertFalse(outside.isReadOnly());
        shared.replace("n", 11);
        outside.replace("n", 12);
    }

    @Test void replacementBudgetAndDescendantCycleHaveAtomicOutcomes() {
        var d = new GuestClassSlots.Domain();
        var leaf = store(d, Map.of("n", 1));
        var original = store(d, Map.of("data", leaf));
        var a = store(d, Map.of("edge", 0));
        var b = store(d, Map.of("edge", 0));
        a.replace("edge", b);
        b.replace("edge", a);
        try (var small = original.shallowSnapshot(1, 2)) {
            deny("E_COPY_BUDGET", () -> small.replace("data", a));
            assertSame(leaf, small.get("data"));
            assertFalse(a.isReadOnly());
            assertFalse(b.isReadOnly());
        }
        try (var enough = original.shallowSnapshot(2, 2)) {
            enough.replace("data", a);
            assertSame(a, enough.get("data"));
            assertSame(b, a.get("edge"));
            assertSame(a, b.get("edge"));
            assertTrue(leaf.isReadOnly());
        }
        assertFalse(leaf.isReadOnly());
    }

    @Test void distinctSnapshotCannotBorrowAnotherSnapshotLease() {
        var d = new GuestClassSlots.Domain();
        var child = store(d, Map.of("n", 1));
        var left = store(d, Map.of("data", child));
        var right = store(d, Map.of("data", 2));
        var donor = store(d, Map.of("nested", child));
        try (var first = left.shallowSnapshot(3, 4);
             var second = right.shallowSnapshot(3, 4)) {
            deny("E_COPY_SHALLOW_ALIAS", () -> second.replace("data", child));
            deny("E_COPY_SHALLOW_ALIAS", () -> second.replace("data", donor));
            assertEquals(2, second.get("data"));
            assertTrue(child.isReadOnly());
            assertEquals(1, first.protectedCount());
        }
        child.replace("n", 3);
    }

    @Test void successiveSnapshotsProtectTransitiveReferentsAfterParentClose() {
        var d = new GuestClassSlots.Domain();
        var leaf = store(d, Map.of("n", 10));
        var child = store(d, Map.of("leaf", leaf));
        var original = store(d, Map.of("data", child, "count", 1));
        var first = original.shallowSnapshot(2, 4);
        try {
            first.replace("count", 2);
            try (var second = first.shallowSnapshot(2, 4)) {
                assertSame(child, second.get("data"));
                assertEquals(2, second.get("count"));
                first.replace("count", 3);
                original.replace("count", 4);
                assertEquals(2, second.get("count"));
                var freshLeaf = store(d, Map.of("n", 20));
                var freshChild = store(d, Map.of("leaf", freshLeaf));
                original.replace("data", freshChild);
                freshLeaf.replace("n", 22);
                assertSame(child, first.get("data"));
                assertSame(child, second.get("data"));
                deny("E_COPY_READONLY", () -> child.replace("leaf", freshLeaf));
                deny("E_COPY_READONLY", () -> leaf.replace("n", 99));
                first.close();
                assertThrows(IllegalStateException.class, () -> first.shallowSnapshot(2, 4));
                deny("E_COPY_READONLY", () -> leaf.replace("n", 99));
                assertEquals(10, leaf.get("n"));
                assertEquals(22, freshLeaf.get("n"));
            }
        } finally {
            // Close the first snapshot even if an earlier assertion fails.
            try { first.close(); } catch (IllegalStateException alreadyClosed) {
                assertTrue(alreadyClosed.getMessage().startsWith("E_COPY_STALE"));
            }
        }
        assertFalse(child.isReadOnly());
        leaf.replace("n", 12);
        assertEquals(12, leaf.get("n"));
    }

    @Test void successiveSnapshotUsesReplacedRootAndFailedCopyIsAtomic() {
        var d = new GuestClassSlots.Domain();
        var old = store(d, Map.of("n", 10));
        var replacement = store(d, Map.of("n", 20));
        var original = store(d, Map.of("data", old));
        try (var first = original.shallowSnapshot(1, 2)) {
            first.replace("data", replacement);
            deny("E_COPY_BUDGET", () -> first.shallowSnapshot(0, 2));
            assertFalse(replacement.isReadOnly());
            assertTrue(old.isReadOnly());
            try (var second = first.shallowSnapshot(1, 2)) {
                assertSame(replacement, second.get("data"));
                assertSame(old, original.get("data"));
                deny("E_COPY_READONLY", () -> replacement.replace("n", 99));
                var fresh = store(d, Map.of("n", 30));
                second.replace("data", fresh);
                fresh.replace("n", 32);
                assertSame(replacement, first.get("data"));
                assertEquals(32, fresh.get("n"));
            }
            replacement.replace("n", 21);
            assertTrue(old.isReadOnly());
        }
        assertFalse(old.isReadOnly());
    }

    @Test void replacementChecksTransitiveDomainLayoutAndOpaquePayloadBeforePublication() {
        var d = new GuestClassSlots.Domain();
        var child = store(d, Map.of("n", 10));
        var original = store(d, Map.of("data", child));
        var foreign = store(new GuestClassSlots.Domain(), Map.of("n", 20));
        var unpublished = new GuestClassSlots(d, Map.of("n", 30));
        try (var snapshot = original.shallowSnapshot(3, 4)) {
            deny("E_COPY_DOMAIN", () -> snapshot.replace("data", store(d, Map.of("nested", foreign))));
            assertThrows(IllegalStateException.class,
                    () -> snapshot.replace("data", store(d, Map.of("nested", unpublished))));
            deny("E_COPY_UNPROVEN", () -> snapshot.replace("data", store(d, Map.of("nested", new Object()))));
            assertSame(child, snapshot.get("data"));
            assertSame(child, original.get("data"));
            assertTrue(child.isReadOnly());
            assertFalse(foreign.isReadOnly());
        }
        assertFalse(child.isReadOnly());
    }

    @Test void replacementMustNotDependOnAnotherSnapshotsLease() {
        var d = new GuestClassSlots.Domain();
        var own = store(d, Map.of("n", 10));
        var other = store(d, Map.of("n", 20));
        var a = store(d, Map.of("data", own));
        var b = store(d, Map.of("data", other));
        try (var first = a.shallowSnapshot(2, 3)) {
            try (var unrelated = b.shallowSnapshot(1, 2)) {
                deny("E_COPY_SHALLOW_ALIAS", () -> first.replace("data", other));
                deny("E_COPY_SHALLOW_ALIAS", () -> first.replace("data", store(d, Map.of("nested", other))));
                assertSame(own, first.get("data"));
                assertSame(other, unrelated.get("data"));
                var wrapper = store(d, Map.of("nested", own));
                first.replace("data", wrapper);
                deny("E_COPY_READONLY", () -> own.replace("n", 99));
                assertSame(wrapper, first.get("data"));
            }
            assertFalse(other.isReadOnly());
            other.replace("n", 21);
            assertTrue(own.isReadOnly());
        }
        assertFalse(own.isReadOnly());
    }

    @Test void replacementTraversalTerminatesForCyclesAndDoesNotFreezeFreshNodes() {
        var d = new GuestClassSlots.Domain();
        var original = store(d, Map.of("data", 0));
        var fresh = store(d, Map.of("next", 0, "n", 20));
        fresh.replace("next", fresh);
        try (var first = original.shallowSnapshot(1, 3)) {
            first.replace("data", fresh);
            assertSame(fresh, first.get("data"));
            assertFalse(fresh.isReadOnly());
            fresh.replace("n", 22);
            try (var second = first.shallowSnapshot(1, 3)) {
                assertSame(fresh, second.get("data"));
                deny("E_COPY_READONLY", () -> fresh.replace("n", 99));
            }
            fresh.replace("n", 23);
            assertEquals(23, fresh.get("n"));
            assertEquals(0, original.get("data"));
        }
    }

    @Test void rootBackEdgesRejectAtomicallyWhileDescendantCyclesRemainAdmissible() {
        var d = new GuestClassSlots.Domain();
        var original = store(d, Map.of("data", 0, "count", 1));
        original.replace("data", original);
        deny("E_COPY_SHALLOW_ALIAS", () -> original.shallowSnapshot(1, 4));
        assertFalse(original.isReadOnly());
        original.replace("count", 2);

        var child = store(d, Map.of("next", original));
        original.replace("data", child);
        deny("E_COPY_SHALLOW_ALIAS", () -> original.shallowSnapshot(2, 5));
        assertFalse(original.isReadOnly());
        assertFalse(child.isReadOnly());
        assertSame(original, child.get("next"));

        // A cycle wholly inside the retained subgraph is safe: the root
        // is not reachable from any retained edge and remains writable.
        child.replace("next", child);
        try (var snapshot = original.shallowSnapshot(1, 3)) {
            assertSame(child, snapshot.get("data"));
            assertTrue(child.isReadOnly());
            assertFalse(original.isReadOnly());
            original.replace("count", 3);
            snapshot.replace("count", 4);
            assertEquals(3, original.get("count"));
            assertEquals(4, snapshot.get("count"));
            deny("E_COPY_READONLY", () -> child.replace("next", 0));
        }
        assertFalse(child.isReadOnly());
        child.replace("next", 0);
    }

    @Test void exceptionalScopeExitReleasesOnlyItsOwnSnapshotLoan() {
        var d = new GuestClassSlots.Domain();
        var child = store(d, Map.of("n", 20));
        var original = store(d, Map.of("data", child));
        try (var survivor = original.shallowSnapshot(1, 2)) {
            var marker = new IllegalStateException("test unwind");
            var thrown = assertThrows(IllegalStateException.class, () -> {
                try (var temporary = original.shallowSnapshot(1, 2)) {
                    assertSame(child, temporary.get("data"));
                    throw marker;
                }
            });
            assertSame(marker, thrown);
            assertSame(child, survivor.get("data"));
            deny("E_COPY_READONLY", () -> child.replace("n", 22));
        }
        child.replace("n", 22);
        assertEquals(22, child.get("n"));
    }

    @Test void flatSnapshotQuotaIsReleasedExactlyOnce() {
        var d = new GuestClassSlots.Domain();
        var original = store(d, Map.of("count", 1));
        var snapshots = new GuestClassSlots.ShallowSnapshot[65536];
        try {
            for (int i = 0; i < snapshots.length; i++) {
                snapshots[i] = original.shallowSnapshot(0, 1);
            }
            deny("E_COPY_BUDGET", () -> original.shallowSnapshot(0, 1));
            assertFalse(original.isReadOnly());
            original.replace("count", 2);
            assertEquals(1, snapshots[0].get("count"));
            snapshots[0].close();
            assertThrows(IllegalStateException.class, snapshots[0]::close);
            snapshots[0] = original.shallowSnapshot(0, 1);
            assertEquals(2, snapshots[0].get("count"));
            deny("E_COPY_BUDGET", () -> original.shallowSnapshot(0, 1));
        } finally {
            for (var snapshot : snapshots) {
                if (snapshot != null) snapshot.close();
            }
        }
        try (var recovered = original.shallowSnapshot(0, 1)) {
            assertEquals(2, recovered.get("count"));
        }
    }

    @Test void snapshotCloseAndSuccessorPublicationLinearize() throws Exception {
        var d = new GuestClassSlots.Domain();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 1000; i++) {
                var leaf = store(d, Map.of("n", 20));
                var root = store(d, Map.of("data", leaf));
                var parent = root.shallowSnapshot(1, 2);
                var barrier = new CyclicBarrier(2);
                var successor = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return parent.shallowSnapshot(1, 2);
                    } catch (IllegalStateException e) {
                        if (!e.getMessage().startsWith("E_COPY_STALE")) throw e;
                        return null;
                    }
                });
                var retire = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    parent.close();
                    return true;
                });
                assertTrue(retire.get(10, TimeUnit.SECONDS));
                try (var next = successor.get(10, TimeUnit.SECONDS)) {
                    if (next != null) {
                        assertSame(leaf, next.get("data"));
                        deny("E_COPY_READONLY", () -> leaf.replace("n", 22));
                    } else {
                        assertFalse(leaf.isReadOnly());
                    }
                }
                assertFalse(leaf.isReadOnly());
                leaf.replace("n", 22);
                assertEquals(22, leaf.get("n"));
            }
        }
    }

    @Test void unrelatedLiveSourceCannotBecomeProtectedDescendant() {
        var d = new GuestClassSlots.Domain();
        var original = store(d, Map.of("count", 1));
        var wrapper = store(d, Map.of("nested", original));
        var unrelated = store(d, Map.of("data", 0));
        try (var first = original.shallowSnapshot(0, 1);
             var second = unrelated.shallowSnapshot(3, 4)) {
            deny("E_COPY_SHALLOW_ALIAS", () -> wrapper.shallowSnapshot(3, 4));
            deny("E_COPY_SHALLOW_ALIAS", () -> second.replace("data", wrapper));
            assertEquals(0, second.get("data"));
            assertFalse(original.isReadOnly());
            original.replace("count", 2);
            first.replace("count", 3);
            assertEquals(2, original.get("count"));
            assertEquals(3, first.get("count"));
        }
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
