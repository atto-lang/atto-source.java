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
    @Test void fivePendingIoWatchesShareOneVmReactor() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var pipes = new ArrayList<Pipe>();
        try {
            assertEquals(1, vm.nioReactorCount(), "one initial process-level readiness thread");
            assertTrue(vm.controlCarrierCount() >= 2);
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
        try (var pipe = Pipe.open()) {
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
        try (var pipe = Pipe.open()) {
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
