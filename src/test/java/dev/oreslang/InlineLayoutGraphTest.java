package dev.oreslang;

import dev.oreslang.types.InlineLayoutGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class InlineLayoutGraphTest {
    private static InlineLayoutGraph.Edge edge(int owner, int target, boolean inline) {
        return new InlineLayoutGraph.Edge(owner, target, inline);
    }

    @Test
    void inlineCyclesAreRejectedEvenWhenAConstructorHasABaseCase() {
        var direct = assertThrows(IllegalArgumentException.class,
                () -> InlineLayoutGraph.admit(1, List.of(edge(0, 0, true))));
        assertTrue(direct.getMessage().contains("E_TYPE_LAYOUT_CYCLE"));
        var mutual = assertThrows(IllegalArgumentException.class,
                () -> InlineLayoutGraph.admit(2, List.of(edge(0, 1, true), edge(1, 0, true))));
        assertTrue(mutual.getMessage().contains("E_TYPE_LAYOUT_CYCLE"));
    }

    @Test
    void verifiedIndirectionCutsOnlyThePhysicalDependency() {
        assertEquals(List.of(1, 0), InlineLayoutGraph.admit(2,
                List.of(edge(0, 1, true), edge(1, 0, false))));
        assertEquals(List.of(0), InlineLayoutGraph.admit(1, List.of(edge(0, 0, false))));
    }

    @Test
    void sharedInlineDependenciesAreOrderedBeforeOwners() {
        var order = InlineLayoutGraph.admit(3,
                List.of(edge(0, 1, true), edge(0, 2, true), edge(1, 2, true)));
        assertEquals(List.of(2, 1, 0), order);
    }

    @Test
    void longChainDoesNotUseRecursiveHostStack() {
        List<InlineLayoutGraph.Edge> edges = new ArrayList<>();
        for (int i = 0; i < 16383; i++) edges.add(edge(i, i + 1, true));
        var order = InlineLayoutGraph.admit(16384, edges);
        assertEquals(16384, order.size());
        assertEquals(16383, order.getFirst());
        assertEquals(0, order.getLast());
        assertThrows(IllegalArgumentException.class, () -> InlineLayoutGraph.admit(16385, List.of()));
    }

    @Test
    void allEdgesCountAndUnknownIndirectTargetsAreRejected() {
        var repeated = edge(0, 0, false);
        assertEquals(List.of(0), InlineLayoutGraph.admit(1,
                java.util.Collections.nCopies(65536, repeated)));
        assertThrows(IllegalArgumentException.class, () -> InlineLayoutGraph.admit(1,
                java.util.Collections.nCopies(65537, repeated)));
        assertThrows(IllegalArgumentException.class, () -> InlineLayoutGraph.admit(1,
                List.of(edge(0, 1, false))));
    }
}
