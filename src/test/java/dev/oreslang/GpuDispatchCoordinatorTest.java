package dev.oreslang;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.DopKernelIR;
import dev.oreslang.runtime.accel.GpuBufferFence;
import dev.oreslang.runtime.accel.GpuDispatchCoordinator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

final class GpuDispatchCoordinatorTest {
    private static final GpuBufferFence.Identity ID =
            new GpuBufferFence.Identity("tenant", "test-device", 17);

    private static DopKernelIR.OpenClArtifact artifact() {
        var layout = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 7, 2, 4, 16, 1);
        return DopKernelIR.lowerOpenCl(
                new DopKernelIR.Kernel(layout, DopKernelIR.Element.F32,
                        DopKernelIR.Op.SCALE, 2f, 0f),
                new DopKernelIR.GpuTarget(64, 4096, 64, true));
    }

    private static final class DeferredProvider implements GpuDispatchCoordinator.Provider {
        CompletableFuture<Void> device = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        int launches;
        int releases;
        @Override public CompletionStage<Void> launch(
                DopKernelIR.OpenClArtifact kernel,
                GpuBufferFence.Identity id, GpuBufferFence.Ticket ticket) {
            ++launches;
            return device;
        }
        @Override public CompletionStage<Void> release(
                GpuBufferFence.Identity id, long bytes) {
            ++releases;
            return release;
        }
    }

    @Test void boundedCreditsAreHeldUntilProviderEventAndPhysicalRelease() {
        var provider = new DeferredProvider();
        long bytes = artifact().requiredBytes() * 2;
        var gpu = new GpuDispatchCoordinator(provider, new GpuDispatchCoordinator.Limits(1, bytes));
        var submission = gpu.submit(artifact(), ID);
        assertTrue(submission.get_ready().isDone());
        assertFalse(submission.get_done().isDone());
        assertEquals(bytes, gpu.reservedBytes());
        assertEquals(1, gpu.inFlightFrames());
        assertThrows(RejectedExecutionException.class, () -> gpu.submit(artifact(), ID));
        assertThrows(IllegalStateException.class, gpu::close);
        provider.device.complete(null);
        assertEquals(1, provider.releases);
        assertFalse(submission.get_done().isDone());
        assertEquals(bytes, gpu.reservedBytes());
        assertThrows(RejectedExecutionException.class, () -> gpu.submit(artifact(), ID));
        provider.release.complete(null);
        assertFalse(submission.get_done().join().cancelled());
        assertEquals(bytes, submission.get_done().join().byteReservation());
        assertEquals(0, gpu.reservedBytes());
        assertEquals(0, gpu.inFlightFrames());
        gpu.close();
        assertThrows(RejectedExecutionException.class, () -> gpu.submit(artifact(), ID));
    }

    @Test void cancellationCannotRecycleMemoryBeforeProviderFenceCompletes() {
        var provider = new DeferredProvider();
        var gpu = new GpuDispatchCoordinator(provider,
                new GpuDispatchCoordinator.Limits(1, 4096));
        var submission = gpu.submit(artifact(), ID);
        assertTrue(submission.cancel());
        assertFalse(submission.cancel());
        assertThrows(RejectedExecutionException.class, () -> gpu.submit(artifact(), ID));
        provider.device.complete(null);
        assertEquals(1, provider.releases);
        assertFalse(submission.get_done().isDone());
        assertTrue(gpu.reservedBytes() > 0);
        provider.release.complete(null);
        assertThrows(CancellationException.class, () -> submission.get_done().join());
        assertEquals(0, gpu.reservedBytes());
        gpu.close();
    }

    @Test void deviceFailureWaitsForReleaseAndSurfacesError() {
        var provider = new DeferredProvider();
        var gpu = new GpuDispatchCoordinator(provider,
                new GpuDispatchCoordinator.Limits(1, 4096));
        var submission = gpu.submit(artifact(), ID);
        provider.device.completeExceptionally(new IllegalStateException("device event failure"));
        assertFalse(submission.get_done().isDone());
        assertEquals(1, provider.releases);
        provider.release.complete(null);
        assertThrows(CompletionException.class, () -> submission.get_done().join());
        assertEquals(0, gpu.reservedBytes());
        gpu.close();
    }

    @Test void failedOrUncertainReleaseRetainsCreditsFailClosed() {
        var provider = new DeferredProvider();
        var gpu = new GpuDispatchCoordinator(provider,
                new GpuDispatchCoordinator.Limits(1, 4096));
        var submission = gpu.submit(artifact(), ID);
        provider.device.complete(null);
        provider.release.completeExceptionally(new IllegalStateException("device still allocated"));
        assertThrows(CompletionException.class, () -> submission.get_done().join());
        assertTrue(gpu.reservedBytes() > 0);
        assertThrows(IllegalStateException.class, gpu::close);
    }

    @Test void noHardwareProviderOrOversizedBytesCannotBypassAdmission() {
        var provider = new DeferredProvider();
        var gpu = new GpuDispatchCoordinator(provider,
                new GpuDispatchCoordinator.Limits(1, 1));
        assertThrows(RejectedExecutionException.class, () -> gpu.submit(artifact(), ID));
        assertEquals(0, provider.launches);
        gpu.close();
    }
}
