package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionCallbackTraceIntegrationTest {

    private static OresAsyncTrace.Trace trace(String symbol, int line) {
        return OresAsyncTrace.root(new OresAsyncTrace.Frame(
                symbol,
                new OresAsyncTrace.SourceSite(
                        "callback-trace-integration.ores",
                        line,
                        7,
                        "ctx-trace-v1")));
    }

    @Test
    void foreignCallbackFailureCarriesContextAndImmutableTraceWithoutGuestAuthority()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicReference<OresFuture.Callback<Integer>> completion = new AtomicReference<>();
            AtomicReference<ExecutionContext> origin = new AtomicReference<>();
            AtomicLong originDispatch = new AtomicLong();
            AtomicReference<ExecutionCallbackContext> callbackSeen = new AtomicReference<>();
            AtomicReference<ExecutionContext> activeSeen = new AtomicReference<>();
            AtomicReference<OresAsyncTrace.Trace> mutableTraceSeen = new AtomicReference<>();
            AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
            AtomicReference<Throwable> producerProblem = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch notified = new CountDownLatch(1);
            AtomicInteger pc = new AtomicInteger();
            OresAsyncTrace.Trace logical = trace("foreign_callback_origin", 10);

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(logical)) {
                        origin.set(OresScheduler.currentExecutionContext());
                        originDispatch.set(OresScheduler.currentDispatchId());

                        OresFuture<Integer> source = OresFuture.fromCallback(callback -> {
                            completion.set(callback);
                            registered.countDown();
                        });
                        source.whenCompleteRuntime((value, failure) -> {
                            callbackSeen.set(ExecutionCallbackContext.current());
                            activeSeen.set(OresScheduler.currentExecutionContext());
                            mutableTraceSeen.set(OresAsyncTrace.current());
                            waiterFailure.set(failure);
                            notified.countDown();
                        });
                        return OresScheduler.await(source);
                    }
                }

                assertSame(origin.get(), OresScheduler.currentExecutionContext(),
                        "logical execution identity must survive await/resume");
                assertNotEquals(originDispatch.get(), OresScheduler.currentDispatchId(),
                        "guest continuation must re-enter through a fresh dispatch");
                assertNotNull(resume.failure());
                Throwable failure = OresFuture.unwrap(resume.failure());
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new RuntimeException(failure);
            });

            IllegalStateException failure =
                    new IllegalStateException("foreign callback failed");
            Thread producer = Thread.ofPlatform().start(() -> {
                try {
                    assertTrue(registered.await(5, TimeUnit.SECONDS));
                    assertNull(OresScheduler.currentExecutionContext());
                    assertNull(ExecutionCallbackContext.current());
                    assertNull(OresAsyncTrace.current());
                    completion.get().reject(failure);
                    assertNull(ExecutionCallbackContext.current(),
                            "callback provenance leaked after producer settlement");
                    assertNull(OresAsyncTrace.current(),
                            "mutable trace leaked onto producer carrier");
                } catch (Throwable problem) {
                    producerProblem.set(problem);
                }
            });
            producer.join();

            assertNull(producerProblem.get(),
                    () -> "producer callback failed: " + producerProblem.get());
            assertTrue(notified.await(5, TimeUnit.SECONDS));
            ExecutionException terminal = assertThrows(
                    ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertSame(failure, terminal.getCause());
            assertSame(failure, waiterFailure.get());

            ExecutionCallbackContext observed = callbackSeen.get();
            assertNotNull(observed);
            assertEquals(origin.get().id(), observed.sourceExecution());
            assertEquals(originDispatch.get(), observed.sourceDispatchId());
            assertEquals(ExecutionCallbackContext.Boundary.HOST_CALLBACK, observed.boundary());
            assertNull(activeSeen.get(),
                    "callback provenance must never impersonate active execution");
            assertNull(mutableTraceSeen.get(),
                    "runtime waiter must not install the mutable guest trace");

            OresAsyncTrace.CallbackSnapshot snapshot = observed.traceSnapshot();
            assertNotNull(snapshot);
            assertEquals(OresAsyncTrace.BoundaryKind.CALLBACK_ADAPTER, snapshot.boundary());
            assertTrue(snapshot.logicalTrace().contains("foreign_callback_origin"));
            assertTrue(snapshot.logicalTrace().contains("--- callback-adapter"));

            long attached = Arrays.stream(failure.getSuppressed())
                    .filter(OresAsyncTrace.LogicalAsyncStackTrace.class::isInstance)
                    .count();
            assertEquals(1, attached,
                    "one callback failure should attach one immutable causal snapshot");
        }
    }

    @Test
    void nestedCallbackRegistrationOnProducerExtendsImmutableTraceWithoutAmbientTrace()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicReference<OresFuture.Callback<Integer>> outerCompletion = new AtomicReference<>();
            AtomicReference<OresFuture.Callback<Integer>> innerCompletion = new AtomicReference<>();
            AtomicReference<ExecutionCallbackContext> nestedObserved = new AtomicReference<>();
            AtomicReference<Throwable> producerProblem = new AtomicReference<>();
            CountDownLatch outerRegistered = new CountDownLatch(1);
            CountDownLatch innerRegistered = new CountDownLatch(1);
            CountDownLatch nestedNotified = new CountDownLatch(1);
            OresAsyncTrace.Trace logical = trace("nested_callback_origin", 30);

            OresFuture<Void> registration = scheduler.startSync(() -> {
                try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(logical)) {
                    OresFuture<Integer> outer = OresFuture.fromCallback(callback -> {
                        outerCompletion.set(callback);
                        outerRegistered.countDown();
                    });
                    outer.whenCompleteRuntime((value, failure) -> {
                        assertNull(OresScheduler.currentExecutionContext());
                        assertNull(OresAsyncTrace.current(),
                                "host callback bookkeeping must not install mutable trace");
                        assertNotNull(ExecutionCallbackContext.current());

                        OresFuture<Integer> inner = OresFuture.fromCallback(callback -> {
                            innerCompletion.set(callback);
                            innerRegistered.countDown();
                        });
                        inner.whenCompleteRuntime((innerValue, innerFailure) -> {
                            nestedObserved.set(ExecutionCallbackContext.current());
                            assertNull(OresScheduler.currentExecutionContext());
                            assertNull(OresAsyncTrace.current());
                            nestedNotified.countDown();
                        });
                    });
                }
                return null;
            });

            registration.get(5, TimeUnit.SECONDS);
            assertTrue(outerRegistered.await(5, TimeUnit.SECONDS));

            Thread firstProducer = Thread.ofPlatform().start(() -> {
                try {
                    assertNull(ExecutionCallbackContext.current());
                    assertNull(OresAsyncTrace.current());
                    outerCompletion.get().resolve(1);
                    assertNull(ExecutionCallbackContext.current());
                    assertNull(OresAsyncTrace.current());
                } catch (Throwable problem) {
                    producerProblem.set(problem);
                }
            });
            firstProducer.join();
            assertNull(producerProblem.get());
            assertTrue(innerRegistered.await(5, TimeUnit.SECONDS));

            IllegalArgumentException failure =
                    new IllegalArgumentException("nested callback failed");
            Thread secondProducer = Thread.ofPlatform().start(() -> {
                try {
                    assertNull(ExecutionCallbackContext.current());
                    assertNull(OresAsyncTrace.current());
                    innerCompletion.get().reject(failure);
                    assertNull(ExecutionCallbackContext.current());
                    assertNull(OresAsyncTrace.current());
                } catch (Throwable problem) {
                    producerProblem.compareAndSet(null, problem);
                }
            });
            secondProducer.join();

            assertNull(producerProblem.get());
            assertTrue(nestedNotified.await(5, TimeUnit.SECONDS));

            ExecutionCallbackContext nested = nestedObserved.get();
            assertNotNull(nested);
            assertNotNull(nested.traceSnapshot());
            String rendered = nested.traceSnapshot().logicalTrace();
            assertTrue(rendered.contains("nested_callback_origin"));
            assertTrue(count(rendered, "--- callback-adapter") >= 2,
                    () -> "nested callback trace did not preserve both boundaries:\n" + rendered);

            assertTrue(Arrays.stream(failure.getSuppressed())
                    .filter(OresAsyncTrace.LogicalAsyncStackTrace.class::isInstance)
                    .map(OresAsyncTrace.LogicalAsyncStackTrace.class::cast)
                    .anyMatch(logicalTrace ->
                            count(logicalTrace.logicalTrace(), "--- callback-adapter") >= 2));
        }
    }

    @Test
    void completionStageFailureUsesUnifiedContextTraceCapture() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            CompletableFuture<Integer> host = new CompletableFuture<>();
            AtomicReference<ExecutionCallbackContext> observed = new AtomicReference<>();
            AtomicReference<OresAsyncTrace.Trace> mutableTraceSeen = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch notified = new CountDownLatch(1);
            AtomicInteger pc = new AtomicInteger();
            OresAsyncTrace.Trace logical = trace("completion_stage_origin", 50);

            OresFuture<Integer> result = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(logical)) {
                        OresFuture<Integer> source = OresFuture.from(host);
                        source.whenCompleteRuntime((value, failure) -> {
                            observed.set(ExecutionCallbackContext.current());
                            mutableTraceSeen.set(OresAsyncTrace.current());
                            notified.countDown();
                        });
                        registered.countDown();
                        return OresScheduler.await(source);
                    }
                }
                Throwable failure = OresFuture.unwrap(resume.failure());
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(failure);
            });

            assertTrue(registered.await(5, TimeUnit.SECONDS));
            IllegalStateException failure =
                    new IllegalStateException("host stage failed");
            Thread.ofPlatform().start(() -> host.completeExceptionally(failure)).join();

            assertTrue(notified.await(5, TimeUnit.SECONDS));
            ExecutionException terminal = assertThrows(
                    ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertSame(failure, terminal.getCause());
            assertNull(mutableTraceSeen.get());

            ExecutionCallbackContext callback = observed.get();
            assertNotNull(callback);
            assertNotNull(callback.traceSnapshot());
            assertTrue(callback.traceSnapshot().logicalTrace()
                    .contains("completion_stage_origin"));
            assertTrue(Arrays.stream(failure.getSuppressed())
                    .anyMatch(OresAsyncTrace.LogicalAsyncStackTrace.class::isInstance));
        }
    }

    @Test
    void errorThrownAfterSynchronousSettlementIsNotSwallowed() {
        AssertionError fatal = new AssertionError("control failure after resolve");
        AssertionError thrown = assertThrows(AssertionError.class, () ->
                OresFuture.fromCallback(completion -> {
                    completion.resolve(41);
                    throw fatal;
                }));
        assertSame(fatal, thrown);
        assertEquals(0, fatal.getSuppressed().length);
    }

    @Test
    void hostFatalFailureIsNeverDecoratedWithCallbackDiagnostics() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            AtomicReference<OresFuture.Callback<Integer>> completion = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            OresAsyncTrace.Trace logical = trace("fatal_callback_origin", 70);

            OresFuture<Integer> source = scheduler.startSync(() -> {
                try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(logical)) {
                    return OresFuture.fromCallback(callback -> {
                        completion.set(callback);
                        registered.countDown();
                    });
                }
            }).get(5, TimeUnit.SECONDS);

            assertTrue(registered.await(5, TimeUnit.SECONDS));
            VirtualMachineError fatal = new VirtualMachineError("fatal callback") { };
            Thread.ofPlatform().start(() -> completion.get().reject(fatal)).join();

            ExecutionException terminal = assertThrows(
                    ExecutionException.class,
                    () -> source.get(5, TimeUnit.SECONDS));
            assertSame(fatal, terminal.getCause());
            assertEquals(0, fatal.getSuppressed().length,
                    "host-fatal failures must not be decorated with logical diagnostics");
        }
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
