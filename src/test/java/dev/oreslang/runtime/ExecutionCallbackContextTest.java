package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionCallbackContextTest {

    @Test
    void foreignCallbackCarriesProvenanceButNeverExecutionAuthority() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicReference<OresFuture.Callback<Integer>> callback = new AtomicReference<>();
            AtomicReference<ExecutionContext> origin = new AtomicReference<>();
            AtomicLong originDispatch = new AtomicLong();
            AtomicReference<ExecutionCallbackContext> observedCallback = new AtomicReference<>();
            AtomicReference<ExecutionContext> observedActiveExecution = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch notification = new CountDownLatch(1);
            AtomicInteger pc = new AtomicInteger();

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    origin.set(OresScheduler.currentExecutionContext());
                    originDispatch.set(OresScheduler.currentDispatchId());
                    OresFuture<Integer> source = OresFuture.fromCallback(callback::set);
                    source.whenCompleteRuntime((value, failure) -> {
                        observedCallback.set(ExecutionCallbackContext.current());
                        observedActiveExecution.set(OresScheduler.currentExecutionContext());
                        notification.countDown();
                    });
                    registered.countDown();
                    return OresScheduler.await(source);
                }

                assertSame(origin.get(), OresScheduler.currentExecutionContext(),
                        "logical task identity must survive callback-driven await");
                assertNotEquals(originDispatch.get(), OresScheduler.currentDispatchId(),
                        "guest resume must use a fresh scheduler dispatch");
                assertEquals(41, resume.value());
                return OresScheduler.done(42);
            });

            Thread producer = Thread.ofPlatform().start(() -> {
                try {
                    assertTrue(registered.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertNull(OresScheduler.currentExecutionContext());
                assertNull(ExecutionCallbackContext.current());
                callback.get().resolve(41);
                assertNull(ExecutionCallbackContext.current(),
                        "callback provenance must not leak after producer resolution");
            });
            producer.join();

            assertTrue(notification.await(5, TimeUnit.SECONDS));
            assertEquals(42, result.get(5, TimeUnit.SECONDS));

            ExecutionCallbackContext observed = observedCallback.get();
            assertNotNull(observed);
            assertEquals(origin.get().id(), observed.sourceExecution());
            assertEquals(originDispatch.get(), observed.sourceDispatchId());
            assertEquals(ExecutionCallbackContext.Boundary.HOST_CALLBACK, observed.boundary());
            assertNull(observedActiveExecution.get(),
                    "callback provenance must never impersonate active Oreslang execution");
        }
    }

    @Test
    void completionStageResolutionCarriesCapturedProvenanceAcrossThreads() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CompletableFuture<Integer> host = new CompletableFuture<>();
            AtomicReference<ExecutionContext.ExecutionId> origin = new AtomicReference<>();
            AtomicReference<ExecutionCallbackContext> observed = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch notification = new CountDownLatch(1);
            AtomicInteger pc = new AtomicInteger();

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    origin.set(OresScheduler.currentExecutionContext().id());
                    OresFuture<Integer> source = OresFuture.from(host);
                    source.whenCompleteRuntime((value, failure) -> {
                        observed.set(ExecutionCallbackContext.current());
                        assertNull(OresScheduler.currentExecutionContext());
                        notification.countDown();
                    });
                    registered.countDown();
                    return OresScheduler.await(source);
                }
                return OresScheduler.done((Integer) resume.value() + 1);
            });

            Thread.ofPlatform().start(() -> {
                try {
                    assertTrue(registered.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                host.complete(41);
            }).join();
            assertTrue(notification.await(5, TimeUnit.SECONDS));
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertNotNull(observed.get());
            assertEquals(origin.get(), observed.get().sourceExecution());
        }
    }

    @Test
    void nestedCallbackScopesRestoreLifoAndClearAfterFailure() {
        ExecutionCallbackContext outer = new ExecutionCallbackContextTestCapture()
                .capture(ExecutionCallbackContext.Boundary.IO);
        ExecutionCallbackContext inner = new ExecutionCallbackContextTestCapture()
                .captureFrom(outer, ExecutionCallbackContext.Boundary.TIMER);

        assertNull(ExecutionCallbackContext.current());
        var thrown = assertThrows(IllegalStateException.class, () ->
                ExecutionCallbackContext.runCaptured(outer, () -> {
                    assertSame(outer, ExecutionCallbackContext.current());
                    ExecutionCallbackContext.runCaptured(inner, () -> {
                        assertSame(inner, ExecutionCallbackContext.current());
                        throw new IllegalStateException("boom");
                    });
                }));
        assertEquals("boom", thrown.getMessage());
        assertNull(ExecutionCallbackContext.current());
    }

    /**
     * Builds test-only provenance through real scheduler capture so production constructors remain
     * private and callers cannot forge callback lineage.
     */
    private static final class ExecutionCallbackContextTestCapture {
        ExecutionCallbackContext capture(ExecutionCallbackContext.Boundary boundary) {
            try (OresScheduler scheduler = new OresScheduler(1)) {
                try {
                    return scheduler.start(resume -> OresScheduler.done(
                            ExecutionCallbackContext.captureCurrent(boundary)))
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        }

        ExecutionCallbackContext captureFrom(
                ExecutionCallbackContext parent,
                ExecutionCallbackContext.Boundary boundary) {
            AtomicReference<ExecutionCallbackContext> captured = new AtomicReference<>();
            ExecutionCallbackContext.runCaptured(parent,
                    () -> captured.set(ExecutionCallbackContext.captureCurrent(boundary)));
            return captured.get();
        }
    }
}
