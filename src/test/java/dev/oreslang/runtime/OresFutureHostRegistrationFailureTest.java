package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

final class OresFutureHostRegistrationFailureTest {

    @Test
    void fatalCallbackRegistrarErrorBeforeSettlementMustEscape() {
        InternalError fatal = new InternalError("VM failure while registering callback");
        assertSame(fatal, assertThrows(InternalError.class,
                () -> OresFuture.fromCallback(callback -> { throw fatal; })));

        LinkageError linkage = new LinkageError("missing host integration");
        assertSame(linkage, assertThrows(LinkageError.class,
                () -> OresFuture.fromCallback(callback -> { throw linkage; })));
    }

    @Test
    void fatalCallbackRegistrarErrorAfterSettlementMustStillEscape() {
        InternalError fatal = new InternalError("VM failure after callback fired");
        assertSame(fatal, assertThrows(InternalError.class,
                () -> OresFuture.fromCallback(callback -> {
                    callback.resolve(42);
                    throw fatal;
                })));
    }

    @Test
    void benignCallbackRegistrarErrorCannotOverwritePublishedValue() throws Exception {
        OresFuture<Integer> future = OresFuture.fromCallback(callback -> {
            callback.resolve(42);
            throw new IllegalArgumentException("late registrar exception");
        });
        assertEquals(42, future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void completionStageObserverRegistrationFailureRejectsInsteadOfLeakingPendingFuture() {
        CompletableFuture<Integer> hostile = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Integer> whenComplete(
                    BiConsumer<? super Integer, ? super Throwable> callback) {
                throw new IllegalStateException("host observer registration failed");
            }
        };
        OresFuture<Integer> future = OresFuture.from(hostile);
        assertTrue(future.isDone(), "failed registration must not strand a pending Future");
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(0, TimeUnit.MILLISECONDS));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals("host observer registration failed", thrown.getCause().getMessage());
    }

    @Test
    void completionStageObserverRegistrationFailureAfterNotificationPreservesResult()
            throws Exception {
        CompletableFuture<Integer> hostile = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Integer> whenComplete(
                    BiConsumer<? super Integer, ? super Throwable> callback) {
                callback.accept(42, null);
                throw new IllegalArgumentException("late host observer failure");
            }
        };
        OresFuture<Integer> future = OresFuture.from(hostile);
        assertEquals(42, future.get(0, TimeUnit.MILLISECONDS));
    }

    @Test
    void fatalCompletionStageObserverRegistrationFailureMustEscape() {
        InternalError fatal = new InternalError("VM failure during stage registration");
        CompletableFuture<Integer> hostile = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Integer> whenComplete(
                    BiConsumer<? super Integer, ? super Throwable> callback) {
                throw fatal;
            }
        };
        assertSame(fatal, assertThrows(InternalError.class,
                () -> OresFuture.from(hostile)));
    }

    @Test
    void cancellationExceptionRetainsCancellationOutcomeWithInconsistentHostFlag() {
        CompletableFuture<Integer> host = new CompletableFuture<>() {
            @Override
            public boolean isCancelled() {
                return false;
            }
        };
        host.completeExceptionally(new java.util.concurrent.CancellationException("cancelled"));
        OresFuture<Integer> future = OresFuture.from(host);
        assertTrue(future.isDone());
        assertTrue(future.isCancelled(), "cancelled host outcome must not become failed");
    }

    @Test
    void brokenHostCancellationProbeFailsAdapterRatherThanLeavingPending()
            throws Exception {
        CompletableFuture<Integer> host = new CompletableFuture<>() {
            @Override
            public boolean isCancelled() {
                throw new IllegalStateException("host cancellation probe failed");
            }
        };
        host.completeExceptionally(new IllegalArgumentException("host failed"));
        OresFuture<Integer> future = OresFuture.from(host);
        assertTrue(future.isDone(), "bad host probe cannot strand an adapter in PENDING");
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> future.get(0, TimeUnit.MILLISECONDS));
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals("host cancellation probe failed", error.getCause().getMessage());
    }
}
