package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorInvocationRuntimeTest {

    @Test
    void oneShotInvocationSelectsSharedOrPrivateMemoryMode() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.MemoryMode shared = runtime.invoke(
                    "ping",
                    ActorRuntime.MemoryPolicy.sharedHeap(),
                    (message, context) -> context.memory().mode());

            ActorRuntime.MemoryMode isolated = runtime.invoke(
                    "ping",
                    ActorRuntime.MemoryPolicy.privateArena(1024L * 1024L),
                    (message, context) -> {
                        assertTrue(Thread.currentThread().isVirtual());
                        assertTrue(context.memory().capacityBytes() >= 1024L * 1024L);
                        return context.memory().mode();
                    });

            assertEquals(ActorRuntime.MemoryMode.SHARED_HEAP, shared);
            assertEquals(ActorRuntime.MemoryMode.PRIVATE_ARENA, isolated);
        }
    }

    @Test
    void oneShotInvocationPropagatesFailureToCaller() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.invoke(
                            "boom",
                            ActorRuntime.MemoryPolicy.sharedHeap(),
                            (message, context) -> {
                                throw new IllegalStateException(message);
                            }));

            assertEquals("boom", failure.getMessage());
        }
    }

    @Test
    void oneShotInvocationFreezesItsReturnValue() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Object value = runtime.invoke(
                    "ok",
                    ActorRuntime.MemoryPolicy.sharedHeap(),
                    (message, context) -> new StringBuilder(message));

            fail("mutable host return should have been rejected, got " + value);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not Sendable"));
        }
    }
}
