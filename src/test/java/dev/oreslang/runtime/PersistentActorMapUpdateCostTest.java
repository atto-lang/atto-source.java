package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Updating a persistent root must perform HAMT path copying, not an
 * unbounded Map.equals walk over every nested immutable value.
 */
@Timeout(20)
final class PersistentActorMapUpdateCostTest {
    private static PersistentActorMap nested(int depth) {
        var result = PersistentActorMap.empty();
        for (int i = 0; i < depth; ++i) {
            result = PersistentActorMap.empty().with("next", result);
        }
        return result;
    }

    @Test
    void replacingDeepImmutableRootDoesNotRecurseThroughValueGraph() {
        var left = nested(8192);
        var right = nested(8192);
        assertNotSame(left, right);
        var prior = PersistentActorMap.empty().with("child", left);
        var updated = assertDoesNotThrow(() -> prior.with("child", right));
        assertSame(left, prior.get("child"));
        assertSame(right, updated.get("child"));
        assertNotSame(prior, updated);
        assertSame(updated, updated.with("child", right),
                "same persistent subtree reference is an O(1) no-op");
    }

    @Test
    void collisionUpdateDoesNotTraverseDistinctDeepSubtrees() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        var left = nested(4096);
        var right = nested(4096);
        var collision = PersistentActorMap.empty()
                .with("Aa", left)
                .with("BB", 17);
        var updated = assertDoesNotThrow(() -> collision.with("Aa", right));
        assertSame(left, collision.get("Aa"));
        assertSame(right, updated.get("Aa"));
        assertEquals(17, updated.get("BB"));
        assertSame(updated, updated.with("Aa", right));
    }

    @Test
    void deepPersistentRootEqualityAndHashCodeUseBoundedCallStack() {
        var left = nested(8192);
        var right = nested(8192);
        assertNotSame(left, right);
        assertTrue(assertDoesNotThrow(() -> left.equals(right)));
        assertTrue(assertDoesNotThrow(() -> right.equals(left)));
        assertEquals(assertDoesNotThrow(left::hashCode),
                assertDoesNotThrow(right::hashCode),
                "equal persistent roots must have the same map hash");
        var changed = PersistentActorMap.empty().with("leaf", 17);
        for (int i = 0; i < 8192; i++)
            changed = PersistentActorMap.empty().with("next", changed);
        var distinct = changed;
        assertFalse(assertDoesNotThrow(() -> left.equals(distinct)));
        assertFalse(assertDoesNotThrow(() -> distinct.equals(left)));
    }

    @Test
    void repeatedSharedSubtreesCompareAndHashWithoutRepeatedRecursion() {
        var sharedLeft = nested(4096);
        var sharedRight = nested(4096);
        var first = PersistentActorMap.empty()
                .with("left", sharedLeft).with("right", sharedLeft);
        var second = PersistentActorMap.empty()
                .with("left", sharedRight).with("right", sharedRight);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first, first.with("left", sharedRight),
                "equal nested values remain equal without recursive update shortcuts");
        assertNotSame(first, first.with("left", sharedRight),
                "distinct nested roots still produce a new path copy");
    }

    @Test
    void deepNestedOrdinaryMapsCompareWithoutStackOverflow() {
        var persistent = nested(8192);
        java.util.Map<String, Object> ordinary = java.util.Map.of();
        for (int i = 0; i < 8192; i++)
            ordinary = java.util.Map.of("next", ordinary);
        var actual = ordinary;
        assertTrue(assertDoesNotThrow(() -> persistent.equals(actual)),
                "comparison with a standard nested Map must not delegate recursive equals");
        assertEquals(assertDoesNotThrow(persistent::hashCode),
                assertDoesNotThrow(() -> nested(8192).hashCode()),
                "equivalent persistent maps must hash without recursive calls");
        // A deeply nested ordinary Map may itself overflow while computing
        // hashCode(), so do not use its non-stack-safe implementation as an oracle.
    }

    @Test
    void differingNestedOrdinaryMapsReturnFalseWithoutRecursion() {
        var persistent = nested(4096);
        java.util.Map<String, Object> ordinary = java.util.Map.of("unexpected", 1);
        for (int i = 0; i < 4096; i++)
            ordinary = java.util.Map.of("next", ordinary);
        var actual = ordinary;
        assertFalse(assertDoesNotThrow(() -> persistent.equals(actual)));
    }

    @Test
    void plainNullAndNonmapComparisonRemainsFalse() {
        var persistent = PersistentActorMap.empty().with("n", 1);
        assertFalse(persistent.equals(null));
        assertFalse(persistent.equals("not-a-map"));
        assertFalse(persistent.equals(java.util.Map.of("n", nullSafeMismatch())));
    }

    private static Object nullSafeMismatch() {
        return "different";
    }

    @Test
    void standardMapHashAndEqualityContractsRemainCompatible() {
        var child = nested(512);
        var parent = PersistentActorMap.empty()
                .with("child", child).with("number", 42);
        java.util.Map<String, Object> oracle = java.util.Map.of(
                "child", child, "number", 42);
        assertEquals(oracle, parent);
        assertEquals(parent, oracle);
        assertEquals(oracle.hashCode(), parent.hashCode());
        assertEquals(parent, PersistentActorMap.empty()
                .with("number", 42).with("child", child));
    }

    @Test
    void scalarReplacementStillPreservesCanonicalNoOp() {
        var first = PersistentActorMap.empty().with("x", 1234);
        assertSame(first, first.with("x", Integer.valueOf(1234)));
        var updated = first.with("x", 1235);
        assertEquals(1234, first.get("x"));
        assertEquals(1235, updated.get("x"));
    }
}
