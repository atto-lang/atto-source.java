package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HOST-ONLY local registry inspection. Not guest virtual actors, authenticated
 * supervisors, distributed location, or production CONTROL routing.
 */
final class VirtualActorLocalObservationTest {
    @Test
    void unknownInspectionDoesNotCreateARecordOrConsumeIdentityBudget() {
        var registry = new VirtualActorActivationRegistry("runtime", 1, 1);
        var missing = new VirtualActorActivationRegistry.Identity("runtime", "Worker", "missing");
        assertNull(registry.inspectExistingForHost(missing));
        assertNull(registry.inspectExistingForHost(missing));
        var real = registry.resolve("Worker", "present");
        assertNotNull(registry.inspectForHost(real));
        assertEquals(VirtualActorActivationRegistry.LocalPhase.INACTIVE,
                registry.inspectExistingForHost(real.identity()).phase());
        assertThrows(IllegalStateException.class, () -> registry.resolve("Worker", "second"));
    }

    @Test
    void snapshotIsImmutableAndNeverPublishesOrAdmitsAnActivation() {
        var registry = new VirtualActorActivationRegistry("runtime", 2, 1);
        var ref = registry.resolve("Worker", "one");
        var cold = registry.inspectForHost(ref);
        assertEquals(0L, cold.incarnation());
        assertEquals(0, cold.admitted());
        assertEquals(VirtualActorActivationRegistry.LocalPhase.INACTIVE, cold.phase());

        var initializing = registry.activate(ref);
        var pending = registry.inspectForHost(ref);
        assertEquals(VirtualActorActivationRegistry.LocalPhase.INITIALIZING, pending.phase());
        assertEquals(initializing.incarnation(), pending.incarnation());
        assertFalse(initializing.active());
        assertThrows(IllegalStateException.class, () -> registry.tryAdmit(initializing));

        registry.publish(initializing);
        var running = registry.inspectForHost(ref);
        assertEquals(VirtualActorActivationRegistry.LocalPhase.ACTIVE, running.phase());
        assertEquals(0, running.admitted());
        assertEquals(VirtualActorActivationRegistry.LocalPhase.INACTIVE, cold.phase());
        assertEquals(VirtualActorActivationRegistry.LocalPhase.INITIALIZING, pending.phase());

        var admission = registry.tryAdmit(initializing);
        assertNotNull(admission);
        assertEquals(1, registry.inspectForHost(ref).admitted());
        assertNull(registry.tryAdmit(initializing));
        assertThrows(IllegalStateException.class, () -> registry.deactivate(initializing));
        admission.settle(VirtualActorActivationRegistry.Disposition.PROCESSED);
        assertEquals(0, registry.inspectForHost(ref).admitted());
        assertEquals(0, running.admitted(), "old inspection is not a live actor view");
        registry.deactivate(initializing);
        assertEquals(VirtualActorActivationRegistry.LocalPhase.INACTIVE,
                registry.inspectForHost(ref).phase());
    }

    @Test
    void failedInitializerBurnsGenerationAndStaleObservationCannotRetarget() {
        var registry = new VirtualActorActivationRegistry("runtime", 1, 1);
        var ref = registry.resolve("Worker", "one");
        var old = registry.activate(ref);
        registry.abort(old);
        var afterAbort = registry.inspectForHost(ref);
        assertEquals(VirtualActorActivationRegistry.LocalPhase.INACTIVE, afterAbort.phase());
        var fresh = registry.activate(ref);
        assertTrue(fresh.incarnation() > old.incarnation());
        assertEquals(old.incarnation(), afterAbort.incarnation());
        registry.publish(fresh);
        assertThrows(IllegalStateException.class, old::requireActive);
        assertEquals(fresh.incarnation(), registry.inspectForHost(ref).incarnation());
        assertEquals(old.incarnation(), afterAbort.incarnation());
    }

    @Test
    void foreignRegistryAndMalformedHostKeysNeverRevealAnotherRegistry() {
        var first = new VirtualActorActivationRegistry("runtime", 1, 1);
        var second = new VirtualActorActivationRegistry("runtime", 1, 1);
        var ref = first.resolve("Worker", "one");
        assertThrows(IllegalArgumentException.class, () -> second.inspectForHost(ref));
        assertNull(second.inspectExistingForHost(ref.identity()));
        assertNull(first.inspectExistingForHost(
                new VirtualActorActivationRegistry.Identity("other-runtime", "Worker", "one")));
        assertNull(first.inspectExistingForHost(
                new VirtualActorActivationRegistry.Identity("runtime", "Worker", "x".repeat(10_000))));
        assertNull(first.inspectExistingForHost(
                new VirtualActorActivationRegistry.Identity("runtime", "Worker", "cafe\u0301")));
        assertNotNull(first.inspectForHost(ref));
    }

    @Test
    void closeInvalidatesLocalObservationWithoutForgingDoneWitness() {
        var registry = new VirtualActorActivationRegistry("runtime", 1, 1);
        var ref = registry.resolve("Worker", "one");
        var activation = registry.activate(ref);
        registry.publish(activation);
        var accepted = registry.tryAdmit(activation);
        assertThrows(IllegalStateException.class, registry::close);
        assertEquals(VirtualActorActivationRegistry.LocalPhase.ACTIVE,
                registry.inspectForHost(ref).phase());
        accepted.settle(VirtualActorActivationRegistry.Disposition.CANCELLED_NOTIFIED);
        registry.close();
        assertEquals(VirtualActorActivationRegistry.LocalPhase.CLOSED,
                registry.inspectForHost(ref).phase());
        assertFalse(activation.active());
        assertThrows(IllegalStateException.class, () -> registry.tryAdmit(activation));
    }
}
