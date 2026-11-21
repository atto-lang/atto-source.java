package dev.oreslang.runtime.accel;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded, nonblocking, host-owned GPU dispatch bridge. This is NOT an OpenCL
 * driver: a real trusted provider must own device allocation, event callbacks
 * and physical release, and authenticate the supplied tenant/device identity.
 *
 * No code from a device callback invokes actor user code. Actors must await
 * get_done() and resume through their owning scheduler logical lane.
 */
public final class GpuDispatchCoordinator implements AutoCloseable {
    public interface Provider {
        /**
         * Completing this stage certifies a real device completion event
         * (success OR failure). It must not complete merely on queue admission.
         */
        CompletionStage<Void> launch(
                DopKernelIR.OpenClArtifact artifact,
                GpuBufferFence.Identity identity, GpuBufferFence.Ticket ticket);

        /**
         * Completing this stage certifies physical device allocation release,
         * not just enqueuing a free. Must be idempotent per generation.
         */
        CompletionStage<Void> release(GpuBufferFence.Identity identity, long reservedBytes);
    }

    public record Limits(int maximumInFlight, long maximumBytes) {
        public Limits {
            if (maximumInFlight < 1 || maximumBytes < 1)
                throw new IllegalArgumentException("positive GPU frame/byte limits required");
        }
    }

    public record Outcome(boolean cancelled, long byteReservation) { }

    public final class Submission {
        private final GpuBufferFence fence;
        private final GpuBufferFence.Ticket ticket;
        private final long bytes;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final CompletableFuture<Outcome> done = new CompletableFuture<>();
        private boolean cancelRequested;
        private boolean eventObserved;

        private Submission(GpuBufferFence fence, GpuBufferFence.Ticket ticket, long bytes) {
            this.fence = fence;
            this.ticket = ticket;
            this.bytes = bytes;
        }

        /** The provider has accepted the request; not a GPU-completion barrier. */
        public CompletableFuture<Void> get_ready() { return ready; }

        /** Device event AND physical release have both finished. */
        public CompletableFuture<Outcome> get_done() { return done; }

        /**
         * A cancelled request remains reserved until the real device event
         * arrives and the provider completes physical release.
         */
        public synchronized boolean cancel() {
            if (eventObserved || cancelRequested) return false;
            cancelRequested = true;
            fence.cancelPending(ticket);
            return true;
        }
    }

    private final Provider provider;
    private final Limits limits;
    private final Object credits = new Object();
    private int usedFrames;
    private long usedBytes;
    private boolean closed;

    public GpuDispatchCoordinator(Provider provider, Limits limits) {
        this.provider = Objects.requireNonNull(provider);
        this.limits = Objects.requireNonNull(limits);
    }

    public int inFlightFrames() {
        synchronized (credits) { return usedFrames; }
    }

    public long reservedBytes() {
        synchronized (credits) { return usedBytes; }
    }

    public Submission submit(
            DopKernelIR.OpenClArtifact artifact, GpuBufferFence.Identity identity) {
        Objects.requireNonNull(artifact);
        Objects.requireNonNull(identity);
        if (artifact.requiredBytes() <= 0)
            throw new IllegalArgumentException("device allocation requires nonempty layout");
        // Account independently allocated input and output buffers, fail-closed
        // on overflow. No submission can exceed its own reserved byte budget.
        long reservation = Math.multiplyExact(artifact.requiredBytes(), 2L);
        GpuBufferFence fence;
        synchronized (credits) {
            if (closed) throw new RejectedExecutionException("GPU coordinator closed");
            if (usedFrames >= limits.maximumInFlight()
                    || reservation > limits.maximumBytes() - usedBytes)
                throw new RejectedExecutionException("GPU frame or byte credit exhausted");
            fence = new GpuBufferFence(identity, reservation);
            ++usedFrames;
            usedBytes += reservation;
        }

        var ticket = fence.beginCommand(identity);
        Submission item = new Submission(fence, ticket, reservation);
        CompletionStage<Void> completion;
        try {
            completion = Objects.requireNonNull(provider.launch(artifact, identity, ticket),
                    "provider launch must return a completion stage");
            item.ready.complete(null);
        } catch (Throwable failure) {
            // An exception at launch *cannot prove* that a device queue did not
            // receive the command. Keep resources fenced/charged for manual
            // reconciliation; never refund or physically free on uncertainty.
            item.ready.completeExceptionally(failure);
            item.done.completeExceptionally(new IllegalStateException(
                    "device launch acceptance uncertain; reservation retained", failure));
            return item;
        }
        completion.whenComplete((unused, eventFailure) -> afterDeviceEvent(item, identity, eventFailure));
        return item;
    }

    private void afterDeviceEvent(Submission item, GpuBufferFence.Identity identity, Throwable eventFailure) {
        final boolean cancelled;
        synchronized (item) {
            if (item.eventObserved) return; // Provider must complete once.
            item.eventObserved = true;
            cancelled = item.cancelRequested;
            try {
                if (cancelled) {
                    item.fence.acknowledgeCancelledDeviceEvent(item.ticket, identity);
                } else {
                    item.fence.complete(item.ticket, eventFailure == null);
                }
            } catch (Throwable mismatch) {
                item.done.completeExceptionally(mismatch);
                return; // Never release/refund an uncertain device event.
            }
        }
        CompletionStage<Void> release;
        try {
            release = Objects.requireNonNull(provider.release(identity, item.bytes),
                    "provider physical release must return a completion stage");
        } catch (Throwable failure) {
            item.done.completeExceptionally(new IllegalStateException(
                    "device release uncertain; reservation retained", failure));
            return;
        }
        release.whenComplete((unused, releaseFailure) -> {
            if (releaseFailure != null) {
                item.done.completeExceptionally(new IllegalStateException(
                        "device release failed; reservation retained", releaseFailure));
                return;
            }
            try {
                item.fence.retireAfterBackendRelease(identity);
                item.fence.close();
                synchronized (credits) {
                    --usedFrames;
                    usedBytes -= item.bytes;
                }
                if (cancelled) {
                    item.done.completeExceptionally(new CancellationException(
                            "GPU command cancelled; device event and release acknowledged"));
                } else if (eventFailure != null) {
                    item.done.completeExceptionally(
                            new IllegalStateException("GPU device execution failed", eventFailure));
                } else {
                    item.done.complete(new Outcome(false, item.bytes));
                }
            } catch (Throwable terminalFailure) {
                item.done.completeExceptionally(terminalFailure);
            }
        });
    }

    /**
     * Host close prevents new admissions; it cannot interrupt live device
     * work or release occupied credits while an event is outstanding.
     */
    @Override public void close() {
        synchronized (credits) {
            closed = true;
            if (usedFrames != 0)
                throw new IllegalStateException(
                        "GPU coordinator has unreleased device reservations");
        }
    }
}
