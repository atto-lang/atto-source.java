package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the native staging gate, NOT an Oreslang source-level init implementation. */
final class ActorInitPublicationGateTest {

    private static ActorInitPublicationGate newGate() {
        return new ActorInitPublicationGate(List.of("store", "payment", "phase"));
    }

    @Test
    void completeStatePublishesOnceWithDeclarationOrder() {
        var gate = newGate();
        gate.stage("phase", "created");
        gate.stage("payment", "provider-a");
        gate.stage("store", "tenant-a");
        gate.seal();
        assertEquals(ActorInitPublicationGate.Phase.SEALED, gate.phase());
        AtomicInteger publishes = new AtomicInteger();
        String ref = gate.publish(state -> {
            publishes.incrementAndGet();
            assertEquals(List.of("store", "payment", "phase"),
                    List.copyOf(state.keySet()));
            assertEquals("tenant-a", state.get("store"));
            assertThrows(UnsupportedOperationException.class,
                    () -> state.put("phase", "hijacked"));
            return "opaque-ref-a";
        });
        assertEquals("opaque-ref-a", ref);
        assertEquals(1, publishes.get());
        assertEquals(ActorInitPublicationGate.Phase.PUBLISHED, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.publish(state -> "second-ref"));
        assertThrows(IllegalStateException.class,
                () -> gate.stage("phase", "changed"));
        assertEquals(1, publishes.get());
    }

    @Test
    void cannotPublishIncompleteActor() {
        var gate = newGate();
        gate.stage("store", "tenant-a");
        var failure = assertThrows(IllegalStateException.class, gate::seal);
        assertTrue(failure.getMessage().contains("payment"), failure.getMessage());
        assertEquals(ActorInitPublicationGate.Phase.FAILED, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.publish(state -> "invalid-ref"));
    }

    @Test
    void invalidSchemaAndDuplicateStateKeysFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorInitPublicationGate(List.of("a", "a")));
        var duplicate = newGate();
        duplicate.stage("store", "tenant-a");
        assertThrows(IllegalStateException.class,
                () -> duplicate.stage("store", "tenant-b"));
        assertEquals(ActorInitPublicationGate.Phase.FAILED, duplicate.phase());
        var unknown = newGate();
        assertThrows(IllegalStateException.class,
                () -> unknown.stage("other_tenant", "secret"));
        assertEquals(ActorInitPublicationGate.Phase.FAILED, unknown.phase());
        var nil = newGate();
        assertThrows(IllegalStateException.class,
                () -> nil.stage("store", null));
        assertEquals(ActorInitPublicationGate.Phase.FAILED, nil.phase());
    }

    @Test
    void noPrematureOrRepeatedPublication() {
        var gate = newGate();
        assertThrows(IllegalStateException.class,
                () -> gate.publish(state -> "too-early"));
        gate.stage("store", "tenant-a");
        gate.stage("payment", "provider-a");
        gate.stage("phase", "created");
        gate.seal();
        assertThrows(IllegalStateException.class,
                () -> gate.stage("phase", "changed-after-seal"));
        assertEquals("ref", gate.publish(state -> "ref"));
    }

    @Test
    void publisherFailureClearsStagingAndNeverRetries() {
        var gate = new ActorInitPublicationGate(List.of("count"));
        gate.stage("count", 1);
        gate.seal();
        assertThrows(IllegalStateException.class,
                () -> gate.publish(state -> {
                    throw new IllegalStateException("runtime allocation failure");
                }));
        assertEquals(ActorInitPublicationGate.Phase.FAILED, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.publish(state -> "retry"));
        assertThrows(IllegalStateException.class,
                () -> gate.stage("count", 2));
    }

    @Test
    void explicitAbortPreventsAnyPublication() {
        var gate = newGate();
        gate.stage("store", "tenant-a");
        gate.abort();
        gate.abort();
        assertEquals(ActorInitPublicationGate.Phase.FAILED, gate.phase());
        assertThrows(IllegalStateException.class, gate::seal);
        assertThrows(IllegalStateException.class,
                () -> gate.publish(state -> "ref"));
    }

    @Test
    void publishingCannotReenterGateOrIssueAnotherRef() {
        var gate = new ActorInitPublicationGate(List.of("count"));
        gate.stage("count", 1);
        gate.seal();
        assertEquals("ref", gate.publish(state -> {
            assertEquals(ActorInitPublicationGate.Phase.ISSUING, gate.phase());
            assertThrows(IllegalStateException.class,
                    () -> gate.publish(inner -> "nested-ref"));
            return "ref";
        }));
    }

    @Test
    void abortDuringIssuingCannotCorruptCommittedPublicationState() {
        var gate = new ActorInitPublicationGate(List.of("count"));
        gate.stage("count", 1L);
        gate.seal();
        assertEquals("ref", gate.publish(state -> {
            var rejected = assertThrows(IllegalStateException.class, gate::abort);
            assertTrue(rejected.getMessage().contains("while publication is issuing"));
            assertEquals(ActorInitPublicationGate.Phase.ISSUING, gate.phase());
            return "ref";
        }));
        assertEquals(ActorInitPublicationGate.Phase.PUBLISHED, gate.phase());
    }

    @Test
    void emptyStateCanBeInitializedAndPublished() {
        var gate = new ActorInitPublicationGate(List.of());
        gate.seal();
        assertEquals("ref", gate.publish(state -> {
            assertEquals(Map.of(), state);
            return "ref";
        }));
    }

    @Test
    void publishedActorCannotBeRetroactivelyInitAborted() {
        var gate = new ActorInitPublicationGate(List.of());
        gate.seal();
        gate.publish(state -> "ref");
        assertThrows(IllegalStateException.class, gate::abort);
    }
}
