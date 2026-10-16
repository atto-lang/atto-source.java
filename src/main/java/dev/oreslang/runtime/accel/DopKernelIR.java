package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.DopRegionSchedule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Typed, deterministic f32 DOP kernel IR and two explicit lowering targets.
 * CPU lowering is executable. GPU lowering emits OpenCL C device source but
 * DOES NOT dispatch a kernel or silently substitute CPU execution.
 */
public final class DopKernelIR {
    private DopKernelIR() {}

    public enum Element { F32 }
    public enum Op { SCALE, BIAS, SCALE_BIAS }

    public record Kernel(DataOrientedLayout.Plan layout, Element element,
                         Op operation, float scale, float bias) {
        public Kernel {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(operation, "operation");
            if (layout.scalarBytes() != Float.BYTES) {
                throw new IllegalArgumentException("f32 kernel requires four-byte POD fields");
            }
            if (!Float.isFinite(scale) || !Float.isFinite(bias)) {
                throw new IllegalArgumentException("kernel coefficients must be finite");
            }
        }
    }

    /** No raw GPU pointer can be created here. A provider must consume this artifact explicitly. */
    public record GpuTarget(int addressBits, long maximumBufferBytes,
                            long maximumRows, boolean float32) {
        public GpuTarget {
            if (addressBits != 64 || maximumBufferBytes <= 0 || maximumRows <= 0) {
                throw new IllegalArgumentException("unsupported GPU address profile or limits");
            }
        }
    }

    public record OpenClArtifact(Kernel source, String kernelName, String deviceSource,
                                 long requiredBytes, long globalRows, int globalFields) { }

    private static final class State {
        final DataOrientedLayout.Plan layout;
        final ByteBuffer bytes;
        final HostBuffer.RegionToken regionToken;
        int readLoans;
        boolean writeLoan;
        boolean closed;
        State(DataOrientedLayout.Plan layout) {
            // getFloat/putFloat operate on four bytes regardless of the POD
            // descriptor. An undersized slot can alias neighboring fields or
            // throw partway through a kernel after modifying other values.
            if (layout.scalarBytes() != Float.BYTES) {
                throw new IllegalArgumentException("F32 HostBuffer requires four-byte POD fields");
            }
            this.layout = layout;
            this.bytes = ByteBuffer.allocateDirect(Math.toIntExact(layout.byteLength()))
                    .order(ByteOrder.LITTLE_ENDIAN);
            this.regionToken = new HostBuffer.RegionToken(this);
        }
    }

    /**
     * Non-shareable host buffer owner. Ownership can move only while no read
     * loan exists. Closing invalidates all operations; GC is not the lifetime API.
     */
    public static final class HostBuffer implements AutoCloseable {
        /** Opaque allocation identity; never exposes the underlying bytes. */
        public static final class RegionToken {
            private final State owner;
            private RegionToken(State owner) { this.owner = owner; }
            public boolean isLive() {
                synchronized (owner) { return !owner.closed; }
            }
        }
        private final State state;
        private boolean moved;

        public HostBuffer(DataOrientedLayout.Plan layout) {
            this(new State(Objects.requireNonNull(layout)));
        }

        private HostBuffer(State state) { this.state = state; }

        /**
         * Host-only, opaque allocation identity. The backing State is private
         * and cannot be used to access raw data or fabricate a region root.
         */
        public Object regionBackingIdentity() {
            synchronized (state) {
                active();
                return state.regionToken;
            }
        }
        public DopRegionSchedule.Root regionRoot() {
            return DopRegionSchedule.Root.fromHostBuffer(this);
        }
        public static boolean isBackingLive(Object identity) {
            if (!(identity instanceof State backing)) return false;
            synchronized (backing) {
                return !backing.closed;
            }
        }
        public DataOrientedLayout.Plan layout() {
            synchronized (state) {
                active();
                return state.layout;
            }
        }
        private void active() {
            if (moved || state.closed) throw new IllegalStateException("buffer moved or closed");
        }
        public HostBuffer take() {
            synchronized (state) {
                active();
                if (state.readLoans != 0 || state.writeLoan) throw new IllegalStateException("cannot move borrowed buffer");
                moved = true;
                return new HostBuffer(state);
            }
        }
        public ReadLoan borrowRead() {
            synchronized (state) {
                active();
                if (state.writeLoan) throw new IllegalStateException("buffer exclusively borrowed");
                if (state.readLoans == Integer.MAX_VALUE) {
                    throw new IllegalStateException("read loan count exhausted");
                }
                state.readLoans++;
                return new ReadLoan(state);
            }
        }
        public WriteLoan borrowWrite() {
            synchronized (state) {
                active();
                if (state.readLoans != 0 || state.writeLoan) {
                    throw new IllegalStateException("cannot exclusively borrow buffer");
                }
                state.writeLoan = true;
                return new WriteLoan(state);
            }
        }
        public float read(long row, int field) {
            synchronized (state) {
                active();
                if (state.writeLoan) throw new IllegalStateException("buffer exclusively borrowed");
                return state.bytes.getFloat(Math.toIntExact(state.layout.byteOffset(row, field)));
            }
        }
        public void write(long row, int field, float value) {
            synchronized (state) {
                active();
                if (state.readLoans != 0 || state.writeLoan) throw new IllegalStateException("cannot write borrowed buffer");
                state.bytes.putFloat(Math.toIntExact(state.layout.byteOffset(row, field)), value);
            }
        }
        @Override public void close() {
            synchronized (state) {
                if (moved) return; // The successor owns the same storage.
                if (state.readLoans != 0 || state.writeLoan) throw new IllegalStateException("borrowed buffer must be released before close");
                state.closed = true;
            }
        }
    }

    public static final class ReadLoan implements AutoCloseable {
        private final State state;
        private boolean released;
        private ReadLoan(State state) { this.state = state; }
        public float read(long row, int field) {
            synchronized (state) {
                if (released || state.closed) throw new IllegalStateException("stale read loan");
                return state.bytes.getFloat(Math.toIntExact(state.layout.byteOffset(row, field)));
            }
        }
        @Override public void close() {
            synchronized (state) {
                if (released) throw new IllegalStateException("read loan already released");
                released = true;
                state.readLoans--;
            }
        }
    }

    /** Holds the entire destination region until the CPU kernel exits. */
    public static final class WriteLoan implements AutoCloseable {
        private final State state;
        private boolean released;
        private WriteLoan(State state) { this.state = state; }
        public void write(long row, int field, float value) {
            synchronized (state) {
                if (released || state.closed || !state.writeLoan) {
                    throw new IllegalStateException("stale write loan");
                }
                state.bytes.putFloat(Math.toIntExact(state.layout.byteOffset(row, field)), value);
            }
        }
        @Override public void close() {
            synchronized (state) {
                if (released) throw new IllegalStateException("write loan already released");
                released = true;
                state.writeLoan = false;
            }
        }
    }

    /** Typed CPU lowering: input is read-only and output is fresh, never aliased. */
    public static void executeCpu(Kernel kernel, HostBuffer input, HostBuffer output) {
        Objects.requireNonNull(kernel);
        Objects.requireNonNull(input);
        Objects.requireNonNull(output);
        if (input.state == output.state) throw new IllegalArgumentException("input/output alias forbidden");
        if (!kernel.layout().equals(input.layout()) || !kernel.layout().equals(output.layout())) {
            throw new IllegalArgumentException("kernel layout differs from buffer shape");
        }
        try (ReadLoan read = input.borrowRead(); WriteLoan destination = output.borrowWrite()) {
            for (long row = 0; row < kernel.layout().rows(); ++row) {
                for (int field = 0; field < kernel.layout().fields(); ++field) {
                    float v = read.read(row, field);
                    float transformed = switch (kernel.operation()) {
                        case SCALE -> v * kernel.scale();
                        case BIAS -> v + kernel.bias();
                        case SCALE_BIAS -> (v * kernel.scale()) + kernel.bias();
                    };
                    destination.write(row, field, transformed);
                }
            }
        }
    }

    /**
     * Checked host scalar CPU lowering of a single field / half-open row slice.
     * Runtime buffer-wide loans are intentionally exclusive; field-disjoint
     * parallel execution is not yet admitted without a field-aware loan system.
     */
    public static void executeCpuRegion(Kernel kernel, HostBuffer input, HostBuffer output,
                                        DopRegionSchedule.Access source,
                                        DopRegionSchedule.Access destination) {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        if (input.state == output.state) {
            throw new IllegalArgumentException("region input/output alias forbidden");
        }
        if (!kernel.layout().equals(input.layout()) || !kernel.layout().equals(output.layout())) {
            throw new IllegalArgumentException("region kernel layout mismatch");
        }
        if (source.mode() != DopRegionSchedule.Mode.READ
                || destination.mode() != DopRegionSchedule.Mode.WRITE
                || source.field() != destination.field()
                || source.begin() != destination.begin()
                || source.end() != destination.end()
                || !source.root().sameRoot(input.regionRoot())
                || !destination.root().sameRoot(output.regionRoot())) {
            throw new IllegalArgumentException("invalid CPU region owner, privilege or footprint");
        }
        DopRegionSchedule.requireLiveOwned(new DopRegionSchedule.Task(
                "cpu-region", java.util.List.of(source, destination)));
        // Both leases must be acquired before the first write. Failed output
        // admission closes the acquired input loan via try-with-resources.
        try (ReadLoan read = input.borrowRead(); WriteLoan write = output.borrowWrite()) {
            for (long row = source.begin(); row < source.end(); ++row) {
                float v = read.read(row, source.field());
                float transformed = switch (kernel.operation()) {
                    case SCALE -> v * kernel.scale();
                    case BIAS -> v + kernel.bias();
                    case SCALE_BIAS -> (v * kernel.scale()) + kernel.bias();
                };
                write.write(row, destination.field(), transformed);
            }
        }
    }

    private static String f(float v) { return Float.toString(v) + "f"; }
    private static long aligned(long bytes, int a) {
        return bytes == 0 ? 0 : Math.multiplyExact(((bytes - 1) / a) + 1, a);
    }

    /**
     * Real OpenCL C kernel text lowering from typed IR; no device allocation or
     * execution occurs until a separate trusted provider compiles and dispatches.
     */
    public static OpenClArtifact lowerOpenCl(Kernel kernel, GpuTarget target) {
        Objects.requireNonNull(kernel);
        Objects.requireNonNull(target);
        var p = kernel.layout();
        // A zero-row DOP buffer is legitimate for CPU calculations, but a
        // zero-sized GPU launch/allocation cannot satisfy device-fence admission.
        if (p.rows() == 0 || p.byteLength() == 0 || !target.float32()
                || p.byteLength() > target.maximumBufferBytes()
                || p.rows() > target.maximumRows()) {
            throw new IllegalArgumentException("requested device cannot admit kernel layout/effect");
        }
        String offset = switch (p.kind()) {
            case AOS -> "(row * " + aligned(Math.multiplyExact((long)p.fields(), 4), p.alignment())
                    + "UL + field * 4UL)";
            case SOA -> "(field * " + aligned(Math.multiplyExact(p.rows(), 4), p.alignment())
                    + "UL + row * 4UL)";
            case AOSOA -> {
                long tile = aligned(Math.multiplyExact(Math.multiplyExact((long)p.fields(), p.lanes()), 4), p.alignment());
                yield "((row / " + p.lanes() + "UL) * " + tile
                        + "UL + field * " + (p.lanes() * 4L)
                        + "UL + (row % " + p.lanes() + "UL) * 4UL)";
            }
        };
        String rhs = switch (kernel.operation()) {
            case SCALE -> "(v * " + f(kernel.scale()) + ")";
            case BIAS -> "(v + " + f(kernel.bias()) + ")";
            case SCALE_BIAS -> "((v * " + f(kernel.scale()) + ") + " + f(kernel.bias()) + ")";
        };
        String name = "oreslang_dop_f32";
        String source = "#pragma OPENCL FP_CONTRACT OFF\n"
                + "__kernel void " + name + "(__global const float* src, __global float* dst) {\n"
                + "  const ulong row = (ulong)get_global_id(0);\n"
                + "  const ulong field = (ulong)get_global_id(1);\n"
                + "  if (row >= " + p.rows() + "UL || field >= " + p.fields() + "UL) return;\n"
                + "  const ulong byteOffset = " + offset + ";\n"
                + "  const ulong slot = byteOffset / 4UL;\n"
                + "  const float v = src[slot];\n"
                + "  dst[slot] = " + rhs + ";\n"
                + "}\n";
        return new OpenClArtifact(kernel, name, source, p.byteLength(), p.rows(), p.fields());
    }
}
