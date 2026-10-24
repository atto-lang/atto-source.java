package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime authority/retirement checks. Source-level `.ores` tests live in
 * SourceActorWorkerAcceptTest and are separate from this host-level boundary.
 */
@EnabledOnOs(OS.LINUX)
final class ActorOwnedNioListenerLifecycleTest {
    @Test @Timeout(20)
    void actorStopRevokesItsPendingAcceptAndJoinsListenerDrainBeforeDone()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            CountDownLatch bound = new CountDownLatch(1);
            AtomicReference<VmNioTcpListener> listenerRef = new AtomicReference<>();
            AtomicReference<OresFuture<VmNioTcpListener.Connection>> acceptRef =
                    new AtomicReference<>();
            var worker = runtime.<Integer>spawnShared(factory ->
                    (message, context) -> {
                        var listener = VmNioTcpListener.loopbackReusePort(
                                vm.nioReadiness(), 0, 32, 8, 16);
                        runtime.registerCurrentActorNioListener(listener);
                        listenerRef.set(listener);
                        acceptRef.set(listener.acceptAsync());
                        bound.countDown();
                    });
            worker.send(1);
            assertTrue(bound.await(3, TimeUnit.SECONDS));
            assertNotNull(listenerRef.get().actorOwnerDomain());
            assertThrows(SecurityException.class, () ->
                    runtime.requireCurrentActorNioOwner(listenerRef.get().actorOwnerDomain()),
                    "ROOT cannot borrow an actor-owned listener's authority");
            worker.stop();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(listenerRef.get().isTerminated(),
                    "actor DONE must join the listener and its outstanding accepts");
            assertTrue(acceptRef.get().isDone());
            assertEquals(0, vm.nioReadiness().activeAcceptLeases());
        } finally {
            vm.close();
        }
    }

    @Test @Timeout(20)
    void anotherActorCannotClaimListenerFromForeignIncarnation() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            AtomicReference<Object> ownerDomain = new AtomicReference<>();
            CountDownLatch ownerBound = new CountDownLatch(1);
            var owner = runtime.<Integer>spawnShared(factory ->
                    (message, ctx) -> {
                        var listener = VmNioTcpListener.loopbackReusePort(
                                vm.nioReadiness(), 0, 8, 1, 2);
                        runtime.registerCurrentActorNioListener(listener);
                        ownerDomain.set(listener.actorOwnerDomain());
                        ownerBound.countDown();
                    });
            owner.send(1);
            assertTrue(ownerBound.await(3, TimeUnit.SECONDS));

            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            var foreign = runtime.<Integer>spawnShared(factory ->
                    (message, ctx) -> {
                        try {
                            assertThrows(SecurityException.class,
                                    () -> runtime.requireCurrentActorNioOwner(ownerDomain.get()));
                        } catch (Throwable failure) {
                            unexpected.set(failure);
                        } finally {
                            attempted.countDown();
                            ctx.self().stop();
                        }
                    });
            foreign.send(1);
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            assertNull(unexpected.get());
            owner.stop();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(foreign.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            vm.close();
        }
    }
}
