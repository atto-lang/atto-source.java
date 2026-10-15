package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Host-side descriptor reservation budget for proposed model actor kernels.
 * Not a native tensor allocator, GPU lease, actor capability, or guest ABI.
 *
 * This deliberately has no raw device pointer and does not grant execution:
 * the trusted native allocator must verify/consume reservations and enforce
 * the actor's actual memory domain independently.
 */
public final class NeuralTensorAdmissionBudget {
    private final long maxBytes;
    private final int maxDescriptors;
    private long reservedBytes;
    private int reservedDescriptors;
    private long generation;

    public NeuralTensorAdmissionBudget(long maxBytes, int maxDescriptors) {
        if (maxBytes < 1 || maxDescriptors < 1) {
            throw new IllegalArgumentException("tensor budget limits must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxDescriptors = maxDescriptors;
    }

    public record Snapshot(long reservedBytes, int reservedDescriptors, long generation) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(reservedBytes, reservedDescriptors, generation);
    }

    /**
     * Reserve every descriptor in a batch atomically; no partial ownership
     * transition or leaked bytes on allocation/overflow failures.
     */
    public synchronized Reservation reserve(
            NeuralActorSchema.Ticket ticket,
            List<NeuralActorSchema.TensorShape> tensors) {
        Objects.requireNonNull(ticket, "model ticket");
        Objects.requireNonNull(tensors, "tensor shape list");
        if (tensors.isEmpty() || tensors.size() > maxDescriptors) {
            throw new IllegalArgumentException("tensor reservation batch size outside descriptor budget");
        }
        long total = 0;
        for (NeuralActorSchema.TensorShape shape : tensors) {
            total = addBytes(total, Objects.requireNonNull(shape).byteLength());
        }
        if (total > maxBytes - reservedBytes
                || tensors.size() > maxDescriptors - reservedDescriptors) {
            throw new IllegalStateException("neural actor tensor admission budget exceeded");
        }
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("tensor budget reservation generation exhausted");
        }
        generation++;
        reservedBytes += total;
        reservedDescriptors += tensors.size();
        return new Reservation(ticket, total, tensors.size(), generation);
    }

    private static long addBytes(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("tensor reservation byte sum overflow", overflow);
        }
    }

    public final class Reservation implements AutoCloseable {
        private final NeuralActorSchema.Ticket ticket;
        private final long bytes;
        private final int descriptors;
        private final long ticketGeneration;
        private boolean closed;

        private Reservation(
                NeuralActorSchema.Ticket ticket, long bytes,
                int descriptors, long ticketGeneration) {
            this.ticket = ticket;
            this.bytes = bytes;
            this.descriptors = descriptors;
            this.ticketGeneration = ticketGeneration;
        }

        public long bytes() { return bytes; }
        public long generation() { return ticketGeneration; }

        /**
         * Call before native kernel admission and before accepting a late
         * completion. An old request or a released ticket cannot be reused.
         */
        public void assertCurrent(NeuralActorSchema.Ticket candidate) {
            synchronized (NeuralTensorAdmissionBudget.this) {
                if (closed || !ticket.equals(candidate)) {
                    throw new SecurityException("stale, closed or mismatched neural tensor reservation");
                }
            }
        }

        @Override
        public void close() {
            synchronized (NeuralTensorAdmissionBudget.this) {
                if (closed) return;
                closed = true;
                reservedBytes -= bytes;
                reservedDescriptors -= descriptors;
                if (reservedBytes < 0 || reservedDescriptors < 0) {
                    throw new IllegalStateException("neural reservation accounting underflow");
                }
            }
        }
    }
}
