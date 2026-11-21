package dev.oreslang;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.DopKernelIR;
import dev.oreslang.runtime.accel.GpuActorCompletionBridge;
import dev.oreslang.runtime.accel.GpuBufferFence;
import dev.oreslang.runtime.accel.GpuDispatchCoordinator;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static org.junit.jupiter.api.Assertions.*;

final class GpuActorCompletionBridgeTest {
    private static final GpuBufferFence.Identity ID =
            new GpuBufferFence.Identity("tenant", "gpu", 2);

    private static DopKernelIR.OpenClArtifact artifact() {
        var layout = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.SOA, 2, 1, 4, 8, 1);
        return DopKernelIR.lowerOpenCl(
                new DopKernelIR.Kernel(layout, DopKernelIR.Element.F32,
                        DopKernelIR.Op.BIAS, 1, 1),
                new DopKernelIR.GpuTarget(64, 4096, 100, true));
    }
    private static final class Controlled implements GpuDispatchCoordinator.Provider {
        CompletableFuture<Void> event = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        @Override public CompletionStage<Void> launch(DopKernelIR.OpenClArtifact a,
                GpuBufferFence.Identity id, GpuBufferFence.Ticket ticket) { return event; }
        @Override public CompletionStage<Void> release(GpuBufferFence.Identity id, long bytes) {
            return release;
        }
    }

    @Test void providerCallbackOnlyEnqueuesControlAndNeverRunsActorUserCode() {
        var provider = new Controlled();
        var coordinator = new GpuDispatchCoordinator(provider,
                new GpuDispatchCoordinator.Limits(1, 4096));
        var submission = coordinator.submit(artifact(), ID);
        ArrayDeque<Runnable> reservedControl = new ArrayDeque<>();
        var actor = GpuActorCompletionBridge.completionOnActorLane(
                submission, reservedControl::addLast);
        int[] actorUserTurns = {0};
        actor.value().thenAccept(outcome -> actorUserTurns[0]++);
        provider.event.complete(null);
        provider.release.complete(null);
        assertEquals(0, actorUserTurns[0]);
        assertEquals(1, reservedControl.size());
        assertFalse(actor.value().toCompletableFuture().isDone());
        reservedControl.removeFirst().run(); // admitted actor control turn
        assertEquals(1, actorUserTurns[0]);
        assertTrue(actor.value().toCompletableFuture().isDone());
        assertNull(actor.routingFailure().get());
        coordinator.close();
    }

    @Test void rejectedControlAdmissionNeverInvokesUserContinuationsOnDeviceCarrier() {
        var provider = new Controlled();
        var coordinator = new GpuDispatchCoordinator(provider,
                new GpuDispatchCoordinator.Limits(1, 4096));
        var submission = coordinator.submit(artifact(), ID);
        var actor = GpuActorCompletionBridge.completionOnActorLane(
                submission, task -> { throw new IllegalStateException("actor control slot unavailable"); });
        int[] userCalled = {0};
        actor.value().thenRun(() -> userCalled[0]++);
        provider.event.complete(null);
        provider.release.complete(null);
        assertEquals(0, userCalled[0]);
        assertFalse(actor.value().toCompletableFuture().isDone());
        assertNotNull(actor.routingFailure().get());
        coordinator.close();
    }
}
