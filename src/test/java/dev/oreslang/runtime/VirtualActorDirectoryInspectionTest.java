package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class VirtualActorDirectoryInspectionTest {
    @Test
    void addressRoundTripsCanonicalUnicodeIdentity() {
        try (var registry = new VirtualActorActivationRegistry("tenant-α", 4, 8)) {
            var reference = registry.resolve("ShoppingCart", "cafe\u0301");
            var address = VirtualActorAddressV1.encode(reference.identity());
            var decoded = VirtualActorAddressV1.decode(address);
            assertEquals(reference.identity(), decoded);
            assertEquals("café", decoded.key());
        }
    }

    @Test
    void inspectionByAddressDoesNotCreateOrActivateIdentity() {
        try (var registry = new VirtualActorActivationRegistry("tenant-a", 1, 2)) {
            var inspection = new VirtualActorDirectoryInspection(registry);
            var unknown = new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "missing");
            assertTrue(inspection.inspect(unknown).isEmpty());
            assertTrue(inspection.inspectAddress(VirtualActorAddressV1.encode(unknown)).isEmpty());

            // This succeeds only if the two inspections did not consume the sole identity slot.
            var reference = registry.resolve("Cart", "known");
            var snapshot = inspection.inspect(reference);
            assertEquals(VirtualActorDirectoryInspection.Lifecycle.NEVER_ACTIVATED,
                    snapshot.lifecycle());
            assertEquals(0, snapshot.activationGeneration());
            assertEquals(0, snapshot.outstandingAdmissions());
        }
    }

    @Test
    void inspectionReportsGenerationAndDoesNotChangeActivationState() {
        try (var registry = new VirtualActorActivationRegistry("tenant-a", 2, 2)) {
            var reference = registry.resolve("Cart", "42");
            var inspection = new VirtualActorDirectoryInspection(registry);
            var address = VirtualActorAddressV1.encode(reference.identity());

            var first = registry.activate(reference);
            assertEquals(VirtualActorDirectoryInspection.Lifecycle.ACTIVATING,
                    inspection.inspectAddress(address).orElseThrow().lifecycle());
            registry.publish(first);
            var admitted = registry.tryAdmit(first);
            var active = inspection.inspectAddress(address).orElseThrow();
            assertEquals(VirtualActorDirectoryInspection.Lifecycle.ACTIVE, active.lifecycle());
            assertEquals(1, active.activationGeneration());
            assertEquals(1, active.outstandingAdmissions());
            assertEquals(1, registry.outstanding(reference));

            assertThrows(IllegalStateException.class, () -> registry.deactivate(first));
            admitted.settle(VirtualActorActivationRegistry.Disposition.PROCESSED);
            registry.deactivate(first);
            var passive = inspection.inspect(reference);
            assertEquals(VirtualActorDirectoryInspection.Lifecycle.PASSIVATED, passive.lifecycle());
            assertEquals(1, passive.activationGeneration());
            assertEquals(0, passive.outstandingAdmissions());
        }
    }

    @Test
    void failedActivationBurnsGenerationAndOldHandleCannotPublish() {
        try (var registry = new VirtualActorActivationRegistry("tenant-a", 2, 2)) {
            var reference = registry.resolve("Cart", "42");
            var failed = registry.activate(reference);
            registry.abort(failed);
            assertThrows(IllegalStateException.class, () -> registry.publish(failed));
            var replacement = registry.activate(reference);
            assertEquals(2, replacement.incarnation());
            registry.publish(replacement);
            assertEquals(2, registry.inspect(reference).latestIncarnation());
        }
    }

    @Test
    void malformedAndNonCanonicalAddressesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> VirtualActorAddressV1.decode("atto-va2.YQ.Yg.Yw"));
        assertThrows(IllegalArgumentException.class, () -> VirtualActorAddressV1.decode("atto-va1.YQ==.Yg.Yw"));
        assertThrows(IllegalArgumentException.class, () -> VirtualActorAddressV1.decode("atto-va1._w.Yg.Yw"));
        assertThrows(IllegalArgumentException.class, () -> VirtualActorAddressV1.decode("atto-va1.YQ.Yg"));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorAddressV1.decode("atto-va1." + "A".repeat(3_000) + ".Yg.Yw"));
    }

    @Test
    void encoderRejectsMalformedOversizedAndNonCanonicalIdentityComponents() {
        assertThrows(IllegalArgumentException.class, () ->
                VirtualActorAddressV1.encode(new VirtualActorActivationRegistry.Identity(
                        "tenant-a", "Cart", "not-nfc-e\u0301")));
        assertThrows(IllegalArgumentException.class, () ->
                VirtualActorAddressV1.encode(new VirtualActorActivationRegistry.Identity(
                        "tenant-a", "Cart", "x".repeat(257))));
        assertThrows(IllegalArgumentException.class, () ->
                VirtualActorAddressV1.encode(new VirtualActorActivationRegistry.Identity(
                        "tenant-a", "Cart", "bad\nkey")));
        assertThrows(NullPointerException.class, () ->
                VirtualActorAddressV1.encode(new VirtualActorActivationRegistry.Identity(
                        null, "Cart", "42")));
    }

    @Test
    void addressFromAnotherDomainCannotInspectThisRegistry() {
        try (var registry = new VirtualActorActivationRegistry("tenant-a", 2, 2)) {
            var otherDomain = new VirtualActorActivationRegistry.Identity("tenant-b", "Cart", "42");
            assertTrue(new VirtualActorDirectoryInspection(registry).inspect(otherDomain).isEmpty());
        }
    }
    @Test
    void discardedLogicalReferenceCannotActivateOrInspectAHiddenSlot() {
        try (var registry = new VirtualActorActivationRegistry("tenant-a", 1, 2)) {
            var discarded = registry.resolve("Cart", "discarded");
            registry.discardUnactivated(discarded);
            assertTrue(registry.inspectIdentity(discarded.identity()).isEmpty());
            assertThrows(IllegalStateException.class, () -> registry.activate(discarded));
            assertThrows(IllegalStateException.class, () -> registry.inspect(discarded));
            assertThrows(IllegalStateException.class, () -> registry.outstanding(discarded));

            // Discarding a candidate must release its identity capacity, but
            // cannot leave behind a usable orphan activation authority.
            var replacement = registry.resolve("Cart", "current");
            var activation = registry.activate(replacement);
            registry.publish(activation);
            assertTrue(activation.active());
            registry.deactivate(activation);
        }
    }

    @Test
    void discardMustNotErasePassivatedGenerationHistory() {
        try (var registry = new VirtualActorActivationRegistry("tenant-a", 1, 2)) {
            var logical = registry.resolve("Cart", "history");
            var first = registry.activate(logical);
            registry.publish(first);
            registry.deactivate(first);
            assertThrows(IllegalStateException.class,
                    () -> registry.discardUnactivated(logical));
            assertEquals(1, registry.inspect(logical).latestIncarnation());
            var replacement = registry.activate(logical);
            assertEquals(2, replacement.incarnation());
            registry.publish(replacement);
            registry.deactivate(replacement);
        }
    }

    @Test
    void versionOneAddressMatchesTheCrossBackendCanonicalVector() {
        var identity = new VirtualActorActivationRegistry.Identity(
                "tenant-a", "7:defaultCart", "42");
        assertEquals("atto-va1.dGVuYW50LWE.NzpkZWZhdWx0Q2FydA.NDI",
                VirtualActorAddressV1.encode(identity));
        assertEquals(identity, VirtualActorAddressV1.decode(
                "atto-va1.dGVuYW50LWE.NzpkZWZhdWx0Q2FydA.NDI"));
    }

}
