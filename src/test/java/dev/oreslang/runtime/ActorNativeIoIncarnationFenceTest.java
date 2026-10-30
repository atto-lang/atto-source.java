package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stale I/O worker authority may retire work already admitted, but cannot
 * initiate a new native operation after the exact actor incarnation stops.
 * This is a Java runtime test, NOT an operating-system sandbox proof.
 */
@Timeout(30)
final class ActorNativeIoIncarnationFenceTest {
    private static ActorRuntime runtime(RuntimePermissions ceiling) {
        return new ActorRuntime(
                IsolatePolicy.developer(),
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(),
                null, ceiling);
    }

    @Test
    void stoppedActorRejectsCapturedAuthorityWhileLiveSiblingRemainsUsable()
            throws Exception {
        var ceiling = RuntimePermissions.denyAll();
        var firstAuthority = new AtomicReference<ActorRuntime.NativeIoAuthority>();
        var siblingAuthority = new AtomicReference<ActorRuntime.NativeIoAuthority>();
        var firstReady = new CountDownLatch(1);
        var siblingReady = new CountDownLatch(1);
        try (var actors = runtime(ceiling)) {
            var first = actors.<String>spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> {
                        firstAuthority.set(ActorRuntime.captureNativeIoAuthority());
                        firstReady.countDown();
                    });
            var sibling = actors.<String>spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> {
                        siblingAuthority.set(ActorRuntime.captureNativeIoAuthority());
                        siblingReady.countDown();
                    });
            first.send("capture");
            sibling.send("capture");
            assertTrue(firstReady.await(5, TimeUnit.SECONDS));
            assertTrue(siblingReady.await(5, TimeUnit.SECONDS));
            assertNotNull(firstAuthority.get());
            assertNotNull(siblingAuthority.get());

            try (var before = firstAuthority.get().reserveIo("before STOP")) {
                assertNotNull(before);
            }
            actors.stop(first);
            assertThrows(SecurityException.class, () ->
                    firstAuthority.get().reserveIo("stale worker submit"));
            assertThrows(SecurityException.class, () ->
                    firstAuthority.get().requireActive("stale worker dispatch"));
            try (var live = siblingAuthority.get().reserveIo("sibling remains alive")) {
                assertNotNull(live);
            }
            actors.stop(sibling);
            assertThrows(SecurityException.class, () ->
                    siblingAuthority.get().reserveIo("sibling after STOP"));
        }
    }

    @Test
    void selfStopFencesAdmissionEvenBeforePhysicalTurnUnwinds()
            throws Exception {
        var ceiling = RuntimePermissions.denyAll();
        var actorRef = new AtomicReference<ActorRuntime.ActorRef<String>>();
        var terminalError = new AtomicReference<Throwable>();
        var checked = new CountDownLatch(1);
        try (var actors = runtime(ceiling)) {
            var actor = actors.<String>spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> {
                        try {
                            var captured = ActorRuntime.captureNativeIoAuthority();
                            try (var admitted = captured.reserveIo("inside live turn")) {
                                assertNotNull(admitted);
                            }
                            context.runtime().stop(actorRef.get());
                            assertThrows(SecurityException.class, () ->
                                    captured.reserveIo("after self STOP"));
                            assertThrows(SecurityException.class, () ->
                                    captured.requireActive("after self STOP"));
                        } catch (Throwable failure) {
                            terminalError.compareAndSet(null, failure);
                        } finally {
                            checked.countDown();
                        }
                    });
            actorRef.set(actor);
            actor.send("end");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(terminalError.get(), () -> String.valueOf(terminalError.get()));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void runtimeShutdownInvalidatesCapturedActorAuthority()
            throws Exception {
        var ceiling = RuntimePermissions.denyAll();
        var captured = new AtomicReference<ActorRuntime.NativeIoAuthority>();
        var ready = new CountDownLatch(1);
        var actors = runtime(ceiling);
        try {
            var actor = actors.<String>spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> {
                        captured.set(ActorRuntime.captureNativeIoAuthority());
                        ready.countDown();
                    });
            actor.send("capture");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            actors.close();
            assertThrows(SecurityException.class, () ->
                    captured.get().reserveIo("post-runtime-close"));
            assertThrows(SecurityException.class, () ->
                    captured.get().requireActive("post-runtime-close"));
        } finally {
            actors.close();
        }
    }
}
