package dev.oreslang.runtime.hpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class HpcCyclicGraphTest {

    @Test
    void directSelfCycleDoesNotNeedReferenceCountingOrRecursiveDrop() {
        try (HpcCyclicGraph<String> graph = new HpcCyclicGraph<>(2, 2)) {
            var node = graph.add("self");
            graph.connect(node, node);
            assertEquals(List.of(node), graph.neighbors(node));
            graph.clear();
            assertEquals(0, graph.size());
            assertTrue(graph.resolve(node).isEmpty());
        }
    }

    @Test
    void parentChildCycleUsesNonOwningGenerationalEdges() {
        try (HpcCyclicGraph<String> graph = new HpcCyclicGraph<>(3, 3)) {
            var parent = graph.add("parent");
            var child = graph.add("child");
            graph.connect(parent, child);
            graph.connect(child, parent);
            assertEquals(List.of(child), graph.neighbors(parent));
            assertEquals(List.of(parent), graph.neighbors(child));
            assertEquals("parent", graph.resolve(parent).orElseThrow());

            graph.remove(parent);
            assertTrue(graph.resolve(parent).isEmpty());
            assertTrue(graph.neighbors(child).isEmpty(),
                    "stale weak-like backedge must not upgrade");
            assertEquals("child", graph.resolve(child).orElseThrow());
        }
    }

    @Test
    void slotReuseDoesNotResurrectOldEdgesOrHandles() {
        try (HpcCyclicGraph<String> graph = new HpcCyclicGraph<>(2, 2)) {
            var root = graph.add("root");
            var previous = graph.add("previous");
            graph.connect(root, previous);
            graph.remove(previous);
            var next = graph.add("next");

            assertEquals(previous.slot(), next.slot());
            assertNotEquals(previous.generation(), next.generation());
            assertTrue(graph.resolve(previous).isEmpty());
            assertTrue(graph.neighbors(root).isEmpty());
            assertEquals("next", graph.resolve(next).orElseThrow());
            assertThrows(IllegalStateException.class, () -> graph.connect(previous, root));
            graph.connect(root, next);
            assertEquals(List.of(next), graph.neighbors(root));
        }
    }

    @Test
    void crossArenaHandlesCannotBeInsertedOrResolved() {
        try (HpcCyclicGraph<String> first = new HpcCyclicGraph<>(1, 2);
             HpcCyclicGraph<String> second = new HpcCyclicGraph<>(1, 2)) {
            var a = first.add("a");
            var b = second.add("b");
            assertTrue(first.resolve(b).isEmpty());
            assertThrows(IllegalStateException.class, () -> first.connect(a, b));
            assertFalse(first.disconnect(a, b));
            assertNotEquals(a, b);
        }
    }

    @Test
    void repeatedClearNeverAliasesPriorGeneration() {
        try (HpcCyclicGraph<Integer> graph = new HpcCyclicGraph<>(1, 1)) {
            var old = graph.add(42);
            for (int i = 0; i < 30; i++) {
                graph.clear();
                var newer = graph.add(i);
                assertEquals(old.slot(), newer.slot());
                assertTrue(graph.resolve(old).isEmpty());
                assertNotEquals(old.generation(), newer.generation());
            }
        }
    }

    @Test
    void hardCapacityAndPerNodeEdgeBudgetRejectOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new HpcCyclicGraph<>(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new HpcCyclicGraph<>(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new HpcCyclicGraph<>(65_537, 1));
        assertThrows(IllegalArgumentException.class, () -> new HpcCyclicGraph<>(65_536, 100));
        assertThrows(ArithmeticException.class, () ->
                new HpcCyclicGraph<>(65_536, Integer.MAX_VALUE));

        try (HpcCyclicGraph<Integer> graph = new HpcCyclicGraph<>(2, 1)) {
            var a = graph.add(1);
            var b = graph.add(2);
            graph.connect(a, b);
            graph.connect(a, b); // idempotent, does not exhaust quota
            assertEquals(1, graph.neighbors(a).size());
            assertThrows(IllegalStateException.class, () -> graph.connect(a, a));
            assertThrows(IllegalStateException.class, () -> graph.add(3));
        }
    }

    @Test
    void closeIsIdempotentAndDropsAllReferencesWithoutFollowingCycles() {
        HpcCyclicGraph<Object> graph = new HpcCyclicGraph<>(2, 2);
        Object value = new Object();
        var a = graph.add(value);
        var b = graph.add(value);
        graph.connect(a, b);
        graph.connect(b, a);

        graph.close();
        graph.close();
        assertEquals(0, graph.size());
        assertTrue(graph.resolve(a).isEmpty());
        assertTrue(graph.resolve(b).isEmpty());
        assertThrows(IllegalStateException.class, () -> graph.add("after close"));
        assertThrows(IllegalStateException.class, () -> graph.clear());
    }

    @Test
    void cyclesRemainBoundedUnderManyRecycleRounds() {
        try (HpcCyclicGraph<Integer> graph = new HpcCyclicGraph<>(4, 3)) {
            for (int i = 0; i < 200; i++) {
                var a = graph.add(i);
                var b = graph.add(i + 1);
                graph.connect(a, b);
                graph.connect(b, a);
                graph.remove(a);
                graph.remove(b);
                assertEquals(0, graph.size());
            }
        }
    }
}
