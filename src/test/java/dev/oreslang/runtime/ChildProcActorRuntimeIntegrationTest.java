package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-source integration gate: genuine ActorRuntime mailbox lifecycle and
 * actor execution-domain hook. This test must run with the full JVM project,
 * not the isolated host-only Truffle stub compilation.
 */
final class ChildProcActorRuntimeIntegrationTest {
    @Test
    void actorTerminationClosesItsRegisteredChildProcess() throws Exception {
        Path executable = Path.of("/usr/bin/sleep");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(executable));
        var spec = new ChildProcLaunchAdmission.Spec(executable.toString(),
                List.of("30"), Path.of("").toAbsolutePath().toString(), Map.of());
        CountDownLatch launched = new CountDownLatch(1);
        AtomicReference<ChildProcOwnedRuntime.Child> child = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime actors = new ActorRuntime();
             ActorChildProcessRegistry owners = new ActorChildProcessRegistry();
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            actors.setActorExitHook(owners::retire);
            var ref = actors.<String>spawnShared(() -> (message, context) -> {
                try {
                    Object domain = ActorRuntime.currentActorExecutionDomain();
                    assertNotNull(domain, "must execute within a genuine actor domain");
                    var hostAdmission = new ChildProcOwnedRuntime.Admission() {
                        @Override public void requireOwner() {
                            if (ActorRuntime.currentActorExecutionDomain() != domain) {
                                throw new SecurityException("actor domain mismatch");
                            }
                        }
                        @Override public ChildProcLaunchAdmission.Plan admit(
                                ChildProcLaunchAdmission.Spec request) throws IOException {
                            requireOwner();
                            return ChildProcLaunchAdmission.admit(
                                    request, (permission, resource) -> { });
                        }
                    };
                    ChildProcOwnedRuntime manager = new ChildProcOwnedRuntime(
                            hostAdmission, workers, 1, 1024);
                    owners.register(domain, manager);
                    manager.spawnAsync(spec).whenComplete((running, error) -> {
                        if (error != null) failure.set(error);
                        else child.set(running);
                        launched.countDown();
                    });
                } catch (Throwable error) {
                    failure.set(error);
                    launched.countDown();
                }
            });
            ref.send("start");
            assertTrue(launched.await(10, TimeUnit.SECONDS), "process launch never finished");
            assertNull(failure.get(), () -> "actor process startup failed: " + failure.get());
            assertNotNull(child.get());
            assertTrue(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive).orElse(false));

            ref.stop();
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            child.get().waitAsync().get(10, TimeUnit.SECONDS);
            assertFalse(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive).orElse(false),
                    "actor-exit retirement must stop the owned child");
        }
    }
}
