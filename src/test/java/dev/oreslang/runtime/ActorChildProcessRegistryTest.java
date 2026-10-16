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
import static org.junit.jupiter.api.Assertions.*;

/** Tests context-owned actor domain retirement without Truffle API stubs. */
final class ActorChildProcessRegistryTest {
    private static ChildProcOwnedRuntime manager() {
        return new ChildProcOwnedRuntime(new ChildProcOwnedRuntime.Admission() {
            @Override public void requireOwner() { }
            @Override public ChildProcLaunchAdmission.Plan admit(
                    ChildProcLaunchAdmission.Spec spec) throws IOException {
                return ChildProcLaunchAdmission.admit(spec, (permission, resource) -> { });
            }
        }, Runnable::run, 1, 128);
    }

    private static void assertClosed(ChildProcOwnedRuntime processRegistry) {
        Path executable = Path.of("/usr/bin/printf");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(executable));
        var spec = new ChildProcLaunchAdmission.Spec(executable.toString(), List.of("x"),
                Path.of("").toAbsolutePath().toString(), Map.of());
        assertThrows(IllegalStateException.class, () -> processRegistry.spawnAsync(spec));
    }

    @Test
    void retiringActorDomainClosesOwnedProcessRegistry() {
        Object domain = new Object();
        try (ActorChildProcessRegistry table = new ActorChildProcessRegistry();
             ChildProcOwnedRuntime registry = manager()) {
            table.register(domain, registry);
            table.retire(domain);
            assertClosed(registry);
            table.retire(domain);
        }
    }

    @Test
    void duplicateActorRegistryCannotBeInstalled() {
        Object domain = new Object();
        try (ActorChildProcessRegistry table = new ActorChildProcessRegistry();
             ChildProcOwnedRuntime first = manager();
             ChildProcOwnedRuntime second = manager()) {
            table.register(domain, first);
            assertThrows(IllegalStateException.class, () -> table.register(domain, second));
            table.retire(domain);
            assertClosed(first);
        }
    }

    @Test
    void contextCloseClosesAllDomainsAndFencesFutureRegistration() {
        try (ActorChildProcessRegistry table = new ActorChildProcessRegistry();
             ChildProcOwnedRuntime first = manager();
             ChildProcOwnedRuntime second = manager();
             ChildProcOwnedRuntime late = manager()) {
            table.register(new Object(), first);
            table.register(new Object(), second);
            table.close();
            assertClosed(first);
            assertClosed(second);
            assertThrows(IllegalStateException.class, () -> table.register(new Object(), late));
        }
    }

    @Test
    void racingContextCloseAndActorRegistrationCannotLeakRegistry() throws Exception {
        for (int iteration = 0; iteration < 50; iteration++) {
            try (ActorChildProcessRegistry table = new ActorChildProcessRegistry();
                 ChildProcOwnedRuntime registry = manager();
                 ExecutorService threads = Executors.newFixedThreadPool(2)) {
                CountDownLatch starting = new CountDownLatch(1);
                var register = threads.submit(() -> {
                    try {
                        starting.await();
                        table.register(new Object(), registry);
                    } catch (IllegalStateException expectedShutdown) {
                        // Mirror the production forCurrentActor adapter:
                        // rejected registration must close its unregistered
                        // process registry before returning to the actor.
                        registry.close();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
                var shutdown = threads.submit(() -> {
                    try { starting.await(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    table.close();
                });
                starting.countDown();
                register.get(3, TimeUnit.SECONDS);
                shutdown.get(3, TimeUnit.SECONDS);
                assertClosed(registry);
                assertThrows(IllegalStateException.class,
                        () -> table.register(new Object(), registry));
            }
        }
    }
}
