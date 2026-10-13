package dev.oreslang.runtime;

import dev.oreslang.runtime.pipeline.PipelineController;
import dev.oreslang.runtime.pipeline.PipelinePlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-only ActorRuntime bridge for versioned pipeline generations.
 * Returns the actual compiler-validated typed backward reply, after physical
 * DONE. Guest-language lowering and LLVM parity remain separate release gates.
 */
public final class ActorRuntimePipelineEngine implements PipelineController.Engine {
    private final ActorRuntime runtime;
    private final Executor controlExecutor;
    private final Map<String, PipelinePlan.ActorProvider> registry;
    private static final long ROLLBACK_RECEIPT_TIMEOUT_SECONDS = 5;
    private final AtomicReference<List<ActorRuntime.ActorRef<?>>> quarantined =
            new AtomicReference<>(List.of());

    public ActorRuntimePipelineEngine(
            ActorRuntime runtime, Executor trustedControlExecutor,
            Map<String, PipelinePlan.ActorProvider> registeredProviders) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.controlExecutor = Objects.requireNonNull(trustedControlExecutor, "trusted control executor");
        this.registry = Map.copyOf(Objects.requireNonNull(registeredProviders, "provider registry"));
    }

    @Override
    public void scheduleControl(Runnable task) {
        Objects.requireNonNull(task, "control action");
        controlExecutor.execute(() -> {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("pipeline control cannot run on an actor DATA carrier");
            }
            task.run();
        });
    }

    @Override
    public PipelineController.Session open(PipelinePlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!quarantined.get().isEmpty()) {
            throw new IllegalStateException(
                    "actor provider engine is quarantined after incomplete startup rollback");
        }
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException("pipeline providers may run only in trusted host CONTROL");
        }
        for (PipelinePlan.Stage stage : plan.stages()) {
            PipelinePlan.ActorProvider registered = registry.get(stage.providerId());
            if (registered == null || registered != stage.provider()) {
                throw new SecurityException("unregistered or forged actor provider: " + stage.providerId());
            }
        }
        List<ActorRuntime.ActorRef<?>> refs = new ArrayList<>();
        ActorRuntime.ActorStream stream;
        // Captures all successful allocations on the provider CONTROL thread,
        // including providers that allocate then throw without returning.
        try (ActorRuntime.ActorProvisioningScope allocation =
                     runtime.beginActorProvisioning()) {
            try {
            for (PipelinePlan.Stage stage : plan.stages()) {
                int before = allocation.acquiredActors().size();
                long allocationMark = runtime.actorAllocationMark();
                Object created = stage.provider().spawn();
                if (!(created instanceof ActorRuntime.ActorRef<?> ref)) {
                    throw new SecurityException("provider did not return an ActorRuntime.ActorRef: "
                            + stage.providerId());
                }
                // Never include a foreign runtime's actor in rollback.
                // A hostile registered provider cannot gain STOP authority
                // over an unrelated actor by making pipeline admission fail.
                if (!runtime.ownsActorRef(ref)) {
                    throw new SecurityException("pipeline provider returned a foreign runtime actor");
                }
                if (!runtime.ownsFreshActorRefSince(ref, allocationMark)) {
                    throw new SecurityException(
                            "pipeline actor provider must create a fresh actor for each stage");
                }
                List<ActorRuntime.ActorRef<?>> observed = allocation.acquiredActors();
                if (observed.size() != before + 1 ||
                        observed.getLast() != ref) {
                    throw new SecurityException(
                            "pipeline provider must allocate exactly its own single stage");
                }
                refs.add(ref);
            }
            // Atomically bind live unused same-runtime compiler-owned mailboxes.
            stream = runtime.bindLinearPipeline(refs);
            if (!stream.nominalDomainIdentity().equals(plan.nominalDomain())) {
                throw new SecurityException("planner nominal domain differs from compiler-owned runtime domain");
            }
            } catch (RuntimeException | Error failure) {
                rollbackStartup(allocation.acquiredActors(), failure);
                throw failure;
            }
        }
        return new BoundSession(plan, stream, List.copyOf(refs));
    }

    /**
     * STOP is a request, not an attested physical finalization. For all
     * provisionally acquired actors, await runtime-owned DONE settlements
     * before releasing the startup failure. Exceptional DONE proves terminal
     * finalization too. Unsettled DONE permanently quarantines this engine.
     */
    private void rollbackStartup(List<ActorRuntime.ActorRef<?>> acquired,
                                 Throwable startupFailure) {
        if (acquired.isEmpty()) return;
        List<ActorRuntime.ActorRef<?>> held = List.copyOf(acquired);
        Throwable cleanupFailure = null;
        for (ActorRuntime.ActorRef<?> ref : held) {
            try { ref.stop(); }
            catch (RuntimeException | Error failure) {
                if (cleanupFailure == null) cleanupFailure = failure;
                else cleanupFailure.addSuppressed(failure);
            }
        }
        boolean completed = true;
        for (ActorRuntime.ActorRef<?> ref : held) {
            try {
                ref.done().get(ROLLBACK_RECEIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException | CancellationException settledFailure) {
                // A failure is a valid physical terminal receipt.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                completed = false;
                if (cleanupFailure == null) cleanupFailure = interrupted;
            } catch (TimeoutException timeout) {
                completed = false;
                if (cleanupFailure == null) cleanupFailure = timeout;
            }
            if (!ref.done().isDone()) completed = false;
        }
        if (completed) {
            if (cleanupFailure != null) startupFailure.addSuppressed(cleanupFailure);
            return;
        }
        quarantined.set(held);
        IllegalStateException failure = new IllegalStateException(
                "actor startup rollback did not physically finalize; engine quarantined",
                startupFailure);
        if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
        throw failure;
    }

    private final class BoundSession implements PipelineController.Session {
        private final PipelinePlan plan;
        private final ActorRuntime.ActorStream stream;
        private final List<ActorRuntime.ActorRef<?>> refs;
        private final AtomicBoolean sent = new AtomicBoolean();
        private final Object closeGate = new Object();
        private CompletionStage<Void> stopping;
        private final CompletionStage<Void> successfulRoundtrip;

        BoundSession(PipelinePlan plan, ActorRuntime.ActorStream stream,
                     List<ActorRuntime.ActorRef<?>> refs) {
            this.plan = plan;
            this.stream = stream;
            this.refs = refs;
            this.successfulRoundtrip = stream.getDone().asCompletionStage()
                    .toCompletableFuture().minimalCompletionStage();
        }

        @Override
        public CompletionStage<Object> send(Object value) {
            if (ActorRuntime.inActorExecution()) {
                return CompletableFuture.failedFuture(new SecurityException(
                        "pipeline host ingress cannot run on actor DATA carrier"));
            }
            synchronized (closeGate) {
                if (stopping != null || !sent.compareAndSet(false, true)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("pipeline admission is closed or already used"));
                }
                try {
                    stream.send(Objects.requireNonNull(value, "pipeline message"));
                } catch (RuntimeException | Error failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            // The source's received nominal message was copied by ActorRuntime
            // at source completion. Do not synthesize a delivery receipt.
            // The result remains unavailable until all physical DONE receipts.
            return stream.completedReply();
        }

        @Override
        public CompletionStage<Void> getDone() { return successfulRoundtrip; }

        @Override
        public CompletionStage<Void> stopAfterDrain() {
            CompletableFuture<Void> terminal;
            synchronized (closeGate) {
                if (stopping != null) return stopping;
                terminal = new CompletableFuture<>();
                stopping = terminal;
            }
            // stop() may block while the actor physically finalizes. Dispatch
            // ONLY on a supplied trusted host/CONTROL executor; never run
            // this code under the admission lock or on an actor DATA lane.
            try {
                scheduleControl(() -> {
                    Throwable firstStopFailure = null;
                    for (ActorRuntime.ActorRef<?> ref : refs) {
                        try { ref.stop(); }
                        catch (RuntimeException | Error failure) {
                            if (firstStopFailure == null) firstStopFailure = failure;
                        }
                    }
                    CompletableFuture<?>[] done = refs.stream()
                            .map(ref -> ref.done().asCompletionStage()
                                    .handle((ignored, failure) -> null)
                                    .toCompletableFuture())
                            .toArray(CompletableFuture[]::new);
                    Throwable stopProblem = firstStopFailure;
                    CompletableFuture.allOf(done).whenComplete((ignored, failure) -> {
                        if (failure != null) terminal.completeExceptionally(failure);
                        else if (stopProblem != null) terminal.completeExceptionally(stopProblem);
                        else terminal.complete(null);
                    });
                });
            } catch (RuntimeException | Error schedulingFailure) {
                terminal.completeExceptionally(schedulingFailure);
            }
            return terminal;
        }
    }
}
