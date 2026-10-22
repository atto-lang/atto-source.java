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
        boolean accept = true;
        int closes;

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
        public boolean cancel(long handle, long id) {
            assertEquals(77L, handle);
            cancels.add(id);
            return true;
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

        assertTrue(future.cancel(false));
        assertTrue(future.isCancelled());
        assertEquals(List.of(id), bridge.cancels);
        assertEquals(1, provider.pendingCount(),
                "native ownership remains until terminal completion");

        provider.nativeReadCompleted(
                id, "late".getBytes(StandardCharsets.UTF_8), 0);
        assertEquals(0, provider.pendingCount());
        assertTrue(future.isCancelled());

        provider.nativeReadCompleted(
                id, "duplicate".getBytes(StandardCharsets.UTF_8), 0);
        assertEquals(0, provider.pendingCount());
        assertTrue(future.isCancelled());
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
        ExecutionException failure =
                assertThrows(ExecutionException.class, future::get);
        assertTrue(failure.getCause().getMessage().contains("submission was rejected"));
        provider.close();
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
    void unsupportedProviderFailsClosedWithoutWorkerFallback() {
        StrictFileIoProvider provider = StrictFileIoProvider.Unsupported.INSTANCE;
        assertFalse(provider.supported());
        OresFuture<String> future = provider.readText("/tmp/unsupported");
        ExecutionException failure =
                assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
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
