package dev.oreslang.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class LinuxUringFileIoProviderTest {
    @TempDir Path root;

    private static final class FakeBridge
            implements LinuxUringFileIoProvider.NativeBridge {
        LinuxUringFileIoProvider owner;
        final List<Long> reads = new ArrayList<>();
        final List<Long> exists = new ArrayList<>();
        final List<Long> cancels = new ArrayList<>();
        final List<WriteCall> writes = new ArrayList<>();
        boolean accept = true;
        boolean cancelAccept = true;
        int closes;

        record WriteCall(long id, byte[] path, byte[] value, boolean append) {}

        @Override
        public long create(LinuxUringFileIoProvider owner, int entries) {
            this.owner = owner;
            assertEquals(LinuxUringFileIoProvider.DEFAULT_ENTRIES, entries);
            return 77L;
        }

        @Override
        public boolean submitRead(long handle, long id, byte[] path, int maxBytes) {
            assertEquals(77L, handle);
            assertEquals(LinuxUringFileIoProvider.MAX_READ_BYTES, maxBytes);
            assertEquals('/', (char) path[0]);
            reads.add(id);
            return accept;
        }

        @Override
        public boolean submitExists(long handle, long id, byte[] path) {
            assertEquals(77L, handle);
            assertEquals('/', (char) path[0]);
            exists.add(id);
            return accept;
        }

        @Override
        public boolean submitWrite(
                long handle, long id, byte[] path, byte[] value, boolean append) {
            assertEquals(77L, handle);
            assertEquals('/', (char) path[0]);
            writes.add(new WriteCall(id, path.clone(), value.clone(), append));
            return accept;
        }

        @Override
        public boolean cancel(long handle, long id) {
            assertEquals(77L, handle);
            cancels.add(id);
            return cancelAccept;
        }

        @Override
        public void close(long handle) {
            assertEquals(77L, handle);
            closes++;
        }
    }

    @Test
    void cancelledReadIgnoresLateNativeCompletionAndReleasesPendingState() {
        FakeBridge bridge = new FakeBridge();
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);
        OresFuture<String> future = provider.readText("/tmp/strict-read");

        assertEquals(1, bridge.reads.size());
        long id = bridge.reads.getFirst();
        assertEquals(1, provider.pendingCount());
        assertEquals(
                LinuxUringFileIoProvider.MAX_READ_BYTES,
                provider.reservedByteCount());

        assertTrue(future.cancel(false));
        assertTrue(future.isCancelled());
        assertEquals(List.of(id), bridge.cancels);
        assertEquals(1, provider.pendingCount(),
                "native ownership remains until terminal completion");

        provider.nativeReadCompleted(
                id, "late".getBytes(StandardCharsets.UTF_8), 0);
        assertEquals(0, provider.pendingCount());
        assertEquals(0, provider.reservedByteCount());
        assertTrue(future.isCancelled());

        provider.nativeReadCompleted(
                id, "duplicate".getBytes(StandardCharsets.UTF_8), 0);
        assertEquals(0, provider.pendingCount());
        assertTrue(future.isCancelled());
        provider.close();
    }

    @Test
    void cancellationIsRejectedWhenNativeCancelCannotBeEnqueued() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.cancelAccept = false;
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);
        OresFuture<String> future = provider.readText("/tmp/cancel-admission");
        long id = bridge.reads.getFirst();

        assertFalse(future.cancel(false),
                "Future cancellation cannot win without native revocation admission");
        assertFalse(future.isCancelled());
        assertEquals(List.of(id), bridge.cancels);
        assertEquals(1, provider.pendingCount());

        provider.nativeReadCompleted(
                id, "winner".getBytes(StandardCharsets.UTF_8), 0);
        assertEquals("winner", future.get(1, TimeUnit.SECONDS));
        assertEquals(0, provider.pendingCount());
        provider.close();
    }

    @Test
    void providerCloseCancelsAndFailsPendingOperationsExactlyOnce() {
        FakeBridge bridge = new FakeBridge();
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);
        OresFuture<Boolean> future = provider.exists("/tmp/pending");
        long id = bridge.exists.getFirst();

        provider.close();
        assertEquals(List.of(id), bridge.cancels);
        assertEquals(1, bridge.closes);
        assertEquals(0, provider.pendingCount());
        assertTrue(future.isDone());
        ExecutionException failure =
                assertThrows(ExecutionException.class, future::get);
        assertTrue(failure.getCause().getMessage().contains("provider is closed"));

        provider.nativeExistsCompleted(id, true, 0);
        provider.close();
        assertEquals(1, bridge.closes);
        assertEquals(0, provider.pendingCount());
    }

    @Test
    void rejectedNativeSubmissionDoesNotLeakAdmission() {
        FakeBridge bridge = new FakeBridge();
        bridge.accept = false;
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);

        OresFuture<String> future = provider.readText("/tmp/rejected");
        assertEquals(0, provider.pendingCount());
        assertEquals(0, provider.reservedByteCount());
        ExecutionException failure =
                assertThrows(ExecutionException.class, future::get);
        assertTrue(failure.getCause().getMessage().contains("submission was rejected"));
        provider.close();
    }

    @Test
    void providerBoundsAggregateReadReservationsAndSharesBudgetWithWrites() {
        FakeBridge bridge = new FakeBridge();
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);
        List<OresFuture<String>> admitted = new ArrayList<>();

        int slots = Math.toIntExact(
                LinuxUringFileIoProvider.MAX_RESERVED_BYTES
                        / LinuxUringFileIoProvider.MAX_READ_BYTES);
        for (int index = 0; index < slots; index++) {
            admitted.add(provider.readText("/tmp/budget-" + index));
        }
        assertEquals(slots, provider.pendingCount());
        assertEquals(
                LinuxUringFileIoProvider.MAX_RESERVED_BYTES,
                provider.reservedByteCount());

        OresFuture<String> readOverflow = provider.readText("/tmp/budget-overflow");
        assertTrue(readOverflow.isDone());
        assertThrows(ExecutionException.class, readOverflow::get);

        OresFuture<Void> writeOverflow = provider.writeText("/tmp/out", "x");
        assertTrue(writeOverflow.isDone());
        assertThrows(ExecutionException.class, writeOverflow::get);
        assertTrue(bridge.writes.isEmpty(),
                "byte-budget denial must happen before native write submission");

        provider.close();
        assertTrue(admitted.stream().allMatch(OresFuture::isDone));
        assertEquals(0, provider.pendingCount());
        assertEquals(0, provider.reservedByteCount());
    }

    @Test
    void providerEnforcesBoundedPendingAdmission() {
        FakeBridge bridge = new FakeBridge();
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);
        List<OresFuture<Boolean>> accepted = new ArrayList<>();
        for (int index = 0; index < LinuxUringFileIoProvider.MAX_PENDING; index++) {
            accepted.add(provider.exists("/tmp/pending-" + index));
        }
        assertEquals(LinuxUringFileIoProvider.MAX_PENDING, provider.pendingCount());

        OresFuture<Boolean> overflow = provider.exists("/tmp/overflow");
        assertEquals(LinuxUringFileIoProvider.MAX_PENDING, provider.pendingCount());
        assertThrows(ExecutionException.class, overflow::get);

        provider.close();
        assertTrue(accepted.stream().allMatch(OresFuture::isDone));
        assertEquals(0, provider.pendingCount());
    }

    @Test
    void invalidUtf8FailsRatherThanReplacingBytes() {
        FakeBridge bridge = new FakeBridge();
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);
        OresFuture<String> future = provider.readText("/tmp/bad-utf8");
        long id = bridge.reads.getFirst();

        provider.nativeReadCompleted(id, new byte[] {(byte) 0xc3, 0x28}, 0);
        ExecutionException failure =
                assertThrows(ExecutionException.class, future::get);
        assertTrue(failure.getCause().getMessage().contains("invalid UTF-8"));
        assertEquals(0, provider.pendingCount());
        provider.close();
    }

    @Test
    void writeAndAppendDeclineGuestCancellationAndSettleFromNativeCompletion()
            throws Exception {
        FakeBridge bridge = new FakeBridge();
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);

        OresFuture<Void> write = provider.writeText("/tmp/out", "value");
        assertEquals(1, bridge.writes.size());
        FakeBridge.WriteCall first = bridge.writes.getFirst();
        assertEquals(first.value().length, provider.reservedByteCount());
        assertFalse(first.append());
        assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), first.value());
        assertFalse(write.cancel(false),
                "partial filesystem mutation cannot promise rollback");
        assertTrue(bridge.cancels.isEmpty(),
                "guest mutation cancellation must not reach native cancel");
        provider.nativeWriteCompleted(first.id(), 0);
        assertNull(write.get(1, TimeUnit.SECONDS));
        assertEquals(0, provider.reservedByteCount());

        OresFuture<Void> append = provider.appendText("/tmp/out", " more");
        FakeBridge.WriteCall second = bridge.writes.get(1);
        assertEquals(second.value().length, provider.reservedByteCount());
        assertTrue(second.append());
        assertArrayEquals(" more".getBytes(StandardCharsets.UTF_8), second.value());
        assertFalse(append.cancel(false));
        provider.nativeWriteCompleted(second.id(), 0);
        assertNull(append.get(1, TimeUnit.SECONDS));
        assertEquals(0, provider.pendingCount());
        assertEquals(0, provider.reservedByteCount());
        provider.close();
    }

    @Test
    void writeSubmissionFailureAndInvalidUnicodeFailClosed() {
        FakeBridge bridge = new FakeBridge();
        bridge.accept = false;
        LinuxUringFileIoProvider provider = new LinuxUringFileIoProvider(bridge);

        OresFuture<Void> rejected = provider.writeText("/tmp/out", "value");
        assertEquals(0, provider.pendingCount());
        assertThrows(ExecutionException.class, rejected::get);

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.writeText(
                        "/tmp/out", new String(new char[] {(char) 0xd800})));
        provider.close();
    }

    @Test
    void unsupportedProviderFailsClosedWithoutWorkerFallback() {
        StrictFileIoProvider provider = StrictFileIoProvider.Unsupported.INSTANCE;
        assertFalse(provider.supported());
        OresFuture<String> future = provider.readText("/tmp/unsupported");
        ExecutionException failure =
                assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }

    @Test
    void realLinuxBlockedFifoOpenCancelsAndPhysicallyDrainsWhenAvailable()
            throws Exception {
        StrictFileIoProvider candidate = LinuxUringFileIoProvider.tryCreate();
        Assumptions.assumeTrue(candidate instanceof LinuxUringFileIoProvider,
                "kernel/JNI io_uring provider unavailable");
        LinuxUringFileIoProvider provider =
                (LinuxUringFileIoProvider) candidate;
        try {
            Path fifo = root.resolve("blocked.fifo");
            Process mkfifo;
            try {
                mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
            } catch (java.io.IOException unavailable) {
                Assumptions.abort("mkfifo unavailable");
                return;
            }
            Assumptions.assumeTrue(
                    mkfifo.waitFor(5, TimeUnit.SECONDS) && mkfifo.exitValue() == 0,
                    "mkfifo unavailable");

            OresFuture<String> future =
                    provider.readText(fifo.toAbsolutePath().toString());
            assertEquals(1, provider.pendingCount());
            assertTrue(future.cancel(false),
                    "native async-cancel admission is required");
            assertTrue(future.isCancelled());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (provider.pendingCount() != 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(0, provider.pendingCount(),
                    "physical io_uring completion must release native ownership");
            assertTrue(future.isCancelled());
        } finally {
            provider.close();
        }
    }

    @Test
    void realLinuxProviderReadsAndProbesThroughIoUringWhenAvailable()
            throws Exception {
        StrictFileIoProvider candidate = LinuxUringFileIoProvider.tryCreate();
        Assumptions.assumeTrue(candidate instanceof LinuxUringFileIoProvider,
                "kernel/JNI io_uring provider unavailable");
        LinuxUringFileIoProvider provider =
                (LinuxUringFileIoProvider) candidate;
        try {
            Path file = root.resolve("hello.txt");
            Files.writeString(file, "hello from io_uring", StandardCharsets.UTF_8);

            assertEquals(
                    "hello from io_uring",
                    provider.readText(file.toAbsolutePath().toString())
                            .get(5, TimeUnit.SECONDS));
            assertTrue(provider.exists(file.toAbsolutePath().toString())
                    .get(5, TimeUnit.SECONDS));
            assertFalse(provider.exists(root.resolve("missing.txt")
                            .toAbsolutePath().toString())
                    .get(5, TimeUnit.SECONDS));

            Path output = root.resolve("output.txt");
            assertNull(provider.writeText(
                            output.toAbsolutePath().toString(), "first")
                    .get(5, TimeUnit.SECONDS));
            assertEquals("first", Files.readString(output, StandardCharsets.UTF_8));
            assertNull(provider.appendText(
                            output.toAbsolutePath().toString(), " second")
                    .get(5, TimeUnit.SECONDS));
            assertEquals(
                    "first second",
                    Files.readString(output, StandardCharsets.UTF_8));

            Path protectedTarget = root.resolve("protected.txt");
            Files.writeString(protectedTarget, "safe", StandardCharsets.UTF_8);
            Path writeLink = root.resolve("write-link.txt");
            try {
                Files.createSymbolicLink(writeLink, protectedTarget);
                ExecutionException symlinkWrite =
                        assertThrows(ExecutionException.class, () ->
                                provider.writeText(
                                                writeLink.toAbsolutePath().toString(),
                                                "escaped")
                                        .get(5, TimeUnit.SECONDS));
                assertNotNull(symlinkWrite.getCause());
                assertEquals(
                        "safe",
                        Files.readString(protectedTarget, StandardCharsets.UTF_8));
            } catch (UnsupportedOperationException
                    | java.nio.file.FileSystemException unsupported) {
                // The deterministic bridge tests still cover write ownership.
            }

            Path link = root.resolve("link.txt");
            try {
                Files.createSymbolicLink(link, file);
                ExecutionException symlink =
                        assertThrows(ExecutionException.class, () ->
                                provider.readText(link.toAbsolutePath().toString())
                                        .get(5, TimeUnit.SECONDS));
                assertNotNull(symlink.getCause());
            } catch (UnsupportedOperationException
                    | java.nio.file.FileSystemException unsupported) {
                // The deterministic tests still cover the Java ownership path.
            }
        } finally {
            provider.close();
        }
    }
}
