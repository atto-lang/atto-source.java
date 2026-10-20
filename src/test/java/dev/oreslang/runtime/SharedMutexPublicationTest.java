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

            shared.validateRuntimePublicationCommit(runtimeA);
            shared.commitRuntimePublicationAfterAdmission();
            shared.abortRuntimePublication(runtimeA);

            assertTrue(shared.bindToRuntime(runtimeA));
            assertFalse(
                    shared.bindToRuntime(runtimeB),
                    "once any admitted message publishes a SharedMutex, later failed sends must not unbind it");
        }
    }

    @Test
    void publicationReservationOverflowAndCorruptionFailBeforeMutation()
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
            assertEquals(Integer.MAX_VALUE, pendingField.getInt(shared));
            assertSame(runtimeA, ownerField.get(shared));
            assertFalse(shared.reserveRuntimePublication(runtimeB));
            assertEquals(Integer.MAX_VALUE, pendingField.getInt(shared));

            pendingField.setInt(shared, 1);
            ownerField.set(shared, null);
            var corrupt = assertThrows(
                    IllegalStateException.class,
                    () -> shared.reserveRuntimePublication(runtimeA));
            assertTrue(corrupt.getMessage().contains(
                    "publication reservation state is corrupted"));
            assertEquals(1, pendingField.getInt(shared));
            assertNull(ownerField.get(shared));
        }
    }

    @Test
    void commitValidationRejectsCorruptionBeforeAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});
            assertTrue(shared.reserveRuntimePublication(runtime));

            var pendingField = shared.getClass().getDeclaredField(
                    "pendingPublications");
            pendingField.setAccessible(true);
            pendingField.setInt(shared, 0);

            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> shared.validateRuntimePublicationCommit(runtime));
            assertTrue(failure.getMessage().contains(
                    "commit without matching reservation"));

            // Repair only the synthetic witness and prove ordinary rollback.
            pendingField.setInt(shared, 1);
            shared.abortRuntimePublication(runtime);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sealedMailboxAdmissionRollsBackFirstPublicationAndTransportAccounting()
            throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var actor = runtimeA.<Object>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, java.util.concurrent.TimeUnit.SECONDS);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell =
                    ((java.util.Map<Object, Object>) actorsField.get(runtimeA))
                            .get(actor.id());
            assertNotNull(cell);

            var sealingField = cell.getClass().getDeclaredField("sealing");
            sealingField.setAccessible(true);
            sealingField.setBoolean(cell, true);

            var shared = OresMutex.shared(new int[]{0});
            assertThrows(
                    ActorRuntime.ActorSealedException.class,
                    () -> actor.send(java.util.List.of(shared)));

            assertEquals(0L, runtimeA.sharedMemoryBytes(),
                    "failed physical admission must refund message transport accounting");
            assertTrue(shared.bindToRuntime(runtimeB),
                    "aborted first publication must not retain runtime A");
            assertFalse(shared.bindToRuntime(runtimeA));

            sealingField.setBoolean(cell, false);
            actor.stop();
            assertTrue(actor.awaitTermination(
                    5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void acceptedMailboxPublicationCommitsAllReservedMutexes() throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var received = new java.util.concurrent.CountDownLatch(1);
            var actor = runtimeA.<Object>spawnShared(
                    () -> (message, context) -> received.countDown());
            actor.getReady().get(5, java.util.concurrent.TimeUnit.SECONDS);

            var first = OresMutex.shared(new int[]{1});
            var second = OresMutex.shared(new int[]{2});

            actor.send(java.util.List.of(first, second));
            assertTrue(received.await(
                    5, java.util.concurrent.TimeUnit.SECONDS));

            assertTrue(first.bindToRuntime(runtimeA));
            assertTrue(second.bindToRuntime(runtimeA));
            assertFalse(first.bindToRuntime(runtimeB));
            assertFalse(second.bindToRuntime(runtimeB));

            var pendingField = first.getClass().getDeclaredField(
                    "pendingPublications");
            var publishedField = first.getClass().getDeclaredField(
                    "publishedToRuntime");
            pendingField.setAccessible(true);
            publishedField.setAccessible(true);
            assertEquals(0, pendingField.getInt(first));
            assertTrue(publishedField.getBoolean(first));

            pendingField = second.getClass().getDeclaredField(
                    "pendingPublications");
            publishedField = second.getClass().getDeclaredField(
                    "publishedToRuntime");
            pendingField.setAccessible(true);
            publishedField.setAccessible(true);
            assertEquals(0, pendingField.getInt(second));
            assertTrue(publishedField.getBoolean(second));

            actor.stop();
            assertTrue(actor.awaitTermination(
                    5, java.util.concurrent.TimeUnit.SECONDS));
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
