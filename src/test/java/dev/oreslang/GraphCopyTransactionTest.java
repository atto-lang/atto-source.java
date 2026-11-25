package dev.oreslang;

import dev.oreslang.types.DerivedGraphCopyPlan;
import dev.oreslang.types.GraphCopyTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.oreslang.types.DerivedGraphCopyPlan.*;
import static org.junit.jupiter.api.Assertions.*;

final class GraphCopyTransactionTest {
    private static final GraphCopyTransaction.Limits LIMITS =
            new GraphCopyTransaction.Limits(1000, 100, 1000, 1000);

    private static final Graph NODE = new Graph(0, List.of(
            new Instance("Node", List.of(new Field("value", 1),
                    new Field("left", 2), new Field("right", 2))),
            new Scalar("int"), new OptionalValue(0)));

    private static GraphCopyTransaction.Cell node(GraphCopyTransaction.Domain domain, long value) {
        var result = domain.instance("Node", 3);
        result.set(0, value);
        result.set(1, absent());
        result.set(2, absent());
        return result;
    }

    private static GraphCopyTransaction.Maybe absent() {
        return new GraphCopyTransaction.Maybe(false, null);
    }

    private static GraphCopyTransaction.Maybe some(Object value) {
        return new GraphCopyTransaction.Maybe(true, value);
    }

    private static GraphCopyTransaction.Cell edge(GraphCopyTransaction.Cell cell, int index) {
        return (GraphCopyTransaction.Cell) ((GraphCopyTransaction.Maybe) cell.get(index)).value();
    }

    private static GraphCopyTransaction.Cell copy(GraphCopyTransaction.Domain domain,
                                                   GraphCopyTransaction.Cell cell) {
        return (GraphCopyTransaction.Cell) GraphCopyTransaction.copy(
                NODE, cell, domain, LIMITS, new AtomicBoolean());
    }

    @Test
    void selfCycleIsPreservedInFreshRoot() {
        var d = new GraphCopyTransaction.Domain();
        var a = node(d, 42);
        a.set(1, some(a));
        var b = copy(d, a);
        assertNotSame(a, b);
        assertSame(b, edge(b, 1));
        assertSame(a, edge(a, 1));
        b.set(0, 99L);
        assertEquals(42L, a.get(0));
    }

    @Test
    void diamondAndMutualCyclePreserveIdentityWithoutSourceAliases() {
        var d = new GraphCopyTransaction.Domain();
        var a = node(d, 1);
        var b = node(d, 2);
        var c = node(d, 3);
        var shared = node(d, 4);
        a.set(1, some(b)); a.set(2, some(c));
        b.set(1, some(shared)); c.set(1, some(shared));
        shared.set(2, some(a));
        var cloned = copy(d, a);
        var l = edge(cloned, 1);
        var r = edge(cloned, 2);
        var s = edge(l, 1);
        assertSame(s, edge(r, 1));
        assertSame(cloned, edge(s, 2));
        assertNotSame(shared, s);
        assertNotSame(b, l);
        assertNotSame(c, r);
    }

    @Test
    void failedLateValidationDoesNotMutateSourceAndRetryWorks() {
        var d = new GraphCopyTransaction.Domain();
        var a = node(d, 1);
        var b = node(d, 2);
        a.set(1, some(b));
        b.set(0, "wrong scalar");
        assertThrows(IllegalArgumentException.class, () -> copy(d, a));
        assertEquals("wrong scalar", b.get(0));
        assertSame(b, edge(a, 1));
        b.set(0, 2L);
        assertEquals(2L, edge(copy(d, a), 1).get(0));
    }

    @Test
    void foreignCellAndCancellationFailBeforeReturningRoot() {
        var d = new GraphCopyTransaction.Domain();
        var a = node(d, 1);
        var foreign = node(new GraphCopyTransaction.Domain(), 2);
        a.set(1, some(foreign));
        assertThrows(IllegalArgumentException.class, () -> copy(d, a));
        assertSame(foreign, edge(a, 1));
        a.set(1, absent());
        assertThrows(IllegalArgumentException.class, () ->
                GraphCopyTransaction.copy(NODE, a, d, LIMITS, new AtomicBoolean(true)));
        assertEquals(1L, a.get(0));
    }

    @Test
    void allocationAndVisitBudgetsHaveExactBoundaries() {
        var d = new GraphCopyTransaction.Domain();
        var a = node(d, 1);
        a.set(1, some(a));
        var exact = new GraphCopyTransaction.Limits(5, 1, 3, 0);
        assertDoesNotThrow(() -> GraphCopyTransaction.copy(NODE, a, d, exact, new AtomicBoolean()));
        for (var limit : List.of(new GraphCopyTransaction.Limits(4, 1, 3, 0),
                new GraphCopyTransaction.Limits(5, 0, 3, 0),
                new GraphCopyTransaction.Limits(5, 1, 2, 0))) {
            assertThrows(IllegalArgumentException.class, () ->
                    GraphCopyTransaction.copy(NODE, a, d, limit, new AtomicBoolean()));
        }
    }

    @Test
    void emptyContainersCannotHideUnsupportedOrForgedDescriptors() {
        var d = new GraphCopyTransaction.Domain();
        var list = d.sequence("List", 0);
        for (var plan : List.of(
                new Graph(0, List.of(new Sequence("List", 1), new Scalar("uint"))),
                new Graph(0, List.of(new Sequence("List", 1), new Scalar(null))),
                new Graph(0, List.of(new Sequence(null, 1), new Scalar("int"))),
                new Graph(0, List.of(new Sequence("List", 1))),
                new Graph(0, List.of(new Sequence("List", 1), new OptionalValue(1))))) {
            assertThrows(IllegalArgumentException.class, () ->
                    GraphCopyTransaction.copy(plan, list, d, LIMITS, new AtomicBoolean()));
        }
    }

    @Test
    void tenThousandNodeCycleIsCopiedWithoutRecursiveIdentityTraversal() {
        var d = new GraphCopyTransaction.Domain();
        var source = node(d, 0);
        var cursor = source;
        for (int i = 1; i < 10000; i++) {
            var next = node(d, i);
            cursor.set(1, some(next));
            cursor = next;
        }
        cursor.set(1, some(source));
        var cloned = (GraphCopyTransaction.Cell) GraphCopyTransaction.copy(
                NODE, source, d, new GraphCopyTransaction.Limits(60000, 10000, 30000, 0),
                new AtomicBoolean());
        assertNotSame(source, cloned);
        cursor = cloned;
        for (int i = 0; i < 10000; i++) {
            assertEquals((long) i, cursor.get(0));
            cursor = edge(cursor, 1);
        }
        assertSame(cloned, cursor);
    }

    @Test
    void sameSourceUnderConflictingDescriptorsFailsClosed() {
        var plan = new Graph(0, List.of(
                new Instance("Pair", List.of(new Field("a", 1), new Field("b", 2))),
                new Instance("Leaf", List.of()), new Instance("Leaf", List.of())));
        var d = new GraphCopyTransaction.Domain();
        var pair = d.instance("Pair", 2);
        var leaf = d.instance("Leaf", 0);
        pair.set(0, leaf); pair.set(1, leaf);
        assertThrows(IllegalArgumentException.class, () ->
                GraphCopyTransaction.copy(plan, pair, d, LIMITS, new AtomicBoolean()));
        assertSame(pair.get(0), pair.get(1));
    }
}
