package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only composition tests. They prove route/lease bookkeeping, not live
 * NativeHttp sockets, Graal source execution, or executable image unloading.
 */
final class HttpActorGenerationCoordinatorV1Test {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }

    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(
                sha(code), sha('1'), sha('2'), sha('3'));
    }

    private static ActorRoutePublicationV1.StateSnapshot state(int value) {
        return new ActorRoutePublicationV1.StateSnapshot(
                sha('1'), new byte[] {(byte) value});
    }

    private static Fixture fixture(int maxImages) {
        var gate = new ActorUpgradeEpochGate(77, image('a'));
        var mailbox = new LogicalActorProxyMailboxV1(8, 64, 256);
        var routes = new ActorRoutePublicationV1<>(
                gate, mailbox, state(1), "handler-v1");
        var coordinator = new HttpActorGenerationCoordinatorV1<>(
                routes, maxImages, 1024, 100, 16, 32);
        return new Fixture(gate, mailbox, routes, coordinator);
    }

    private record Fixture(
            ActorUpgradeEpochGate gate,
            LogicalActorProxyMailboxV1 mailbox,
            ActorRoutePublicationV1<String> routes,
            HttpActorGenerationCoordinatorV1<String> coordinator)
            implements AutoCloseable {
        @Override public void close() {
            coordinator.close();
            routes.close();
        }
    }

    @Test void oldHttpExchangeDoesNotBlockCompatibleRouteCutover() {
        try (var f = fixture(3)) {
            var oldRequest = f.coordinator.admit();
            assertEquals(1, oldRequest.routeEpoch());
            assertEquals("handler-v1", oldRequest.physicalRoute());

            try (var stage = f.coordinator.stage(
                    image('b'), state(2), "handler-v2", 100)) {
                assertTrue(stage.publish(),
                        "HTTP exchange lifetime is separate from actor mailbox/Task preflight");
            }

            var newRequest = f.coordinator.admit();
            assertEquals(2, newRequest.routeEpoch());
            assertEquals("handler-v2", newRequest.physicalRoute());

            var status = f.coordinator.status();
            assertEquals(2, status.activeRouteEpoch());
            assertEquals(HotCodeGenerationRetentionV1.Phase.DRAINING,
                    status.generations().get(1L).phase());
            assertFalse(status.generations().get(1L).http().readyToRetire());

            oldRequest.lifetime().handlerQuiescent();
            oldRequest.lifetime().transportQuiescent();
            assertTrue(f.coordinator.status().generations()
                    .get(1L).http().readyToRetire());
            f.coordinator.collectRetired(1, true);
            assertFalse(f.coordinator.status().generations().containsKey(1L));

            newRequest.lifetime().handlerQuiescent();
            newRequest.lifetime().transportQuiescent();
        }
    }

    @Test void pinnedOldImageMakesThirdDeployFailWithoutPurgingAnything() {
        try (var f = fixture(2)) {
            var v1 = f.coordinator.admit();
            try (var toV2 = f.coordinator.stage(
                    image('b'), state(2), "handler-v2", 100)) {
                assertTrue(toV2.publish());
            }
            assertEquals(2, f.coordinator.admit().routeEpoch());

            assertThrows(IllegalStateException.class, () ->
                    f.coordinator.stage(image('c'), state(3), "handler-v3", 100));
            assertEquals(2, f.coordinator.status().activeRouteEpoch());
            assertFalse(f.routes.current().taskAdmission().frozen(),
                    "failed image-budget admission must roll back the V2 actor freeze");
            assertEquals("handler-v2", f.coordinator.admit().physicalRoute());

            v1.lifetime().handlerQuiescent();
            v1.lifetime().transportQuiescent();
            f.coordinator.collectRetired(1, true);

            try (var toV3 = f.coordinator.stage(
                    image('c'), state(3), "handler-v3", 100)) {
                assertTrue(toV3.publish());
            }
            assertEquals(3, f.coordinator.status().activeRouteEpoch());
        }
    }

    @Test void actorTaskPinCanDelayPublicationWhileHttpAdmissionsStayOnOldRoute() {
        try (var f = fixture(3)) {
            var task = f.routes.current().taskAdmission().pin(
                    ActorUpgradeEpochGate.LeaseKind.SUSPENDED_TASK);
            try (var stage = f.coordinator.stage(
                    image('b'), state(2), "handler-v2", 100)) {
                var duringStage = f.coordinator.admit();
                assertEquals(1, duringStage.routeEpoch(),
                        "staging is not publication; old route remains authoritative");
                assertFalse(stage.publish());

                task.close();
                assertTrue(stage.publish());
                assertEquals(2, f.coordinator.admit().routeEpoch());

                duringStage.lifetime().handlerQuiescent();
                duringStage.lifetime().transportQuiescent();
            } finally {
                task.close();
            }
        }
    }

    @Test void abortedImageRevisionIsNeverReusedButRouteEpochCanRetry() {
        try (var f = fixture(3)) {
            var aborted = f.coordinator.stage(
                    image('b'), state(2), "handler-bad", 100);
            assertEquals(2, aborted.candidateRouteEpoch());
            aborted.close();
            assertEquals(1, f.coordinator.status().activeRouteEpoch());
            assertEquals(2, f.coordinator.status().retention().retained().size(),
                    "aborted image remains charged until loader cleanup");
            assertThrows(IllegalStateException.class,
                    () -> aborted.acknowledgeAbortedPhysicalCleanup(false));
            aborted.acknowledgeAbortedPhysicalCleanup(true);

            try (var retry = f.coordinator.stage(
                    image('c'), state(3), "handler-good", 100)) {
                assertEquals(2, retry.candidateRouteEpoch(),
                        "no publication occurred, so route epoch 2 is still next");
                var staged = f.coordinator.status().generations().get(2L);
                assertEquals(3, staged.imageGeneration(),
                        "image revision 2 was consumed by the aborted candidate");
                assertTrue(retry.publish());
            }
            assertEquals(2, f.coordinator.status().activeRouteEpoch());
        }
    }

    @Test void unboundAdmissionCanRollbackWithoutFakingPhysicalCompletion() {
        try (var f = fixture(2)) {
            var admission = f.coordinator.admit();
            admission.lifetime().abandonBeforeUse();
            admission.lifetime().abandonBeforeUse();
            assertTrue(admission.lifetime().finished());
            assertEquals(0, f.coordinator.status().generations()
                    .get(1L).http().exchanges());

            var used = f.coordinator.admit();
            var pin = used.lifetime().pin(
                    HttpGenerationDrainV1.ResourceKind.HTTP_STREAM);
            assertThrows(IllegalStateException.class,
                    used.lifetime()::abandonBeforeUse);
            pin.close();
            used.lifetime().handlerQuiescent();
            used.lifetime().transportQuiescent();
        }
    }
}
