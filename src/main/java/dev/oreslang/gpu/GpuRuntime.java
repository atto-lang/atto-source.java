package dev.oreslang.gpu;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Process-wide GPU backend SPI for Oreslang embeddings.
 *
 * GPU placement is mandatory: this runtime never evaluates a gpu callable on
 * the CPU as a fallback. Device resources are opaque and remain permanently
 * bound to the backend that created them.
 */
public final class GpuRuntime {
    private static final AtomicReference<Backend> PROCESS_BACKEND = new AtomicReference<>();

    public enum CallableKind { FNC, ROUTINE }
    public enum ResourceKind { ARRAY, MATRIX, STREAM }

    /**
     * Trusted-backend wire descriptor. This is the only form in which a native
     * resource ID crosses from the Oreslang runtime into a physical backend.
     */
    public record NativeResourceArg(
            long nativeHandle,
            ResourceKind kind,
            String elementAbiType,
            long length,
            long rows,
            long cols) {
        public NativeResourceArg {
            requireNativeHandle(nativeHandle, "backend resource argument");
            Objects.requireNonNull(kind, "kind");
            elementAbiType = normalizeDeviceScalarType(elementAbiType);
            if (length < -1 || rows < 0 || cols < 0) {
                throw new IllegalArgumentException("invalid backend resource metadata");
            }
        }
    }

    public record BackendInvocation(String callable, CallableKind callableKind, List<?> arguments) {
        public BackendInvocation {
            Objects.requireNonNull(callable, "callable");
            Objects.requireNonNull(callableKind, "callableKind");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    /** Marker for private guest-runtime values that expose a safe GPU wire form. */
    public interface Transferable {
        Object freezeForGpu();
    }

    /** Stable public representation for Oreslang complex scalar values. */
    public record ComplexValue(double real, double imaginary) { }

    /** Stable public representation for Oreslang Option values. */
    public record OptionValue(boolean present, Object value) {
        public OptionValue {
            if (present && value == null) {
                throw new IllegalArgumentException("Some cannot carry standalone null");
            }
            if (!present && value != null) {
                throw new IllegalArgumentException("None cannot carry a value");
            }
        }
    }

    /**
     * Backend result for collecting a stream into a concrete device array.
     *
     * Native resource identifiers are opaque unsigned 64-bit IDs represented by
     * Java long. Zero is permanently invalid. They are IDs, not guest-visible
     * addresses and must not be dereferenceable by Oreslang code.
     */
    public record CollectedArray(long nativeHandle, long length) {
        public CollectedArray {
            requireNativeHandle(nativeHandle, "collected GpuArray");
            if (length < 0) {
                throw new IllegalArgumentException("collected GpuArray length cannot be negative");
            }
        }
    }

    public interface Backend {
        String name();

        default boolean supports(BackendInvocation invocation) {
            return true;
        }

        Object invoke(BackendInvocation invocation);

        /**
         * Uploads a flat device buffer and returns a backend-owned opaque native
         * resource ID. Zero is invalid. Implementations should use a generation-
         * checked handle table rather than returning raw native addresses.
         */
        default long uploadArray(String elementAbiType, List<?> values) {
            throw new GpuCapabilityUnavailableException(
                    "GPU backend '" + name() + "' does not support GpuArray uploads");
        }

        default List<?> downloadArray(String elementAbiType, long nativeHandle, long length) {
            throw new GpuCapabilityUnavailableException(
                    "GPU backend '" + name() + "' does not support GpuArray downloads");
        }

        default long openStream(String elementAbiType, long arrayNativeHandle, long length) {
            return arrayNativeHandle;
        }

        default CollectedArray collectStream(String elementAbiType, long streamNativeHandle, long lengthHint) {
            if (lengthHint < 0) {
                throw new GpuCapabilityUnavailableException(
                        "GPU backend '" + name()
                                + "' must report a concrete length when collecting an unknown-length GpuStream");
            }
            return new CollectedArray(streamNativeHandle, lengthHint);
        }

        /**
         * Releases a backend-owned native resource ID. Backends must make this
         * idempotent or reject a stale/double release deterministically.
         */
        default void release(long nativeHandle) { }
    }

    /**
     * Shared ownership of one backend-native resource. Multiple Oreslang views
     * (for example Array -> Stream -> collected Array) may intentionally refer
     * to the same native handle; the backend release hook runs exactly once.
     */
    private static final class ResourceLease {
        private final Backend backend;
        private final long nativeHandle;
        private final AtomicInteger references = new AtomicInteger(1);
        private final AtomicBoolean backendReleased = new AtomicBoolean();

        private ResourceLease(Backend backend, long nativeHandle) {
            this.backend = Objects.requireNonNull(backend, "backend");
            this.nativeHandle = requireNativeHandle(nativeHandle, "native resource");
        }

        private synchronized ResourceLease retain() {
            int current = references.get();
            if (current <= 0 || backendReleased.get()) {
                throw new GpuTransferException("native GPU resource has already been released");
            }
            references.set(Math.addExact(current, 1));
            return this;
        }

        private ResourcePin pin(String kind) {
            retain();
            return new ResourcePin(this, kind);
        }

        private synchronized void release(String kind) {
            int current = references.get();
            if (current <= 0 || backendReleased.get()) {
                throw new GpuTransferException(kind + " native resource lease was released too many times");
            }
            if (current > 1) {
                references.decrementAndGet();
                return;
            }

            // Last owner: make backend release transactional. If the driver
            // rejects/fails the release, keep one live reference so the caller
            // may retry instead of leaking an unreachable native allocation.
            backendCallVoid(backend, "release(" + kind + ")", () -> backend.release(nativeHandle));
            backendReleased.set(true);
            references.set(0);
        }

        private void ensureLive(String kind) {
            if (references.get() <= 0 || backendReleased.get()) {
                throw new GpuTransferException(kind + " native resource has already been released");
            }
        }
    }

    private static final class ResourcePin implements AutoCloseable {
        private final ResourceLease lease;
        private final String kind;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ResourcePin(ResourceLease lease, String kind) {
            this.lease = lease;
            this.kind = kind;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) lease.release(kind);
        }
    }

    /** Opaque GPU-resident array resource. Guest code cannot access the native handle. */
    public static final class ArrayHandle {
        private final ResourceLease lease;
        private final Backend backend;
        private final long nativeHandle;
        private final String elementAbiType;
        private final long length;
        private final AtomicBoolean released = new AtomicBoolean();

        private ArrayHandle(Backend backend, long nativeHandle, String elementAbiType, long length) {
            this(new ResourceLease(backend, nativeHandle), elementAbiType, length, false);
        }

        private ArrayHandle(ResourceLease lease, String elementAbiType, long length, boolean retain) {
            this.lease = retain ? lease.retain() : lease;
            this.backend = lease.backend;
            this.nativeHandle = lease.nativeHandle;
            this.elementAbiType = normalizeDeviceScalarType(elementAbiType);
            if (length < 0) throw new IllegalArgumentException("GpuArray length cannot be negative");
            this.length = length;
        }

        public String backendName() { ensureLive(); return backend.name(); }
        public String elementAbiType() { ensureLive(); return elementAbiType; }
        public long length() { ensureLive(); return length; }

        private void ensureLive() {
            if (released.get()) throw new GpuTransferException("GpuArray native resource has already been released");
            lease.ensureLive("GpuArray");
        }
    }

    /** Opaque GPU-resident dense row-major matrix resource. */
    public static final class MatrixHandle {
        private final ArrayHandle array;
        private final long rows;
        private final long cols;

        private MatrixHandle(ArrayHandle array, long rows, long cols) {
            this.array = Objects.requireNonNull(array, "array");
            if (rows <= 0 || cols <= 0) {
                throw new GpuTransferException(
                        "GpuMatrix dimensions must be positive; zero-dimension matrices are not supported by GPU ABI v3");
            }
            long length;
            try {
                length = Math.multiplyExact(rows, cols);
            } catch (ArithmeticException overflow) {
                throw new GpuTransferException("GpuMatrix dimensions overflow addressable length", overflow);
            }
            if (length != array.length) {
                throw new GpuTransferException(
                        "GpuMatrix shape " + rows + "x" + cols + " does not match backing length " + array.length);
            }
            this.rows = rows;
            this.cols = cols;
        }

        public String backendName() { ensureLive(); return array.backendName(); }
        public String elementAbiType() { ensureLive(); return array.elementAbiType(); }
        public long length() { ensureLive(); return array.length(); }
        public long rows() { ensureLive(); return rows; }
        public long cols() { ensureLive(); return cols; }

        private void ensureLive() { array.ensureLive(); }
    }

    /** Opaque GPU-resident sequential stream resource. */
    public static final class StreamHandle {
        private final ResourceLease lease;
        private final Backend backend;
        private final long nativeHandle;
        private final String elementAbiType;
        private final long lengthHint;
        private final AtomicBoolean released = new AtomicBoolean();

        private StreamHandle(Backend backend, long nativeHandle, String elementAbiType, long lengthHint) {
            this(new ResourceLease(backend, nativeHandle), elementAbiType, lengthHint, false);
        }

        private StreamHandle(ResourceLease lease, String elementAbiType, long lengthHint, boolean retain) {
            this.lease = retain ? lease.retain() : lease;
            this.backend = lease.backend;
            this.nativeHandle = lease.nativeHandle;
            this.elementAbiType = normalizeDeviceScalarType(elementAbiType);
            if (lengthHint < -1) {
                throw new IllegalArgumentException("GpuStream length hint must be -1 or non-negative");
            }
            this.lengthHint = lengthHint;
        }

        public String backendName() { ensureLive(); return backend.name(); }
        public String elementAbiType() { ensureLive(); return elementAbiType; }
        public long lengthHint() { ensureLive(); return lengthHint; }

        private void ensureLive() {
            if (released.get()) throw new GpuTransferException("GpuStream native resource has already been released");
            lease.ensureLive("GpuStream");
        }
    }

    public record Invocation(String callable, CallableKind callableKind, List<?> arguments) {
        public Invocation {
            Objects.requireNonNull(callable, "callable");
            Objects.requireNonNull(callableKind, "callableKind");
            arguments = freezeArguments(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    public static void installBackend(Backend backend) {
        PROCESS_BACKEND.set(Objects.requireNonNull(backend, "backend"));
    }

    public static void clearBackend() {
        PROCESS_BACKEND.set(null);
    }

    public static ArrayHandle adoptArray(
            Backend backend, long nativeHandle, String elementAbiType, long length) {
        return new ArrayHandle(backend, nativeHandle, elementAbiType, length);
    }

    public static MatrixHandle adoptMatrix(
            Backend backend, long nativeHandle, String elementAbiType, long rows, long cols) {
        long length;
        try {
            length = Math.multiplyExact(rows, cols);
        } catch (ArithmeticException overflow) {
            throw new GpuTransferException("GpuMatrix dimensions overflow addressable length", overflow);
        }
        return new MatrixHandle(new ArrayHandle(backend, nativeHandle, elementAbiType, length), rows, cols);
    }

    public static StreamHandle adoptStream(
            Backend backend, long nativeHandle, String elementAbiType, long lengthHint) {
        return new StreamHandle(backend, nativeHandle, elementAbiType, lengthHint);
    }

    public boolean available() {
        return PROCESS_BACKEND.get() != null;
    }

    public String backendName() {
        Backend backend = PROCESS_BACKEND.get();
        return backend == null ? "unavailable" : backend.name();
    }

    public Object dispatch(String callable, CallableKind callableKind, List<?> arguments) {
        Invocation invocation = new Invocation(callable, callableKind, arguments);
        Backend backend = requireBackend("gpu callable '" + callable + "'");
        validateDeviceOwnership(invocation.arguments(), backend);
        List<ResourcePin> pins = pinDeviceResources(invocation.arguments(), backend);
        Throwable primaryFailure = null;
        try {
            BackendInvocation backendInvocation = new BackendInvocation(
                    callable, callableKind, lowerBackendArguments(invocation.arguments(), backend));

            boolean supported = backendCall(backend, "supports(" + callable + ")",
                    () -> backend.supports(backendInvocation));
            if (!supported) {
                throw new GpuCapabilityUnavailableException(
                        "GPU backend '" + backend.name() + "' cannot execute gpu callable '" + callable + "'");
            }

            Object result = backendCall(
                    backend, "invoke(" + callable + ")", () -> backend.invoke(backendInvocation));
            Object frozen = freezeValue(result, true);
            validateDeviceOwnership(frozen, backend);
            return frozen;
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                closePins(pins);
            } catch (RuntimeException closeFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(closeFailure);
                else throw closeFailure;
            }
        }
    }

    public ArrayHandle uploadArray(List<?> values) {
        Objects.requireNonNull(values, "values");
        List<?> frozen = freezeArguments(values);
        return uploadArray(inferDeviceScalarType(frozen), frozen);
    }

    public ArrayHandle uploadArray(String elementAbiType, List<?> values) {
        Objects.requireNonNull(values, "values");
        String exactType = normalizeDeviceScalarType(elementAbiType);
        Backend backend = requireBackend("GpuArray.from_cpu()");
        List<?> frozen = freezeArguments(values);
        validateArrayElements(exactType, frozen);
        long nativeHandle = backendCall(
                backend,
                "uploadArray(" + exactType + ")",
                () -> backend.uploadArray(exactType, frozen));
        requireNativeHandle(nativeHandle, "GpuArray upload from backend '" + backend.name() + "'");
        return new ArrayHandle(backend, nativeHandle, exactType, frozen.size());
    }

    public List<?> downloadArray(ArrayHandle array) {
        Objects.requireNonNull(array, "array");
        array.ensureLive();
        if (array.length > Integer.MAX_VALUE) {
            throw new GpuTransferException(
                    "GpuArray length " + array.length + " cannot be materialized as a host Array/List");
        }
        try (ResourcePin ignored = array.lease.pin("GpuArray operation")) {
            List<?> downloaded = backendCall(array.backend, "downloadArray",
                    () -> array.backend.downloadArray(
                            array.elementAbiType, array.nativeHandle, array.length));
            Object frozen = freezeValue(downloaded, false);
            if (!(frozen instanceof List<?> list)) {
                throw new GpuTransferException(
                        "GPU backend '" + array.backend.name() + "' downloadArray must return a List");
            }
            if (list.size() != (int) array.length) {
                throw new GpuTransferException(
                        "GPU backend '" + array.backend.name() + "' returned " + list.size()
                                + " element(s) for GpuArray length " + array.length);
            }
            // Device -> host is a copy boundary. Return a fresh mutable host Array/List so
            // ordinary Oreslang indexed mutation semantics continue to work after download.
            return new ArrayList<>(list);
        }
    }

    public MatrixHandle uploadMatrix(List<?> rows) {
        Objects.requireNonNull(rows, "rows");
        int columns = matrixColumnCount(rows);
        validateMatrixShapeForUpload(rows.size(), columns);
        List<Object> flat = flattenMatrix(rows, columns);
        return new MatrixHandle(uploadArray(flat), rows.size(), columns);
    }

    public MatrixHandle uploadMatrix(String elementAbiType, List<?> rows) {
        Objects.requireNonNull(rows, "rows");
        int columns = matrixColumnCount(rows);
        validateMatrixShapeForUpload(rows.size(), columns);
        List<Object> flat = flattenMatrix(rows, columns);
        return new MatrixHandle(uploadArray(elementAbiType, flat), rows.size(), columns);
    }

    public List<?> downloadMatrix(MatrixHandle matrix) {
        Objects.requireNonNull(matrix, "matrix");
        if (matrix.rows > Integer.MAX_VALUE || matrix.cols > Integer.MAX_VALUE) {
            throw new GpuTransferException(
                    "GpuMatrix shape " + matrix.rows + "x" + matrix.cols + " is too large to materialize on the host");
        }
        List<?> flat = downloadArray(matrix.array);
        int rowCount = (int) matrix.rows;
        int colCount = (int) matrix.cols;
        ArrayList<List<Object>> rows = new ArrayList<>(rowCount);
        for (int row = 0; row < rowCount; row++) {
            ArrayList<Object> values = new ArrayList<>(colCount);
            int base = row * colCount;
            for (int col = 0; col < colCount; col++) values.add(flat.get(base + col));
            rows.add(values);
        }
        return new ArrayList<>(rows);
    }

    private static List<Object> flattenMatrix(List<?> rows, int columns) {
        ArrayList<Object> flat = new ArrayList<>(Math.multiplyExact(rows.size(), columns));
        for (int row = 0; row < rows.size(); row++) {
            Object raw = rows.get(row);
            if (!(raw instanceof List<?> values)) {
                throw new GpuTransferException("GpuMatrix.from_cpu row " + row + " is not an Array/List");
            }
            if (values.size() != columns) {
                throw new GpuTransferException(
                        "GpuMatrix.from_cpu requires a rectangular matrix; row " + row
                                + " has " + values.size() + " column(s), expected " + columns);
            }
            flat.addAll(values);
        }
        return flat;
    }

    private static void validateMatrixShapeForUpload(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new GpuTransferException(
                    "GpuMatrix dimensions must be positive; zero-dimension matrices are not supported by GPU ABI v3");
        }
        try {
            Math.multiplyExact((long) rows, (long) cols);
        } catch (ArithmeticException overflow) {
            throw new GpuTransferException("GpuMatrix dimensions overflow addressable length", overflow);
        }
    }

    private static int matrixColumnCount(List<?> rows) {
        if (rows.isEmpty()) return 0;
        Object first = rows.getFirst();
        if (!(first instanceof List<?> firstRow)) {
            throw new GpuTransferException("GpuMatrix.from_cpu row 0 is not an Array/List");
        }
        int columns = firstRow.size();
        for (int row = 1; row < rows.size(); row++) {
            Object raw = rows.get(row);
            if (!(raw instanceof List<?> values)) {
                throw new GpuTransferException("GpuMatrix.from_cpu row " + row + " is not an Array/List");
            }
            if (values.size() != columns) {
                throw new GpuTransferException(
                        "GpuMatrix.from_cpu requires a rectangular matrix; row " + row
                                + " has " + values.size() + " column(s), expected " + columns);
            }
        }
        return columns;
    }

    public StreamHandle stream(ArrayHandle array) {
        Objects.requireNonNull(array, "array");
        array.ensureLive();
        try (ResourcePin ignored = array.lease.pin("GpuArray operation")) {
            long nativeHandle = backendCall(array.backend, "openStream",
                    () -> array.backend.openStream(
                            array.elementAbiType, array.nativeHandle, array.length));
            requireNativeHandle(nativeHandle, "GpuStream from backend '" + array.backend.name() + "'");
            return nativeHandle == array.nativeHandle
                    ? new StreamHandle(array.lease, array.elementAbiType, array.length, true)
                    : new StreamHandle(array.backend, nativeHandle, array.elementAbiType, array.length);
        }
    }

    public ArrayHandle collect(StreamHandle stream) {
        Objects.requireNonNull(stream, "stream");
        stream.ensureLive();
        try (ResourcePin ignored = stream.lease.pin("GpuStream operation")) {
            CollectedArray collected = backendCall(stream.backend, "collectStream",
                    () -> stream.backend.collectStream(
                            stream.elementAbiType, stream.nativeHandle, stream.lengthHint));
            if (collected == null) {
                throw new GpuExecutionException(
                        "GPU backend '" + stream.backend.name() + "' returned null from collectStream", null);
            }
            return collected.nativeHandle() == stream.nativeHandle
                    ? new ArrayHandle(stream.lease, stream.elementAbiType, collected.length(), true)
                    : new ArrayHandle(
                            stream.backend,
                            collected.nativeHandle(),
                            stream.elementAbiType,
                            collected.length());
        }
    }

    public List<?> downloadStream(StreamHandle stream) {
        ArrayHandle collected = collect(stream);
        try {
            return downloadArray(collected);
        } finally {
            release(collected);
        }
    }

    public void release(ArrayHandle array) {
        Objects.requireNonNull(array, "array");
        if (!array.released.compareAndSet(false, true)) {
            throw new GpuTransferException("GpuArray native resource has already been released");
        }
        try {
            array.lease.release("GpuArray");
        } catch (RuntimeException failure) {
            array.released.set(false);
            throw failure;
        }
    }

    public void release(MatrixHandle matrix) {
        Objects.requireNonNull(matrix, "matrix");
        release(matrix.array);
    }

    public void release(StreamHandle stream) {
        Objects.requireNonNull(stream, "stream");
        if (!stream.released.compareAndSet(false, true)) {
            throw new GpuTransferException("GpuStream native resource has already been released");
        }
        try {
            stream.lease.release("GpuStream");
        } catch (RuntimeException failure) {
            stream.released.set(false);
            throw failure;
        }
    }

    private static long requireNativeHandle(long nativeHandle, String where) {
        if (nativeHandle == 0L) {
            throw new GpuTransferException(where + " returned/contains invalid native resource handle 0");
        }
        return nativeHandle;
    }

    private static List<ResourcePin> pinDeviceResources(Object value, Backend expected) {
        Set<ResourceLease> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayList<ResourcePin> pins = new ArrayList<>();
        try {
            pinDeviceResources(value, expected, seen, pins);
            return List.copyOf(pins);
        } catch (RuntimeException failure) {
            closePins(pins);
            throw failure;
        }
    }

    private static void pinDeviceResources(
            Object value,
            Backend expected,
            Set<ResourceLease> seen,
            List<ResourcePin> pins) {
        if (value == null) return;
        if (value instanceof ArrayHandle array) {
            validateDeviceOwnership(array, expected);
            if (seen.add(array.lease)) pins.add(array.lease.pin("GpuArray operation"));
            return;
        }
        if (value instanceof MatrixHandle matrix) {
            validateDeviceOwnership(matrix, expected);
            if (seen.add(matrix.array.lease)) pins.add(matrix.array.lease.pin("GpuMatrix operation"));
            return;
        }
        if (value instanceof StreamHandle stream) {
            validateDeviceOwnership(stream, expected);
            if (seen.add(stream.lease)) pins.add(stream.lease.pin("GpuStream operation"));
            return;
        }
        if (value instanceof OptionValue option) {
            if (option.present()) pinDeviceResources(option.value(), expected, seen, pins);
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) pinDeviceResources(item, expected, seen, pins);
            return;
        }
        if (value instanceof Set<?> set) {
            for (Object item : set) pinDeviceResources(item, expected, seen, pins);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                pinDeviceResources(entry.getKey(), expected, seen, pins);
                pinDeviceResources(entry.getValue(), expected, seen, pins);
            }
        }
    }

    private static void closePins(List<ResourcePin> pins) {
        RuntimeException first = null;
        for (int i = pins.size() - 1; i >= 0; i--) {
            try {
                pins.get(i).close();
            } catch (RuntimeException failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }

    private static List<?> lowerBackendArguments(List<?> arguments, Backend expected) {
        ArrayList<Object> lowered = new ArrayList<>(arguments.size());
        for (Object argument : arguments) lowered.add(lowerBackendValue(argument, expected));
        return List.copyOf(lowered);
    }

    private static Object lowerBackendValue(Object value, Backend expected) {
        if (value instanceof ArrayHandle array) {
            array.ensureLive();
            validateDeviceOwnership(array, expected);
            return new NativeResourceArg(
                    array.nativeHandle, ResourceKind.ARRAY, array.elementAbiType, array.length, 0L, 0L);
        }
        if (value instanceof MatrixHandle matrix) {
            matrix.array.ensureLive();
            validateDeviceOwnership(matrix, expected);
            return new NativeResourceArg(
                    matrix.array.nativeHandle,
                    ResourceKind.MATRIX,
                    matrix.elementAbiType(),
                    matrix.length(),
                    matrix.rows,
                    matrix.cols);
        }
        if (value instanceof StreamHandle stream) {
            stream.ensureLive();
            validateDeviceOwnership(stream, expected);
            return new NativeResourceArg(
                    stream.nativeHandle,
                    ResourceKind.STREAM,
                    stream.elementAbiType,
                    stream.lengthHint,
                    0L,
                    0L);
        }
        if (value instanceof OptionValue option) {
            return option.present()
                    ? new OptionValue(true, lowerBackendValue(option.value(), expected))
                    : option;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> lowered = new ArrayList<>(list.size());
            for (Object item : list) lowered.add(lowerBackendValue(item, expected));
            return List.copyOf(lowered);
        }
        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> lowered = new LinkedHashSet<>();
            for (Object item : set) lowered.add(lowerBackendValue(item, expected));
            return Collections.unmodifiableSet(lowered);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> lowered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                lowered.put(
                        lowerBackendValue(entry.getKey(), expected),
                        lowerBackendValue(entry.getValue(), expected));
            }
            return Collections.unmodifiableMap(lowered);
        }
        return value;
    }

    private static String inferDeviceScalarType(List<?> values) {
        if (values.isEmpty()) {
            throw new GpuTransferException(
                    "cannot infer an exact GPU element type from an empty Array/List; use a typed GPU transfer path");
        }

        boolean sawBoolean = false;
        boolean sawInteger = false;
        boolean sawFloating = false;
        boolean sawComplex = false;
        for (Object value : values) {
            if (value instanceof Boolean) sawBoolean = true;
            else if (value instanceof ComplexValue) sawComplex = true;
            else if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) sawFloating = true;
            else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger) sawInteger = true;
            else throw new GpuTransferException(
                    "cannot infer GPU element ABI type from " + (value == null ? "null" : value.getClass().getName()));
        }

        int families = (sawBoolean ? 1 : 0) + (sawComplex ? 1 : 0)
                + ((sawInteger || sawFloating) ? 1 : 0);
        if (families != 1) {
            throw new GpuTransferException("GPU array/matrix transfer cannot mix boolean, numeric, and complex element families");
        }
        if (sawBoolean) return "bool";
        if (sawComplex) {
            if (sawInteger || sawFloating) {
                throw new GpuTransferException("complex GPU transfer requires explicit complex values for every element");
            }
            return "complex128";
        }
        return sawFloating ? "f64" : "i64";
    }

    private static String normalizeDeviceScalarType(String type) {
        Objects.requireNonNull(type, "elementAbiType");
        return switch (type.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "bool" -> "bool";
            case "i8" -> "i8";
            case "u8" -> "u8";
            case "i16" -> "i16";
            case "u16" -> "u16";
            case "i32" -> "i32";
            case "u32" -> "u32";
            case "i64", "int" -> "i64";
            case "u64", "uint" -> "u64";
            case "f32" -> "f32";
            case "f64", "float" -> "f64";
            case "complex64" -> "complex64";
            case "complex128", "complex" -> "complex128";
            default -> throw new GpuTransferException(
                    "unsupported exact GPU element ABI type '" + type + "'");
        };
    }

    private static void validateArrayElements(String type, List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!validElement(type, value)) {
                throw new GpuTransferException(
                        "GpuArray<" + type + "> element " + i
                                + " cannot be represented exactly/safely from "
                                + (value == null ? "null" : value.getClass().getSimpleName())
                                + " value " + value);
            }
        }
    }

    private static boolean validElement(String type, Object value) {
        if (type.equals("bool")) return value instanceof Boolean;
        if (type.equals("complex64") || type.equals("complex128")) {
            if (!(value instanceof ComplexValue complex)) return false;
            if (!Double.isFinite(complex.real()) || !Double.isFinite(complex.imaginary())) return false;
            if (type.equals("complex128")) return true;
            return Math.abs(complex.real()) <= Float.MAX_VALUE && Math.abs(complex.imaginary()) <= Float.MAX_VALUE;
        }
        if (!(value instanceof Number number)) return false;

        if (type.equals("f32") || type.equals("f64")) {
            double d = number.doubleValue();
            if (!Double.isFinite(d)) return false;
            return !type.equals("f32") || Math.abs(d) <= Float.MAX_VALUE;
        }

        BigInteger integer;
        if (value instanceof BigInteger big) {
            integer = big;
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(number.longValue());
        } else if (value instanceof BigDecimal decimal) {
            try {
                integer = decimal.toBigIntegerExact();
            } catch (ArithmeticException failure) {
                return false;
            }
        } else {
            double d = number.doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d)) return false;
            try {
                integer = BigDecimal.valueOf(d).toBigIntegerExact();
            } catch (ArithmeticException failure) {
                return false;
            }
        }

        return switch (type) {
            case "i8" -> between(integer, BigInteger.valueOf(Byte.MIN_VALUE), BigInteger.valueOf(Byte.MAX_VALUE));
            case "u8" -> between(integer, BigInteger.ZERO, BigInteger.valueOf(0xffL));
            case "i16" -> between(integer, BigInteger.valueOf(Short.MIN_VALUE), BigInteger.valueOf(Short.MAX_VALUE));
            case "u16" -> between(integer, BigInteger.ZERO, BigInteger.valueOf(0xffffL));
            case "i32" -> between(integer, BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE));
            case "u32" -> between(integer, BigInteger.ZERO, BigInteger.valueOf(0xffff_ffffL));
            case "i64" -> between(integer, BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE));
            case "u64" -> between(
                    integer,
                    BigInteger.ZERO,
                    BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE));
            default -> false;
        };
    }

    private static boolean between(BigInteger value, BigInteger min, BigInteger max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    private Backend requireBackend(String operation) {
        Backend backend = PROCESS_BACKEND.get();
        if (backend == null) {
            throw new GpuUnavailableException(
                    operation + " requires a GPU backend; none is installed and CPU fallback is forbidden");
        }
        return backend;
    }

    private static void backendCallVoid(Backend backend, String operation, Runnable call) {
        try {
            call.run();
        } catch (GpuException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new GpuExecutionException(
                    "GPU backend '" + backend.name() + "' failed during " + operation, failure);
        }
    }

    private static <T> T backendCall(Backend backend, String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (GpuException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new GpuExecutionException(
                    "GPU backend '" + backend.name() + "' failed during " + operation, failure);
        }
    }

    private static void validateDeviceOwnership(Object value, Backend expected) {
        if (value == null) return;
        if (value instanceof ArrayHandle array) {
            array.ensureLive();
            if (array.backend != expected) {
                throw new GpuTransferException(
                        "GpuArray belongs to backend '" + array.backend.name()
                                + "' but callable is executing on backend '" + expected.name() + "'");
            }
            return;
        }
        if (value instanceof MatrixHandle matrix) {
            validateDeviceOwnership(matrix.array, expected);
            return;
        }
        if (value instanceof StreamHandle stream) {
            stream.ensureLive();
            if (stream.backend != expected) {
                throw new GpuTransferException(
                        "GpuStream belongs to backend '" + stream.backend.name()
                                + "' but callable is executing on backend '" + expected.name() + "'");
            }
            return;
        }
        if (value instanceof OptionValue option) {
            if (option.present()) validateDeviceOwnership(option.value(), expected);
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) validateDeviceOwnership(item, expected);
            return;
        }
        if (value instanceof Set<?> set) {
            for (Object item : set) validateDeviceOwnership(item, expected);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                validateDeviceOwnership(entry.getKey(), expected);
                validateDeviceOwnership(entry.getValue(), expected);
            }
        }
    }

    private static List<?> freezeArguments(List<?> arguments) {
        ArrayList<Object> frozen = new ArrayList<>(arguments.size());
        for (Object argument : arguments) frozen.add(freezeValue(argument, false));
        return List.copyOf(frozen);
    }

    private static Object freezeValue(Object value, boolean allowRootVoidNull) {
        if (value == null) {
            if (allowRootVoidNull) return null;
            throw new GpuTransferException(
                    "standalone null cannot cross the Oreslang GPU boundary; use Option");
        }

        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal || value instanceof Enum<?>
                || value instanceof UUID || value instanceof ComplexValue
                || value instanceof ArrayHandle || value instanceof MatrixHandle || value instanceof StreamHandle) {
            return value;
        }

        if (value instanceof OptionValue option) {
            return option.present()
                    ? new OptionValue(true, freezeValue(option.value(), false))
                    : new OptionValue(false, null);
        }

        if (value instanceof Transferable transferable) {
            Object transferred = transferable.freezeForGpu();
            if (transferred == value) {
                throw new GpuTransferException(
                        "GPU transferable values must produce a distinct wire representation");
            }
            return freezeValue(transferred, false);
        }

        if (value instanceof List<?> list) {
            ArrayList<Object> frozen = new ArrayList<>(list.size());
            for (Object item : list) frozen.add(freezeValue(item, false));
            return List.copyOf(frozen);
        }

        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> frozen = new LinkedHashSet<>();
            for (Object item : set) frozen.add(freezeValue(item, false));
            return Collections.unmodifiableSet(frozen);
        }

        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                frozen.put(freezeValue(entry.getKey(), false), freezeValue(entry.getValue(), false));
            }
            return Collections.unmodifiableMap(frozen);
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> frozen = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                frozen.add(freezeValue(Array.get(value, i), false));
            }
            return List.copyOf(frozen);
        }

        throw new GpuTransferException(
                "value of type " + value.getClass().getName()
                        + " cannot cross the Oreslang GPU boundary");
    }

    public static class GpuException extends RuntimeException {
        public GpuException(String message) { super(message); }
        public GpuException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class GpuUnavailableException extends GpuException {
        public GpuUnavailableException(String message) { super(message); }
    }

    public static final class GpuCapabilityUnavailableException extends GpuException {
        public GpuCapabilityUnavailableException(String message) { super(message); }
    }

    public static final class GpuExecutionException extends GpuException {
        public GpuExecutionException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class GpuTransferException extends GpuException {
        public GpuTransferException(String message) { super(message); }
        public GpuTransferException(String message, Throwable cause) { super(message, cause); }
    }
}
