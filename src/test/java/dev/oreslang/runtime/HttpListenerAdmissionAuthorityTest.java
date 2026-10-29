package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The HTTP server-listen boundary must reject actors and actor-owned native workers. */
@Timeout(30)
final class HttpListenerAdmissionAuthorityTest {
    @Test
    void plainSupervisorThreadMayPassListenerAuthorityGate() {
        assertDoesNotThrow(NativeHttp::requireSupervisorListenerAdmission);
    }

    @Test
    void actorAndItsNativeWorkerCannotBecomeListenerSupervisor() throws Exception {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK);
        RuntimePermissions ceiling = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET);
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicReference<OresFuture<Boolean>> worker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime(
                policy, ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
             AsyncNativeIo io = new AsyncNativeIo(2)) {
            var actor = runtime.<String>spawnSharedTrusted(policy,
                    factory -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class,
                                    NativeHttp::requireSupervisorListenerAdmission,
                                    "actor turn cannot open an HTTP listener");
                            worker.set(io.submit("http-listener-authority", () -> {
                                assertNotNull(ActorRuntime.currentNativeIoAuthority(),
                                        "worker must be carrying admitted actor authority");
                                assertThrows(SecurityException.class,
                                        NativeHttp::requireSupervisorListenerAdmission,
                                        "actor-owned native worker is not a supervisor");
                                return true;
                            }));
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            submitted.countDown();
                        }
                    });
            actor.send("attempt");
            assertTrue(submitted.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertNotNull(worker.get());
            assertTrue(worker.get().get(5, TimeUnit.SECONDS));
            actor.stop();
        }
    }
}
