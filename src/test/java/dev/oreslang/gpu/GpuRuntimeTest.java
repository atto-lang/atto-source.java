package dev.oreslang.gpu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class GpuRuntimeTest {
    @AfterEach
    void clearBackend() {
        GpuRuntime.clearBackend();
    }

    @Test
    void neverFallsBackToCpuWhenNoBackendIsInstalled() {
        GpuRuntime runtime = new GpuRuntime();
        GpuRuntime.GpuUnavailableException failure = assertThrows(
                GpuRuntime.GpuUnavailableException.class,
                () -> runtime.dispatch("math.add", GpuRuntime.CallableKind.FNC, List.of(1L, 2L)));
        assertTrue(failure.getMessage().contains("CPU fallback is forbidden"));
    }

    @Test
    void residentTransfersNeverFallBackWithoutABackend() {
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.GpuUnavailableException upload = assertThrows(
                GpuRuntime.GpuUnavailableException.class,
                () -> runtime.uploadArray("i64", List.of(1L, 2L, 3L)));
        assertTrue(upload.getMessage().contains("CPU fallback is forbidden"));
    }

    @Test
    void freezesGuestCollectionsBeforeBackendInvocation() {
        ArrayList<Object> nested = new ArrayList<>();
        nested.add(7L);
        ArrayList<Object> original = new ArrayList<>();
        original.add(nested);

        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "freeze-test"; }

            @Override
            public Object invoke(GpuRuntime.Invocation invocation) {
                List<?> outer = assertInstanceOf(List.class, invocation.arguments().getFirst());
                List<?> inner = assertInstanceOf(List.class, outer.getFirst());
                assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) outer).add(9L));
                assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) inner).add(9L));
                nested.set(0, 99L);
                assertEquals(7L, inner.getFirst());
                return 41L;
            }
        });

        assertEquals(41L, new GpuRuntime().dispatch(
                "test.freeze", GpuRuntime.CallableKind.FNC, List.of(original)));
    }

    @Test
    void deviceHandlesRemainBoundToCreatingBackend() {
        GpuRuntime.Backend first = new TransferBackend("first");
        GpuRuntime.Backend second = new TransferBackend("second");

        GpuRuntime.installBackend(first);
        GpuRuntime.ArrayHandle array = new GpuRuntime().uploadArray("i64", List.of(1L, 2L));

        GpuRuntime.installBackend(second);
        GpuRuntime.GpuTransferException failure = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> new GpuRuntime().dispatch(
                        "test.consume", GpuRuntime.CallableKind.FNC, List.of(array)));
        assertTrue(failure.getMessage().contains("belongs to backend 'first'"));
    }

    @Test
    void matrixTransfersAreRectangularRowMajorAndDetached() {
        GpuRuntime.installBackend(new TransferBackend("matrix-transfer"));
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.MatrixHandle matrix = runtime.uploadMatrix(
                "f64",
                List.of(List.of(1.0, 2.0, 3.0), List.of(4.0, 5.0, 6.0)));

        assertEquals(2L, matrix.rows());
        assertEquals(3L, matrix.cols());
        assertEquals(6L, matrix.length());
        assertEquals("f64", matrix.elementAbiType());
        assertEquals(
                List.of(List.of(1.0, 2.0, 3.0), List.of(4.0, 5.0, 6.0)),
                runtime.downloadMatrix(matrix));

        @SuppressWarnings("unchecked")
        List<List<Object>> host = (List<List<Object>>) (List<?>) runtime.downloadMatrix(matrix);
        host.getFirst().set(0, 99.0);
        assertEquals(1.0, ((List<?>) runtime.downloadMatrix(matrix).getFirst()).getFirst());
    }

    @Test
    void matrixTransfersRejectJaggedAndZeroDimensionShapes() {
        GpuRuntime.installBackend(new TransferBackend("matrix-shape"));
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.GpuTransferException jagged = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> runtime.uploadMatrix(
                        "f32",
                        List.of(List.of(1.0), List.of(2.0, 3.0))));
        assertTrue(jagged.getMessage().contains("rectangular"));

        GpuRuntime.GpuTransferException empty = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> runtime.uploadMatrix("f32", List.of()));
        assertTrue(empty.getMessage().contains("dimensions must be positive"));

        GpuRuntime.GpuTransferException zeroColumns = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> runtime.uploadMatrix("f32", List.of(List.of())));
        assertTrue(zeroColumns.getMessage().contains("dimensions must be positive"));
    }

    @Test
    void complexMatrixTransfersPreserveComplexAbi() {
        GpuRuntime.installBackend(new TransferBackend("matrix-complex"));
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.MatrixHandle matrix = runtime.uploadMatrix(
                "complex64",
                List.of(
                        List.of(
                                new GpuRuntime.ComplexValue(1.0, 2.0),
                                new GpuRuntime.ComplexValue(3.0, -4.0))));

        assertEquals("complex64", matrix.elementAbiType());
        assertEquals(1L, matrix.rows());
        assertEquals(2L, matrix.cols());
        assertEquals(
                List.of(List.of(
                        new GpuRuntime.ComplexValue(1.0, 2.0),
                        new GpuRuntime.ComplexValue(3.0, -4.0))),
                runtime.downloadMatrix(matrix));
    }

    @Test
    void streamCollectPreservesBackendAndConcreteCardinality() {
        GpuRuntime.installBackend(new TransferBackend("stream-test"));
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.ArrayHandle uploaded = runtime.uploadArray("i64", List.of(1L, 2L, 3L));
        GpuRuntime.StreamHandle stream = runtime.stream(uploaded);
        GpuRuntime.ArrayHandle collected = runtime.collect(stream);

        assertEquals("stream-test", stream.backendName());
        assertEquals(3L, stream.lengthHint());
        assertEquals("stream-test", collected.backendName());
        assertEquals(3L, collected.length());
        assertEquals(List.of(1L, 2L, 3L), runtime.downloadArray(collected));
    }

    @Test
    void downloadedArraysAreDetachedMutableHostCopies() {
        GpuRuntime.installBackend(new TransferBackend("mutable-download"));
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.ArrayHandle array = runtime.uploadArray("i64", List.of(1L, 2L, 3L));
        List<?> downloaded = runtime.downloadArray(array);

        @SuppressWarnings("unchecked")
        List<Object> mutable = (List<Object>) downloaded;
        mutable.set(0, 99L);

        assertEquals(List.of(99L, 2L, 3L), mutable);
        assertEquals(List.of(1L, 2L, 3L), runtime.downloadArray(array),
                "host mutation must not mutate device-resident storage");
    }

    @Test
    void residentHandlesPreserveExactElementAbiAcrossStreamsAndCollections() {
        GpuRuntime.installBackend(new TransferBackend("typed-resident"));
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.ArrayHandle array = runtime.uploadArray("i32", List.of(1L, 2L));
        GpuRuntime.StreamHandle stream = runtime.stream(array);
        GpuRuntime.ArrayHandle collected = runtime.collect(stream);

        assertEquals("i32", array.elementAbiType());
        assertEquals("i32", stream.elementAbiType());
        assertEquals("i32", collected.elementAbiType());
    }

    @Test
    void residentUploadsRejectUnknownOrOutOfRangeElementAbi() {
        GpuRuntime.installBackend(new TransferBackend("typed-validation"));
        GpuRuntime runtime = new GpuRuntime();

        assertThrows(GpuRuntime.GpuTransferException.class,
                () -> runtime.uploadArray("object", List.of(1L)));

        GpuRuntime.GpuTransferException overflow = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> runtime.uploadArray("i8", List.of(128L)));
        assertTrue(overflow.getMessage().contains("GpuArray<i8>"));

        assertDoesNotThrow(() -> runtime.uploadArray(
                "u64",
                List.of(new java.math.BigInteger("18446744073709551615"))));
        assertThrows(GpuRuntime.GpuTransferException.class, () -> runtime.uploadArray(
                "u64",
                List.of(new java.math.BigInteger("18446744073709551616"))));
    }

    @Test
    void rejectsBackendDownloadLengthMismatch() {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "bad-length"; }
            @Override public Object invoke(GpuRuntime.Invocation invocation) { return null; }
            @Override public long uploadArray(String elementAbiType, List<?> values) {
                assertEquals("i64", elementAbiType);
                return 1L;
            }
            @Override public List<?> downloadArray(String elementAbiType, long nativeHandle, long length) {
                assertEquals("i64", elementAbiType);
                assertEquals(1L, nativeHandle);
                return List.of(1L);
            }
        });

        GpuRuntime runtime = new GpuRuntime();
        GpuRuntime.ArrayHandle array = runtime.uploadArray("i64", List.of(1L, 2L));

        GpuRuntime.GpuTransferException failure = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> runtime.downloadArray(array));
        assertTrue(failure.getMessage().contains("returned 1 element(s) for GpuArray length 2"));
    }

    @Test
    void nativeResourceHandleZeroIsAlwaysRejected() {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "zero-handle"; }
            @Override public Object invoke(GpuRuntime.Invocation invocation) { return null; }
            @Override public long uploadArray(String elementAbiType, List<?> values) { return 0L; }
        });

        GpuRuntime.GpuTransferException failure = assertThrows(
                GpuRuntime.GpuTransferException.class,
                () -> new GpuRuntime().uploadArray("i64", List.of(1L)));
        assertTrue(failure.getMessage().contains("handle 0"));
    }

    @Test
    void resourceReleaseUsesOpaqueNativeHandle() {
        TransferBackend backend = new TransferBackend("release-test");
        GpuRuntime.installBackend(backend);
        GpuRuntime runtime = new GpuRuntime();

        GpuRuntime.ArrayHandle array = runtime.uploadArray("i64", List.of(1L, 2L));
        assertEquals(List.of(1L, 2L), runtime.downloadArray(array));

        runtime.release(array);
        assertThrows(GpuRuntime.GpuExecutionException.class, () -> runtime.downloadArray(array));
    }

    @Test
    void guestHandlesExposeNoRawBackendTokenAccessor() {
        assertThrows(NoSuchMethodException.class,
                () -> GpuRuntime.ArrayHandle.class.getMethod("backendToken"));
        assertThrows(NoSuchMethodException.class,
                () -> GpuRuntime.MatrixHandle.class.getMethod("backendToken"));
        assertThrows(NoSuchMethodException.class,
                () -> GpuRuntime.StreamHandle.class.getMethod("backendToken"));
    }

    @Test
    void wrapsUnexpectedBackendFailuresWithoutMaskingGpuExceptions() {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "boom"; }
            @Override public Object invoke(GpuRuntime.Invocation invocation) {
                throw new IllegalStateException("driver exploded");
            }
        });

        GpuRuntime.GpuExecutionException failure = assertThrows(
                GpuRuntime.GpuExecutionException.class,
                () -> new GpuRuntime().dispatch(
                        "test.boom", GpuRuntime.CallableKind.FNC, List.of(1L)));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("invoke(test.boom)"));
    }

    private static final class TransferBackend implements GpuRuntime.Backend {
        private final String name;
        private final AtomicLong nextHandle = new AtomicLong(1L);
        private final Map<Long, List<?>> buffers = new HashMap<>();

        private TransferBackend(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public Object invoke(GpuRuntime.Invocation invocation) { return null; }

        @Override
        public synchronized long uploadArray(String elementAbiType, List<?> values) {
            long handle = nextHandle.getAndIncrement();
            buffers.put(handle, List.copyOf(values));
            return handle;
        }

        @Override
        public synchronized List<?> downloadArray(String elementAbiType, long nativeHandle, long length) {
            List<?> values = buffers.get(nativeHandle);
            if (values == null) throw new IllegalStateException("stale native test handle " + nativeHandle);
            return values;
        }

        @Override
        public synchronized void release(long nativeHandle) {
            buffers.remove(nativeHandle);
        }
    }
}
