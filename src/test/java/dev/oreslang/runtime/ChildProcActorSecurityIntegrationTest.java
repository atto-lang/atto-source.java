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
 * Runs against the actual ActorRuntime mailbox and actor-exit lifecycle.
 * Unlike the host-only unit suite, actor context and owner identity here
 * originate from real scheduler turns, not a synthetic API fixture.
 */
final class ChildProcActorSecurityIntegrationTest {
    private static ChildProcLaunchAdmission.Spec sleepSpec() {
        Path executable = Path.of("/usr/bin/sleep");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(executable));
        return new ChildProcLaunchAdmission.Spec(
                executable.toString(), List.of("30"),
                Path.of("").toAbsolutePath().toString(), Map.of());
    }

    private static ChildProcOwnedRuntime ownedManager(
            ActorRuntime runtime, ActorChildProcessRegistry owners, ExecutorService workers) {
        ActorRuntime.ActorId actorId = ActorRuntime.currentActorId().orElseThrow();
        Object domain = ActorRuntime.currentActorExecutionDomain();
        assertNotNull(domain);
        var admission = new ChildProcOwnedRuntime.Admission() {
            @Override public void requireOwner() {
                if (ActorRuntime.currentActorRuntime() != runtime
                        || ActorRuntime.currentActorId().filter(actorId::equals).isEmpty()) {
                    throw new SecurityException("process handle belongs to another actor");
                }
            }

            @Override public ChildProcLaunchAdmission.Plan admit(
                    ChildProcLaunchAdmission.Spec spec) throws IOException {
                requireOwner();
                return ChildProcLaunchAdmission.admit(spec, (permission, resource) -> { });
            }
        };
        ChildProcOwnedRuntime manager = new ChildProcOwnedRuntime(admission, workers, 1, 1024);
        try {
            owners.register(domain, manager);
        } catch (RuntimeException rejected) {
            manager.close();
            throw rejected;
        }
        return manager;
    }

    @Test
    void unrelatedActorCannotSpawnOrSignalOwnedChild() throws Exception {
        var spec = sleepSpec();
        try (ActorRuntime runtime = new ActorRuntime();
             ActorChildProcessRegistry owners = new ActorChildProcessRegistry();
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            runtime.setActorExitHook(owners::retire);
            AtomicReference<ChildProcOwnedRuntime> manager = new AtomicReference<>();
            AtomicReference<ChildProcOwnedRuntime.Child> child = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(1);
            var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    var owned = ownedManager(runtime, owners, workers);
                    manager.set(owned);
                    owned.spawnAsync(spec).whenComplete((running, failure) -> {
                        if (failure != null) error.set(failure);
                        else child.set(running);
                        ready.countDown();
                    });
                } catch (Throwable failed) {
                    error.set(failed);
                    ready.countDown();
                }
            });
            try {
                parent.send("start");
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                assertNull(error.get(), () -> "owner failed to launch: " + error.get());
                assertNotNull(child.get());
                assertTrue(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive).orElse(false));

                CountDownLatch checked = new CountDownLatch(1);
                var other = runtime.<String>spawnShared(() -> (message, context) -> {
                    try {
                        assertThrows(SecurityException.class, () -> manager.get().spawnAsync(spec));
                        assertThrows(SecurityException.class, child.get()::kill);
                    } catch (Throwable violation) {
                        error.set(violation);
                    } finally {
                        checked.countDown();
                        context.self().stop();
                    }
                });
                other.send("probe");
                assertTrue(checked.await(10, TimeUnit.SECONDS));
                assertTrue(other.awaitTermination(10, TimeUnit.SECONDS));
                assertNull(error.get(), () -> "cross-actor permission failure: " + error.get());
                assertTrue(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive).orElse(false),
                        "unauthorized actor must not terminate the child");
            } finally {
                parent.stop();
                assertTrue(parent.awaitTermination(10, TimeUnit.SECONDS));
                if (child.get() != null) {
                    child.get().waitAsync().get(10, TimeUnit.SECONDS);
                    assertFalse(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive).orElse(false));
                }
            }
        }
    }

    @Test
    void actorFailureAutomaticallyRetiresAndReapsItsChild() throws Exception {
        var spec = sleepSpec();
        try (ActorRuntime runtime = new ActorRuntime();
             ActorChildProcessRegistry owners = new ActorChildProcessRegistry();
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            runtime.setActorExitHook(owners::retire);
            AtomicReference<ChildProcOwnedRuntime.Child> child = new AtomicReference<>();
            AtomicReference<Throwable> launchError = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(1);
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                if (message.equals("fail")) throw new IllegalStateException("injected actor failure");
                try {
                    var manager = ownedManager(runtime, owners, workers);
                    manager.spawnAsync(spec).whenComplete((running, failure) -> {
                        if (failure != null) launchError.set(failure);
                        else child.set(running);
                        ready.countDown();
                    });
                } catch (Throwable failure) {
                    launchError.set(failure);
                    ready.countDown();
                }
            });
            try {
                ref.send("start");
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                assertNull(launchError.get(), () -> "owner launch failed: " + launchError.get());
                assertNotNull(child.get());
                assertTrue(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive).orElse(false));
                ref.send("fail");
                assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
                assertTrue(ref.failure().isPresent(), "actor failure must be recorded");
                child.get().waitAsync().get(10, TimeUnit.SECONDS);
                assertFalse(ProcessHandle.of(child.get().pid()).map(ProcessHandle::isAlive).orElse(false),
                        "actor panic/failure must retire the owned child");
            } finally {
                ref.stop();
            }
        }
    }
}
