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
        assertDoesNotThrow(() -> OresVM.validateControlBounds(2, 20));
        assertDoesNotThrow(() -> OresVM.validateControlBounds(20, 20));
        for (int[] limits : new int[][] {
                {0, 20}, {1, 20}, {2, 1}, {2, 21}, {21, 21}, {4, 3}
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresVM.validateControlBounds(limits[0], limits[1]));
        }
    }

    @Test void fivePendingIoWatchesShareOneVmReactor() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var pipes = new ArrayList<Pipe>();
        try {
            assertEquals(1, vm.nioReactorCount(), "one initial process-level readiness thread");
            assertTrue(vm.controlCarrierCount() >= 2);
            assertFalse(vm.nioReadiness().usesVmCompletionCarriers(),
                    "arbitrary NIO Future subscribers must not consume CONTROL carriers");
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
                    "five pending I/O calls do not justify five selector threads");
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
            assertEquals(1, vm.nioReactorCount(),
                    "two listeners share the VM selector instead of launching listener reactors");
            try (var firstPeer = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), firstPort);
                 var secondPeer = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), secondPort);
                 var firstChannel = firstAccepted.get(3, TimeUnit.SECONDS);
                 var secondChannel = secondAccepted.get(3, TimeUnit.SECONDS)) {
                assertFalse(firstChannel.isBlocking());
                assertFalse(secondChannel.isBlocking());
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
            assertEquals(1, vm.nioReactorCount());
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
