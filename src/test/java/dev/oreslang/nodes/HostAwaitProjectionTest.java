package dev.oreslang.nodes;

import dev.oreslang.runtime.Awaitable;
import dev.oreslang.runtime.OresFuture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

final class HostAwaitProjectionTest {
    @Test
    void liveGuardRejectsBeforeHostAwaitableProjection() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        Awaitable<String> host = () -> {
            invocations.incrementAndGet();
            return OresFuture.completed("ok");
        };

        var error = assertThrows(IllegalStateException.class,
                () -> HostAwaitProjection.project(host, () -> {
                    checks.incrementAndGet();
                    return true;
                }));
        assertTrue(error.getMessage().contains("MutexGuard"));
        assertEquals(1, checks.get(), "check must happen exactly once");
        assertEquals(0, invocations.get(), "guard denial must not invoke host code");

        assertEquals("ok", HostAwaitProjection.project(host, () -> false).join());
        assertEquals(1, invocations.get(), "projection allowed after guard release");
    }

    private static final class SpyStage extends CompletableFuture<String> {
        private final AtomicInteger registrations = new AtomicInteger();

        @Override
        public CompletableFuture<String> whenComplete(
                BiConsumer<? super String, ? super Throwable> action) {
            registrations.incrementAndGet();
            return super.whenComplete(action);
        }
    }

    @Test
    void liveGuardRejectsBeforePendingCompletionStageRegistration() {
        SpyStage stage = new SpyStage();
        var error = assertThrows(IllegalStateException.class,
                () -> HostAwaitProjection.adapt(stage, () -> true));
        assertTrue(error.getMessage().contains("MutexGuard"));
        assertEquals(0, stage.registrations.get(),
                "denied await must not arm a host callback");

        OresFuture<?> future = HostAwaitProjection.adapt(stage, () -> false);
        assertEquals(1, stage.registrations.get());
        stage.complete("later");
        assertEquals("later", future.join());
    }

    @Test
    void evenSettledHostStageMustNotRegisterCallbackUnderGuard() {
        SpyStage stage = new SpyStage();
        stage.complete("already settled");
        assertThrows(IllegalStateException.class,
                () -> HostAwaitProjection.adapt(stage, () -> true));
        assertEquals(0, stage.registrations.get());

        assertEquals("already settled",
                HostAwaitProjection.adapt(stage, () -> false).join());
        assertEquals(1, stage.registrations.get());
    }

    @Test
    void failedProjectionNeverTriggersAnotherHostRegistration() {
        AtomicInteger projections = new AtomicInteger();
        Awaitable<String> host = () -> {
            projections.incrementAndGet();
            throw new IllegalArgumentException("host failed");
        };
        assertThrows(IllegalStateException.class,
                () -> HostAwaitProjection.project(host, () -> true));
        assertEquals(0, projections.get());
        assertThrows(IllegalArgumentException.class,
                () -> HostAwaitProjection.project(host, () -> false));
        assertEquals(1, projections.get());
    }
}
