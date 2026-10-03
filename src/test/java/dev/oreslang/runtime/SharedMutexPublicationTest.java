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
    @Test
    void boundMutexRejectsUnsafeMutationBeforePermitRelease() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var foreign = runtimeB.<String>spawnSharedTrusted(
                    factoryContext -> (message, context) -> { });
            MutableState state = new MutableState("safe");
            var shared = OresMutex.shared(state);

            assertTrue(shared.bindToRuntime(runtimeA));

            var guard = shared.lock();
            state.child = foreign;

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    guard::release);
            assertTrue(failure.getMessage().contains("another ActorRuntime"));
            assertTrue(shared.isPoisoned(),
                    "unsafe mutation must poison the SharedMutex rather than publish it");

            shared.recover(value -> {
                value.child = "repaired";
                return null;
            });
            assertFalse(shared.isPoisoned());
        }
    }

    private static final class MutableState implements OresMutex.SharedState {
        private Object child;

        private MutableState(Object child) {
            this.child = child;
        }

        @Override
        public Iterable<?> sharedStateChildren() {
            return java.util.Collections.singletonList(child);
        }
    }

}
