package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class HttpActorTransportOwnershipTest {

    @Test
    void oneHttpLeaseCannotHaveTwoControllingActors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RecordingResponse response = new RecordingResponse();
            var lease = runtime.leaseHttpTransport(
                    null,
                    response,
                    ActorRuntime.HttpLeaseExitPolicy.RETURN_TO_SUPERVISOR);

            var first = runtime.<String>spawnPrivateWithHttp(
                    IsolatePolicy.developer(),
                    lease,
                    ignored -> (message, turn) -> turn.self().stop());

            assertEquals(first.id(), lease.actorOwner().orElseThrow());
            assertFalse(lease.ownedBySupervisor());

            IllegalStateException alreadyOwned = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.<String>spawnPrivateWithHttp(
                            IsolatePolicy.developer(),
                            lease,
                            ignored -> (message, turn) -> turn.self().stop()));
            assertTrue(alreadyOwned.getMessage().contains("already controlled"));

            first.send("finish");
            assertTrue(first.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(first.failure().isEmpty());
            assertTrue(lease.ownedBySupervisor());
            assertTrue(lease.actorOwner().isEmpty());
            assertFalse(lease.closed());
        }
    }

    @Test
    void trustedLeaseReturnInvalidatesOldGenerationAndCanBeReassigned() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RecordingResponse response = new RecordingResponse();
            var lease = runtime.leaseHttpTransport(
                    null,
                    response,
                    ActorRuntime.HttpLeaseExitPolicy.RETURN_TO_SUPERVISOR);

            long initialGeneration = lease.generation();
            var first = runtime.<String>spawnPrivateWithHttp(
                    IsolatePolicy.developer(),
                    lease,
                    ignored -> (message, turn) -> {
                        turn.httpResponse().orElseThrow().write(ByteBuffer.wrap(new byte[]{1}));
                        turn.self().stop();
                    });
            long firstGeneration = lease.generation();
            assertTrue(firstGeneration > initialGeneration);

            first.send("one");
            assertTrue(first.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(lease.ownedBySupervisor());
            long returnedGeneration = lease.generation();
            assertTrue(returnedGeneration > firstGeneration);

            var second = runtime.<String>spawnPrivateWithHttp(
                    IsolatePolicy.developer(),
                    lease,
                    ignored -> (message, turn) -> {
                        turn.httpResponse().orElseThrow().write(ByteBuffer.wrap(new byte[]{2}));
                        turn.self().stop();
                    });
            long secondGeneration = lease.generation();
            assertTrue(secondGeneration > returnedGeneration);
            assertEquals(second.id(), lease.actorOwner().orElseThrow());

            second.send("two");
            assertTrue(second.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(second.failure().isEmpty());
            assertTrue(lease.ownedBySupervisor());
            assertEquals(2, response.bytes.get());
        }
    }

    @Test
    void httpLeaseCannotCrossActorRuntimeBoundaries() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var lease = runtimeA.leaseHttpTransport(null, new RecordingResponse());

            SecurityException rejected = assertThrows(
                    SecurityException.class,
                    () -> runtimeB.<String>spawnPrivateWithHttp(
                            IsolatePolicy.developer(),
                            lease,
                            ignored -> (message, turn) -> turn.self().stop()));

            assertTrue(rejected.getMessage().contains("different ActorRuntime"));
            assertTrue(lease.ownedBySupervisor());
            assertFalse(lease.closed());
        }
    }

    @Test
    void compatibilitySpawnRollbackClosesNewLease() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RecordingResponse response = new RecordingResponse();
            Object capturedHostState = new Object();

            assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnUntrusted(
                            IsolatePolicy.untrustedActor(),
                            ActorRuntime.UntrustedActorLimits.defaults(),
                            null,
                            response,
                            ignored -> {
                                capturedHostState.hashCode();
                                return (message, turn) -> turn.self().stop();
                            }));

            assertEquals(1, response.closeCalls.get());
        }
    }

    @Test
    void untrustedActorCannotReceiveReusableHttpLease() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var lease = runtime.leaseHttpTransport(
                    null,
                    new RecordingResponse(),
                    ActorRuntime.HttpLeaseExitPolicy.RETURN_TO_SUPERVISOR);

            SecurityException rejected = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnUntrusted(
                            IsolatePolicy.untrustedActor(),
                            ActorRuntime.UntrustedActorLimits.defaults(),
                            lease,
                            ignored -> (message, turn) -> turn.self().stop()));

            assertTrue(rejected.getMessage().contains("CLOSE-on-exit"));
            assertTrue(lease.ownedBySupervisor());
            assertFalse(lease.closed());
        }
    }

    @Test
    void untrustedActorExitClosesItsTransportLease() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RecordingResponse response = new RecordingResponse();
            var lease = runtime.leaseHttpTransport(null, response);

            var sandbox = runtime.<String>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    ActorRuntime.UntrustedActorLimits.defaults(),
                    lease,
                    ignored -> (message, turn) -> turn.self().stop());

            sandbox.send("finish");
            assertTrue(sandbox.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(sandbox.failure().isEmpty());
            assertTrue(lease.closed());
            assertTrue(lease.actorOwner().isEmpty());
            assertEquals(1, response.closeCalls.get());
        }
    }

    @Test
    void httpLeaseCannotCrossGenericActorTransport() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var lease = runtime.leaseHttpTransport(null, new RecordingResponse());

            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(lease));

            assertTrue(rejected.getMessage().contains("live actor/shared capabilities"));
        }
    }

    private static final class RecordingResponse
            implements ActorRuntime.HttpResponseTransport {
        private final AtomicInteger bytes = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int write(ByteBuffer source) {
            int count = source.remaining();
            source.position(source.limit());
            bytes.addAndGet(count);
            return count;
        }

        @Override
        public void closeTransport(Throwable cause) {
            closeCalls.incrementAndGet();
        }
    }
}
