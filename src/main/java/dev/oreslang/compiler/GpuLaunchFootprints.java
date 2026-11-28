package dev.oreslang.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-side, fail-closed footprint preflight for a future AttoLang explicit GPU
 * launch. This is not a provider authorization, a device allocation, a GPU
 * submission, or a proof of physical aliasing: callers must first resolve
 * opaque buffers and queue authority through a trusted backend.
 *
 * This verifier is deliberately independent of the proposed launch parser so
 * it can be reused by all host launch paths without enabling CPU fallback.
 */
public final class GpuLaunchFootprints {
    private GpuLaunchFootprints() { }

    public enum Access { READ, WRITE, READ_WRITE }

    public record QueueScope(String tenant, String device, String queue,
                             long queueGeneration) {
        public QueueScope {
            requireName(tenant, "tenant");
            requireName(device, "device");
            requireName(queue, "queue");
            if (queueGeneration <= 0) {
                throw invalid("queue generation must be positive");
            }
        }
    }

    /**
     * All byte ranges are half-open [byteOffset, byteOffset + byteLength).
     * The allocation ID and generation must come from a trusted, immutable
     * provider snapshot, not source strings supplied by the guest.
     */
    public record View(String tenant, String device, String allocationId,
                       long allocationGeneration, long allocationBytes,
                       long byteOffset, long byteLength, long elementAlignment,
                       Access access) {
        public View {
            requireName(tenant, "view tenant");
            requireName(device, "view device");
            requireName(allocationId, "allocation");
            Objects.requireNonNull(access, "GPU-LAUNCH-005: access");
            if (allocationGeneration <= 0 || allocationBytes <= 0
                    || byteOffset < 0 || byteLength <= 0
                    || elementAlignment <= 0
                    || (elementAlignment & (elementAlignment - 1)) != 0) {
                throw invalid("invalid buffer generation, range, or alignment");
            }
            if (byteOffset % elementAlignment != 0
                    || byteLength % elementAlignment != 0) {
                throw invalid("device view is not element aligned");
            }
            long end;
            try {
                end = Math.addExact(byteOffset, byteLength);
            } catch (ArithmeticException overflow) {
                throw invalid("device view byte range overflow");
            }
            if (end > allocationBytes) {
                throw invalid("device view exceeds allocation length");
            }
        }

        public long endExclusive() {
            // Checked once in the canonical record constructor.
            return byteOffset + byteLength;
        }

        public boolean writes() {
            return access != Access.READ;
        }
    }

    public record Limits(int maximumViews, long maximumReferencedBytes) {
        public Limits {
            if (maximumViews <= 0 || maximumReferencedBytes <= 0) {
                throw invalid("invalid launch footprint quotas");
            }
        }
    }

    private record AllocationMeta(long generation, long bytes) { }

    private static void requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("missing " + field + " identity");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("GPU-LAUNCH-005: " + message);
    }

    /**
     * Validate a fully captured, trusted provider snapshot before enqueue.
     * No locks or device authority are acquired here. A provider must
     * separately pin the validated generations and loans until its actual
     * completion event proves device quiescence.
     *
     * The byte budget intentionally counts each view, including duplicate
     * read-only views, so callers cannot evade per-launch admission limits.
     */
    public static void validate(QueueScope scope, List<View> views, Limits limits) {
        Objects.requireNonNull(scope, "GPU-LAUNCH-005: scope");
        Objects.requireNonNull(views, "GPU-LAUNCH-005: views");
        Objects.requireNonNull(limits, "GPU-LAUNCH-005: limits");
        if (views.size() > limits.maximumViews()) {
            throw invalid("too many kernel buffer arguments");
        }

        Map<String, AllocationMeta> allocations = new HashMap<>();
        Map<String, List<View>> byAllocation = new HashMap<>();
        long referencedBytes = 0L;
        for (View view : views) {
            Objects.requireNonNull(view, "GPU-LAUNCH-005: null device view");
            if (!scope.tenant().equals(view.tenant())
                    || !scope.device().equals(view.device())) {
                throw new SecurityException(
                        "GPU-LAUNCH-004: foreign tenant/device GPU buffer");
            }

            AllocationMeta incoming = new AllocationMeta(
                    view.allocationGeneration(), view.allocationBytes());
            AllocationMeta existing = allocations.putIfAbsent(
                    view.allocationId(), incoming);
            if (existing != null && !existing.equals(incoming)) {
                throw invalid("inconsistent allocation generation or byte extent");
            }
            try {
                referencedBytes = Math.addExact(referencedBytes, view.byteLength());
            } catch (ArithmeticException overflow) {
                throw invalid("total referenced bytes overflow");
            }
            if (referencedBytes > limits.maximumReferencedBytes()) {
                throw invalid("referenced device bytes exceed launch quota");
            }
            byAllocation.computeIfAbsent(view.allocationId(),
                    ignored -> new ArrayList<>()).add(view);
        }

        // Sorting by offset makes the overlap verifier O(n log n), including
        // long lists of read-only aliases. Adjacent half-open views may write
        // independently. Any read/write or write/write overlap is rejected.
        for (List<View> ranges : byAllocation.values()) {
            ranges.sort(Comparator.comparingLong(View::byteOffset)
                    .thenComparingLong(View::byteLength));
            long maxAnyEnd = 0L;
            long maxWriteEnd = 0L;
            for (View view : ranges) {
                if (view.writes() ? maxAnyEnd > view.byteOffset()
                                  : maxWriteEnd > view.byteOffset()) {
                    throw invalid("overlapping mutable device-buffer loans");
                }
                maxAnyEnd = Math.max(maxAnyEnd, view.endExclusive());
                if (view.writes()) {
                    maxWriteEnd = Math.max(maxWriteEnd, view.endExclusive());
                }
            }
        }
    }
}
