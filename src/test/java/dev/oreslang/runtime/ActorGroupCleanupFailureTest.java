package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorGroupCleanupFailureTest {
    @Test
    void infrastructureErrorDoesNotStrandLaterHooksOrRegistryCleanup() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            AtomicInteger laterHook = new AtomicInteger();

            group.registerInfrastructureClose(() -> {
                throw new AssertionError("synthetic infrastructure teardown failure");
            });
            group.registerInfrastructureClose(laterHook::incrementAndGet);

            AssertionError failure = assertThrows(AssertionError.class, group::close);
            assertEquals("synthetic infrastructure teardown failure", failure.getMessage());
            assertEquals(1, laterHook.get(),
                    "one failing infrastructure hook must not strand later cleanup");
            assertTrue(group.closed());
            assertEquals(0, runtime.actorGroupCount(),
                    "group registry removal must complete before the original Error is rethrown");
        }
    }
}
