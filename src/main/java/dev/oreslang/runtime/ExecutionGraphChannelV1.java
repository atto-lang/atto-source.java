package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted, bounded CPU graph -> explicit Channel<Long> publication bridge.
 *
 * <p>Nonblocking submission returns a one-shot OresFuture<Void>; streaming is
 * opt-in through an independently supplied, bounded channel. This is NOT
 * automatic `nb` streaming, source syntax lowering, nor GPU device output.
 * Never call guest callbacks from a completion thread.</p>
 */
public final class ExecutionGraphChannelV1 {
    private ExecutionGraphChannelV1() { }

    public static OresFuture<Void> publishOne(
            ExecutionGraphV1.Plan<Long> plan,
            ChannelRuntime.Channel<Long> destination,
            Executor cpuExecutor) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(cpuExecutor, "cpuExecutor");

        if (!plan.cpuOnly() || plan.resultType() != ExecutionGraphV1.ValueKind.HOST_I64) {
            return OresFuture.failed(new UnsupportedOperationException(
                    "CPU stream requires host i64; GPU transfer unavailable; no implicit CPU fallback"));
        }

        AtomicReference<OresFuture<Long>> worker = new AtomicReference<>();
        AtomicReference<OresFuture<Void>> pendingWrite = new AtomicReference<>();
        OresFuture<Void> completed = new OresFuture<>(() -> {
            OresFuture<Long> running = worker.get();
            if (running != null) running.cancel(false);
            OresFuture<Void> writing = pendingWrite.get();
            if (writing != null) writing.cancel(false);
        });

        // Registers a CPU computation with trusted executor admission. All
        // producer/channel notification callbacks below are HOST bookkeeping,
        // never scheduled guest Oreslang callables.
        OresFuture<Long> computed = plan.submitCpu(cpuExecutor);
        worker.set(computed);
        if (completed.isDone()) computed.cancel(false);
        computed.whenCompleteRuntime((value, failure) -> {
            if (completed.isDone()) return;
            if (failure != null) {
                completed.failFromRuntime(failure);
                return;
            }
            if (value == null) {
                completed.failFromRuntime(new IllegalStateException("CPU graph produced null i64"));
                return;
            }
            try {
                OresFuture<Void> write = destination.writeAsync(value);
                pendingWrite.set(write);
                if (completed.isDone()) {
                    write.cancel(false);
                    return;
                }
                write.whenCompleteRuntime((ignored, writeFailure) -> {
                    if (writeFailure == null) completed.completeFromRuntime(null);
                    else completed.failFromRuntime(writeFailure);
                });
            } catch (RuntimeException ex) {
                completed.failFromRuntime(ex);
            }
        });
        return completed;
    }
}
