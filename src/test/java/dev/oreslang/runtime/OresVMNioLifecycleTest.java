package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VM owns the readiness reactor independently of actor/CONTROL carriers.
 * This does not yet route the older per-listener TCP transport through it.
 */
final class OresVMNioLifecycleTest {
    @Test void controlCarrierBoundsRejectInvalidBootstrapPolicies() {
        assertDoesNotThrow(() -> OresVM.validateControlBounds(0, 20));
        assertDoesNotThrow(() -> OresVM.validateControlBounds(1, 20));
        assertDoesNotThrow(() -> OresVM.validateControlBounds(20, 20));
        for (int[] limits : new int[][] {
                {-1, 20}, {0, 0}, {2, 1}, {0, 21}, {21, 21}, {4, 3}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresVM.validateControlBounds(limits[0], limits[1]));
        }
    }

    @Test void nestedNioWorkerScopeCannotBypassSelfJoinTeardownGuard() {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            assertFalse(OresVM.onNioWorker());
            OresVM.runAsNioWorker(() -> {
                assertTrue(OresVM.onNioWorker());
                OresVM.runAsNioWorker(() -> assertTrue(OresVM.onNioWorker()));
                assertTrue(OresVM.onNioWorker(),
                        "nested worker scope must preserve outer teardown guard");
                assertThrows(IllegalStateException.class, vm::close,
                        "nested callback must not synchronously join its own worker");
            });
            assertFalse(OresVM.onNioWorker(),
                    "external host must not inherit the worker marker");
        } finally {
            vm.close();
        }
    }

    @Test void fivePendingIoWatchesShareOneVmReactor() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var pipes = new ArrayList<Pipe>();
        try {
            assertEquals(0, vm.nioReactorCount(),
                    "cold OresVM must not create a readiness thread");
            assertEquals(0, vm.controlCarrierCount(),
                    "throughput cold start must not prestart CONTROL carriers");
            assertEquals(0, vm.nioReadiness().completionCarrierCount(),
                    "completion isolation is lazy and must cost zero cold-start threads");
            assertFalse(vm.nioReadiness().usesVmCompletionCarriers(),
                    "arbitrary NIO Future subscribers must not consume CONTROL carriers");
            assertEquals(0, vm.ownedRuntimeThreadCount(),
                    "normal OresVM cold start owns zero runtime threads");
            var nio = vm.nioReadiness();
            var pending = new ArrayList<OresFuture<Integer>>();
            for (int i = 0; i < 5; i++) {
                var pipe = Pipe.open();
                pipes.add(pipe);
                pipe.source().configureBlocking(false);
                pipe.sink().configureBlocking(false);
                pending.add(nio.watch(pipe.source(), SelectionKey.OP_READ));
            }
            assertEquals(5, nio.pendingCount());
            assertEquals(1, nio.reactorCount(),
                    "first readiness demand starts exactly one reactor; five pending I/O calls do not justify five threads");
            assertEquals(1, vm.ownedRuntimeThreadCount(),
                    "pending watches need one reactor and no other VM thread");
            assertEquals(0, vm.controlCarrierCount(),
                    "idle I/O readiness must not create CONTROL carriers");
            assertEquals(0, nio.completionCarrierCount(),
                    "pending I/O alone must not create completion carriers");
            for (var pipe : pipes) {
                assertEquals(1, pipe.sink().write(ByteBuffer.wrap(new byte[]{42})));
            }
            for (var future : pending) {
                assertTrue((future.get(3, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
            }
            assertEquals(0, nio.pendingCount());
        } finally {
            vm.close();
            for (var pipe : pipes) {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }

    @Test void boundVmNotificationsUseActorCarriersAndRetireColdBootstrapHelper()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var actors = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 16, 64),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable());
        var pipe = Pipe.open();
        try {
            pipe.source().configureBlocking(false);
            actors.bindNioCompletionCapacityHook(vm.nioReadiness()::retryStagedNotifications);
            vm.nioReadiness().bindVmCompletionExecutor(
                    actors::executeHostIoCompletion, true);
            assertTrue(vm.nioReadiness().usesVmCompletionCarriers());
            assertEquals(0, vm.nioReadiness().completionCarrierCount());
            assertEquals(0, actors.startedActorCarrierCount());

            var completedOnActorCarrier = new java.util.concurrent.CountDownLatch(1);
            var readiness = vm.nioReadiness().watch(pipe.source(), SelectionKey.OP_READ);
            readiness.whenCompleteRuntime((events, failure) -> {
                if (failure == null && !OresVM.onControlCarrier()
                        && OresVM.onNioWorker() && !ActorRuntime.inActorExecution())
                    completedOnActorCarrier.countDown();
            });
            pipe.sink().write(ByteBuffer.wrap(new byte[] {42}));
            assertTrue(completedOnActorCarrier.await(3, TimeUnit.SECONDS),
                    "host-only notification must execute on actor-owned runtime carriers");
            assertEquals(0, vm.controlCarrierCount());
            assertTrue(actors.startedActorCarrierCount() >= 1);
            long helperDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (vm.nioReadiness().completionCarrierCount() != 0
                    && System.nanoTime() < helperDeadline)
                Thread.sleep(1);
            assertEquals(0, vm.nioReadiness().completionCarrierCount(),
                    "one-shot cold-executor bootstrap helper must retire after handoff");

            // This ordering is mandatory in OresContext.close(): the actor
            // domain must remain alive until all NIO notifications drain.
            vm.nioReadiness().close();
            assertTrue(vm.nioReadiness().awaitStopped(3, TimeUnit.SECONDS));
        } finally {
            pipe.source().close();
            pipe.sink().close();
            actors.close();
            vm.close();
        }
    }

    @Test void nonAffineJvmNioNotificationsReuseWarmCarrierWithoutExpansion()
            throws Exception {
        String previousAffinity = System.getProperty("ores.actor.affinity");
        String previousBackend = System.getProperty("ores.runtime.carriers");
        System.setProperty("ores.actor.affinity", "none");
        System.setProperty("ores.runtime.carriers", "java");
        try {
            OresVM vm = OresVM.create(Runnable::run);
            ActorRuntime actors = null;
            var first = Pipe.open();
            var second = Pipe.open();
            var third = Pipe.open();
            var release = new java.util.concurrent.CountDownLatch(1);
            try {
                actors = new ActorRuntime(IsolatePolicy.developer(),
                        new ActorRuntime.DispatcherConfig(1, 1, 16, 64),
                        ActorRuntime.TurnExecutor.direct(),
                        ActorRuntime.ControlDispatcher.unavailable());
                actors.bindNioCompletionCapacityHook(vm.nioReadiness()::retryStagedNotifications);
                vm.nioReadiness().bindVmCompletionExecutor(
                        actors::executeHostIoCompletion, true);

                first.source().configureBlocking(false);
                var bootstrap = new java.util.concurrent.CountDownLatch(1);
                var one = vm.nioReadiness().watch(first.source(), SelectionKey.OP_READ);
                one.whenCompleteRuntime((events, failure) -> bootstrap.countDown());
                first.sink().write(ByteBuffer.wrap(new byte[] {1}));
                assertTrue(bootstrap.await(3, TimeUnit.SECONDS));
                assertEquals(1, actors.startedActorCarrierCount());

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (vm.nioReadiness().completionCarrierCount() != 0
                        && System.nanoTime() < deadline)
                    Thread.sleep(1);
                assertEquals(0, vm.nioReadiness().completionCarrierCount(),
                        "subsequent completions must use actor carriers, not bootstrap workers");

                second.source().configureBlocking(false);
                third.source().configureBlocking(false);
                var callbackEntered = new java.util.concurrent.CountDownLatch(1);
                var two = vm.nioReadiness().watch(second.source(), SelectionKey.OP_READ);
                two.whenCompleteRuntime((events, failure) -> {
                    callbackEntered.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                second.sink().write(ByteBuffer.wrap(new byte[] {2}));
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));

                var three = vm.nioReadiness().watch(third.source(), SelectionKey.OP_READ);
                third.sink().write(ByteBuffer.wrap(new byte[] {3}));
                long pendingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (vm.nioReadiness().pendingCount() != 0
                        && System.nanoTime() < pendingDeadline)
                    Thread.sleep(1);
                assertEquals(0, vm.nioReadiness().pendingCount());
                assertEquals(1, actors.startedActorCarrierCount(),
                        "selector NIO completion must not expand non-affine JVM workers");
                assertEquals(0, vm.controlCarrierCount());
                release.countDown();
                assertTrue((two.get(3, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
                assertTrue((three.get(3, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
                vm.nioReadiness().close();
                assertTrue(vm.nioReadiness().awaitStopped(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                // Even on a failed assertion, stop the source of terminal
                // NIO deliveries before retiring actor carrier admission.
                vm.nioReadiness().close();
                first.source().close(); first.sink().close();
                second.source().close(); second.sink().close();
                third.source().close(); third.sink().close();
                if (actors != null) actors.close();
                vm.close();
            }
        } finally {
            if (previousAffinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousAffinity);
            if (previousBackend == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previousBackend);
        }
    }

    @Test void coldStartCreatesControlOnlyForRunnableControlWork() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            assertEquals(0, vm.nioReactorCount());
            assertEquals(0, vm.controlCarrierCount());
            assertEquals(0, vm.ownedRuntimeThreadCount());
            assertEquals(0, vm.nioReadiness().completionCarrierCount());

            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            vm.dispatchHostIoMapping(() -> {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, vm.controlCarrierCount(),
                    "first independently runnable CONTROL task creates one carrier");
            assertEquals(0, vm.nioReadiness().completionCarrierCount());
            release.countDown();
        } finally {
            vm.close();
            assertEquals(0, vm.controlCarrierCount(),
                    "shutdown must leave no VM CONTROL carriers");
            assertEquals(0, vm.nioReadiness().completionCarrierCount(),
                    "shutdown must leave no NIO completion carriers");
        }
    }

    @Test void stalledNioFutureSubscribersCannotStarveControl() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var firstPipe = Pipe.open();
        var secondPipe = Pipe.open();
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            for (var pipe : new Pipe[] {firstPipe, secondPipe}) {
                pipe.source().configureBlocking(false);
                var ready = vm.nioReadiness().watch(pipe.source(), SelectionKey.OP_READ);
                ready.whenCompleteRuntime((events, failure) -> {
                    // These callbacks intentionally occupy BOTH NIO-completion
                    // workers. They must never run on VM CONTROL carriers.
                    if (OresVM.onControlCarrier() || !OresVM.onNioWorker())
                        return;
                    entered.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            }
            assertEquals(1, firstPipe.sink().write(ByteBuffer.wrap(new byte[]{1})));
            assertEquals(1, secondPipe.sink().write(ByteBuffer.wrap(new byte[]{2})));
            assertTrue(entered.await(2, TimeUnit.SECONDS),
                    "NIO callbacks must occupy their own completion workers");

            var controlProgress = new java.util.concurrent.CountDownLatch(1);
            vm.dispatchHostIoMapping(controlProgress::countDown);
            assertTrue(controlProgress.await(2, TimeUnit.SECONDS),
                    "blocking both NIO completion workers must not stall CONTROL");
            assertEquals(2, vm.nioReadiness().pendingNotifications());
        } finally {
            release.countDown();
            vm.close(); // external test driver waits for NIO before CONTROL
            firstPipe.source().close();
            firstPipe.sink().close();
            secondPipe.source().close();
            secondPipe.sink().close();
        }
    }

    @Test void vmOwnedTcpAcceptUsesOneSelectorAndReturnsNonblockingChannel() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var first = java.nio.channels.ServerSocketChannel.open();
             var second = java.nio.channels.ServerSocketChannel.open()) {
            first.configureBlocking(false);
            second.configureBlocking(false);
            first.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            second.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            int firstPort = ((java.net.InetSocketAddress) first.getLocalAddress()).getPort();
            int secondPort = ((java.net.InetSocketAddress) second.getLocalAddress()).getPort();
            var firstAccepted = vm.nioReadiness().acceptAsync(first);
            var secondAccepted = vm.nioReadiness().acceptAsync(second);
            var callbackThread = new java.util.concurrent.CompletableFuture<String>();
            firstAccepted.whenCompleteRuntime((socket, failure) ->
                    callbackThread.complete(Thread.currentThread().getName()));
            assertEquals(1, vm.nioReactorCount(),
                    "two listeners share the VM selector instead of launching listener reactors");
            try (var firstPeer = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), firstPort);
                 var secondPeer = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), secondPort);
                 var firstChannel = firstAccepted.get(3, TimeUnit.SECONDS);
                 var secondChannel = secondAccepted.get(3, TimeUnit.SECONDS)) {
                assertFalse(firstChannel.isBlocking());
                assertFalse(secondChannel.isBlocking());
                assertTrue(callbackThread.get(2, TimeUnit.SECONDS).startsWith("ores-nio-completion-"),
                        "TCP accept Future must settle off selector, CONTROL and actor carriers");
                assertEquals(1, vm.nioReactorCount());
            }

            // A cancelled accept must revoke its pending readiness ticket.
            var cancelled = vm.nioReadiness().acceptAsync(first);
            assertTrue(cancelled.cancel(false));
            assertTrue(cancelled.isCancelled());
        } finally {
            vm.close();
        }
    }

    @Test void mappingWorkerCannotJoinItsOwnControlExecutor() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var outcome = new java.util.concurrent.CompletableFuture<Boolean>();
        try {
            vm.dispatchHostIoMapping(() -> {
                if (!OresVM.onControlCarrier()) {
                    outcome.completeExceptionally(
                            new AssertionError("CONTROL host mapping worker lost identity"));
                    return;
                }
                try {
                    vm.close();
                    outcome.complete(false);
                } catch (IllegalStateException rejected) {
                    outcome.complete(rejected.getMessage().contains("outside actor"));
                }
            });
            assertTrue(outcome.get(3, TimeUnit.SECONDS),
                    "host I/O mapping callback must not attempt executor self-join");
            assertTrue(vm.started(), "rejected callback close cannot retire the VM");
            assertEquals(0, vm.nioReactorCount(),
                    "CONTROL-only work must not start the NIO reactor");
        } finally {
            vm.close(); // external host owns the real teardown
        }
    }

    @Test void completionWorkerCannotJoinItsOwnReactorOrControlPool() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var pipe = Pipe.open();
        try (var source = pipe.source(); var sink = pipe.sink()) {
            pipe.source().configureBlocking(false);
            pipe.sink().configureBlocking(false);
            var pending = vm.nioReadiness().watch(pipe.source(), SelectionKey.OP_READ);
            var callback = new java.util.concurrent.CompletableFuture<Boolean>();
            pending.whenCompleteRuntime((events, failure) -> {
                if (failure != null) {
                    callback.completeExceptionally(failure);
                    return;
                }
                try {
                    if (!OresVM.onNioWorker()) {
                        callback.complete(false);
                        return;
                    }
                    vm.close();
                    callback.complete(false);
                } catch (IllegalStateException expected) {
                    callback.complete(vm.started());
                }
            });
            assertEquals(1, pipe.sink().write(ByteBuffer.wrap(new byte[]{9})));
            assertTrue(callback.get(3, TimeUnit.SECONDS),
                    "NIO runtime subscriber must not self-join its completion pool");
            assertEquals(0, vm.nioReadiness().pendingCount());
        } finally {
            vm.close(); // only external host may join the selector and CONTROL
        }
    }

    @Test void vmCloseRetiresReadinessAndRejectsNewRegistrations() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var nio = vm.nioReadiness();
        var pipe = Pipe.open();
        try (var source = pipe.source(); var sink = pipe.sink()) {
            pipe.source().configureBlocking(false);
            var pending = nio.watch(pipe.source(), SelectionKey.OP_READ);
            assertFalse(pending.isDone());
            vm.close();
            assertThrows(ExecutionException.class,
                    () -> pending.get(3, TimeUnit.SECONDS));
            assertTrue(nio.awaitStopped(3, TimeUnit.SECONDS),
                    "the VM's reactor must quiesce after VM shutdown");
            assertThrows(ExecutionException.class,
                    () -> nio.watch(pipe.source(), SelectionKey.OP_READ).get());
        } finally {
            vm.close();
        }
    }
}
