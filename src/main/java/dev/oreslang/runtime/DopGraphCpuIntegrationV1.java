package dev.oreslang.runtime;

import dev.oreslang.runtime.accel.DopKernelIR;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted host-only composition of one owned f32 DOP region and a subsequent
 * typed host-i64 execution graph. This is not Oreslang source AST lowering,
 * a fused f32/i64 graph, a GPU provider, or a stream.
 *
 * <p>The caller supplies a bounded trusted executor. The two stages run on
 * the same claimed one-shot worker, never on a guest actor carrier.
 * Cancellation before worker admission suppresses all buffer writes.
 * Cancellation after a kernel has started suppresses Future publication
 * but cannot roll back already executed host writes. The kernel's
 * allocation-wide loans prevent partial reads while it executes.</p>
 */
public final class DopGraphCpuIntegrationV1 {
    private DopGraphCpuIntegrationV1() { }

    public static OresFuture<Long> submit(
            DopKernelIR.Kernel kernel,
            DopKernelIR.HostBuffer input,
            DopKernelIR.HostBuffer output,
            DopRegionSchedule.Access source,
            DopRegionSchedule.Access destination,
            ExecutionGraphV1.Plan<Long> scalar,
            Executor workerExecutor) {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(scalar, "scalar");
        Objects.requireNonNull(workerExecutor, "workerExecutor");

        // Never schedule or touch DOP buffers for unsupported device paths.
        if (!scalar.cpuOnly() || scalar.resultType() != ExecutionGraphV1.ValueKind.HOST_I64) {
            return OresFuture.failed(new UnsupportedOperationException(
                    "owned CPU graph requires host i64; GPU transfer unavailable; no CPU fallback"));
        }

        // Structural preflight, not an ownership lease. executeCpuRegion
        // rechecks the footprint and acquires both actual loans atomically
        // with respect to all operations on each allocation.
        try {
            if (!kernel.layout().equals(input.layout())
                    || !kernel.layout().equals(output.layout())) {
                throw new IllegalArgumentException("owned CPU graph kernel layout mismatch");
            }
            if (source.root().sameRoot(destination.root())
                    || source.mode() != DopRegionSchedule.Mode.READ
                    || destination.mode() != DopRegionSchedule.Mode.WRITE
                    || source.field() != destination.field()
                    || source.begin() != destination.begin()
                    || source.end() != destination.end()
                    || !source.root().sameRoot(input.regionRoot())
                    || !destination.root().sameRoot(output.regionRoot())) {
                throw new IllegalArgumentException("invalid owned CPU graph region footprint");
            }
            DopRegionSchedule.requireLiveOwned(new DopRegionSchedule.Task(
                    "owned-graph-cpu", List.of(source, destination)));
        } catch (RuntimeException ex) {
            return OresFuture.failed(ex);
        }

        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean started = new AtomicBoolean();
        OresFuture<Long> future = new OresFuture<>(() -> cancelled.set(true));
        try {
            workerExecutor.execute(() -> {
                // Executor callbacks are not necessarily exactly-once.
                if (!started.compareAndSet(false, true) || cancelled.get() || future.isDone()) {
                    return;
                }
                try {
                    if (ActorRuntime.inActorExecution()) {
                        throw new IllegalStateException(
                                "owned CPU graph cannot execute on an actor carrier");
                    }
                    DopKernelIR.executeCpuRegion(kernel, input, output, source, destination);
                    if (cancelled.get() || future.isDone()) return;
                    Long value = scalar.executeCpu();
                    if (!future.isDone()) future.completeFromRuntime(value);
                } catch (Throwable failure) {
                    future.failFromRuntime(failure);
                }
            });
        } catch (RuntimeException failure) {
            // A buggy scheduler may retain the closure and then throw.
            // The retained task must not mutate the destination later.
            cancelled.set(true);
            future.failFromRuntime(failure);
        }
        return future;
    }
}
