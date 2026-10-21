package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Dependency admission is independent of guest scheduling and mailbox locks. */
final class ActorRequestDependencyGraphTest {
    private static ActorRequestDependencyGraph.ActorIdentity actor(String name) {
        return new ActorRequestDependencyGraph.ActorIdentity(name, 1);
    }

    @Test
    void rejectsSelfCallWithoutRetainingAnEdge() {
        var graph = new ActorRequestDependencyGraph(8);
        assertThrows(ActorRequestDependencyGraph.CyclicRequestException.class,
                () -> graph.suspendUntilReply(actor("a"), actor("a")));
        assertEquals(0, graph.activeCount());
    }

    @Test
    void rejectsSerialSupervisorCycleAndAcceptsAfterUnwind() {
        var graph = new ActorRequestDependencyGraph(8);
        var actor = actor("a");
        var supervisor = actor("supervisor");
        try (var request = graph.suspendUntilReply(actor, supervisor)) {
            assertThrows(ActorRequestDependencyGraph.CyclicRequestException.class,
                    () -> graph.suspendUntilReply(supervisor, actor));
            assertEquals(1, graph.activeCount());
        }
        try (var response = graph.suspendUntilReply(supervisor, actor)) {
            assertEquals(1, graph.activeCount());
        }
        assertEquals(0, graph.activeCount());
    }

    @Test
    void detectsLongCyclesWithoutRecursiveTraversal() {
        var graph = new ActorRequestDependencyGraph(1024);
        var leases = new java.util.ArrayList<ActorRequestDependencyGraph.Lease>();
        for (int i = 0; i < 700; i++) {
            leases.add(graph.suspendUntilReply(actor("node" + i), actor("node" + (i + 1))));
        }
        assertThrows(ActorRequestDependencyGraph.CyclicRequestException.class,
                () -> graph.suspendUntilReply(actor("node700"), actor("node0")));
        assertEquals(700, graph.activeCount());
        for (int i = leases.size() - 1; i >= 0; i--) leases.get(i).close();
        assertEquals(0, graph.activeCount());
    }

    @Test
    void duplicatedDependenciesReleaseIndependentlyAndCloseIsIdempotent() {
        var graph = new ActorRequestDependencyGraph(2);
        var first = graph.suspendUntilReply(actor("a"), actor("b"));
        var second = graph.suspendUntilReply(actor("a"), actor("b"));
        assertThrows(IllegalStateException.class,
                () -> graph.suspendUntilReply(actor("b"), actor("c")));
        assertThrows(ActorRequestDependencyGraph.CyclicRequestException.class,
                () -> graph.suspendUntilReply(actor("b"), actor("a")));
        first.close();
        first.close();
        assertEquals(1, graph.activeCount());
        assertThrows(ActorRequestDependencyGraph.CyclicRequestException.class,
                () -> graph.suspendUntilReply(actor("b"), actor("a")));
        second.close();
        assertEquals(0, graph.activeCount());
        try (var reverse = graph.suspendUntilReply(actor("b"), actor("a"))) {
            assertEquals(1, graph.activeCount());
        }
        assertEquals(0, graph.activeCount());
    }

    @Test
    void actorRestartCreatesDifferentDependencyIdentity() {
        var graph = new ActorRequestDependencyGraph(3);
        var a = actor("a");
        var b = actor("b");
        try (var wait = graph.suspendUntilReply(a, b)) {
            var nextB = new ActorRequestDependencyGraph.ActorIdentity("b", 2);
            try (var fresh = graph.suspendUntilReply(nextB, a)) {
                assertEquals(2, graph.activeCount());
            }
        }
        assertEquals(0, graph.activeCount());
    }
}
