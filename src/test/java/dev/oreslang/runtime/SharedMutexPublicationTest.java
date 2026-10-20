package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SharedMutexPublicationTest {
    @Test
    void abortedFirstPublicationDoesNotPermanentlyClaimRuntime() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});

            assertTrue(shared.reserveRuntimePublication(runtimeA));
            shared.abortRuntimePublication(runtimeA);

            assertTrue(
                    shared.bindToRuntime(runtimeB),
                    "a failed mailbox admission must leave an unpublished SharedMutex available to another runtime");
            assertFalse(shared.bindToRuntime(runtimeA));
        }
    }

    @Test
    void committedPublicationSurvivesAnotherAbortedReservation() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});

            assertTrue(shared.reserveRuntimePublication(runtimeA));
            assertTrue(shared.reserveRuntimePublication(runtimeA));

            shared.commitRuntimePublication(runtimeA);
            shared.abortRuntimePublication(runtimeA);

            assertTrue(shared.bindToRuntime(runtimeA));
            assertFalse(
                    shared.bindToRuntime(runtimeB),
                    "once any admitted message publishes a SharedMutex, later failed sends must not unbind it");
        }
    }

    @Test
    void publicationReservationOverflowFailsBeforeCounterWrapOrNewBinding()
            throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});

            var pendingField = shared.getClass().getDeclaredField(
                    "pendingPublications");
            var ownerField = shared.getClass().getDeclaredField("owningRuntime");
            pendingField.setAccessible(true);
            ownerField.setAccessible(true);

            assertTrue(shared.reserveRuntimePublication(runtimeA));
            pendingField.setInt(shared, Integer.MAX_VALUE);

            var overflow = assertThrows(
                    IllegalStateException.class,
                    () -> shared.reserveRuntimePublication(runtimeA));
            assertTrue(overflow.getMessage().contains(
                    "publication reservation capacity exhausted"));
            assertEquals(Integer.MAX_VALUE, pendingField.getInt(shared),
                    "overflow refusal must not wrap or consume a reservation");
            assertSame(runtimeA, ownerField.get(shared));

            assertFalse(shared.reserveRuntimePublication(runtimeB),
                    "foreign-runtime refusal must remain side-effect free");
            assertEquals(Integer.MAX_VALUE, pendingField.getInt(shared));
            assertSame(runtimeA, ownerField.get(shared));
        }
    }

    @Test
    void impossiblePendingReservationWithoutOwnerFailsClosed()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});

            var pendingField = shared.getClass().getDeclaredField(
                    "pendingPublications");
            var ownerField = shared.getClass().getDeclaredField("owningRuntime");
            pendingField.setAccessible(true);
            ownerField.setAccessible(true);

            pendingField.setInt(shared, 1);
            ownerField.set(shared, null);

            var corrupted = assertThrows(
                    IllegalStateException.class,
                    () -> shared.reserveRuntimePublication(runtime));
            assertTrue(corrupted.getMessage().contains(
                    "publication reservation state is corrupted"));
            assertEquals(1, pendingField.getInt(shared));
            assertNull(ownerField.get(shared),
                    "fail-closed validation must not invent an owner");
        }
    }

    @Test
    void foreignRuntimeCannotReserveWhilePublicationIsPending() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});

            assertTrue(shared.reserveRuntimePublication(runtimeA));
            assertFalse(shared.reserveRuntimePublication(runtimeB));

            shared.abortRuntimePublication(runtimeA);
            assertTrue(shared.reserveRuntimePublication(runtimeB));
            shared.abortRuntimePublication(runtimeB);
        }
    }
}
