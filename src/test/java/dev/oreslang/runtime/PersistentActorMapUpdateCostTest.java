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
    void scalarReplacementStillPreservesCanonicalNoOp() {
        var first = PersistentActorMap.empty().with("x", 1234);
        assertSame(first, first.with("x", Integer.valueOf(1234)));
        var updated = first.with("x", 1235);
        assertEquals(1234, first.get("x"));
        assertEquals(1235, updated.get("x"));
    }
}
