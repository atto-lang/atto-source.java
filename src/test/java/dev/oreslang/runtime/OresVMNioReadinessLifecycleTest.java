package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class OresVMNioReadinessLifecycleTest {
    @Test
    void vmOwnsLazyReadinessAndClosesReactorBeforeReturning() throws Exception {
        OresVM vm = OresVM.createConfigured(Runnable::run, null, "2", false);
        NioReadinessGroup readiness = vm.nioReadiness();
        Pipe pipe = Pipe.open();
        try (Pipe.SourceChannel source = pipe.source();
             Pipe.SinkChannel sink = pipe.sink()) {
            assertEquals(0, vm.nioReactorCount(),
                    "VM construction must not create a selector thread");

            OresFuture<Integer> ready =
                    readiness.watch(source, SelectionKey.OP_READ);
            sink.write(ByteBuffer.wrap(new byte[] { 7 }));
            int mask = ready.get(5, TimeUnit.SECONDS);

            assertNotEquals(0, mask & SelectionKey.OP_READ);
            assertEquals(1, vm.nioReactorCount());
        } finally {
            vm.close();
        }

        assertTrue(readiness.isClosed());
        assertTrue(readiness.awaitStopped(1, TimeUnit.SECONDS));
        assertEquals(0, readiness.reactorCount(),
                "OresVM.close must return only after the NIO reactor exits");
    }

    @Test
    void vmCloseFromNioWorkerIsRejectedBeforeShutdownBegins() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> OresVM.runAsNioWorker(vm::close));
            assertTrue(failure.getMessage().contains("outside actor"));
            assertTrue(vm.started(),
                    "rejected self-join must not poison VM lifecycle state");
        } finally {
            vm.close();
        }
    }

    @Test
    void sharedSelectorRunsBetweenRootCarrierTurns() throws Exception {
        OresVM vm = OresVM.createConfigured(Runnable::run, null, "1", true);
        NioReadinessGroup readiness = vm.nioReadiness();
        Pipe pipe = Pipe.open();
        try (vm; Pipe.SourceChannel source = pipe.source(); Pipe.SinkChannel sink = pipe.sink()) {
            OresFuture<Integer> ready = readiness.watch(source, SelectionKey.OP_READ);
            AtomicReference<Thread> selectorCarrier = new AtomicReference<>();
            ready.whenCompleteRuntime((mask, failure) -> {
                if (OresVM.onNioWorker()) selectorCarrier.set(Thread.currentThread());
            });
            CountDownLatch started = new CountDownLatch(1);
            OresFuture<Thread> task = vm.rootScheduler().start(resume -> {
                assertFalse(OresVM.onNioWorker(), "a task turn must not inherit reactor authority");
                if (resume.initial()) {
                    started.countDown();
                    return new OresScheduler.Await<>(ready);
                }
                assertNull(resume.failure());
                assertEquals(SelectionKey.OP_READ, resume.value());
                return new OresScheduler.Done<>(Thread.currentThread());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            sink.write(ByteBuffer.wrap(new byte[] {7}));
            Thread resumed = task.get(5, TimeUnit.SECONDS);
            assertSame(selectorCarrier.get(), resumed);
            assertTrue(resumed.getName().startsWith("ores-control-plane-"));
            assertEquals(0, vm.nioReactorCount());
            assertFalse(vm.nioCompletionGroupLive());
        }
        assertTrue(readiness.awaitStopped(0, TimeUnit.SECONDS));
        assertEquals(0, readiness.pendingCount());
        assertEquals(0, vm.controlCarrierCount(), "VM close joins the shared root carriers");
    }

    @Test
    void sharedSelectorHandsCarrierBackWhileMoreDescriptorsAreReady() throws Exception {
        var pipes = new ArrayList<Pipe>();
        var pending = new ArrayList<OresFuture<Integer>>();
        try (var readiness = new NioReadinessGroup(() -> { })) {
            for (int i = 0; i < 80; i++) {
                Pipe pipe = Pipe.open();
                pipes.add(pipe);
                pending.add(readiness.watch(pipe.source(), SelectionKey.OP_READ));
            }
            // Install every watch before making any descriptors ready.
            for (int i = 0; i < 3; i++) readiness.pollShared(false);
            for (Pipe pipe : pipes) pipe.sink().write(ByteBuffer.wrap(new byte[] {1}));

            assertTrue(readiness.pollShared(false));
            long firstTurn = pending.stream().filter(OresFuture::isDone).count();
            assertTrue(firstTurn > 0 && firstTurn < pending.size(),
                    "a shared selector turn should leave time for queued scheduler work");
            for (int i = 0; i < pending.size() && readiness.pollShared(false); i++) { }
            for (var future : pending) {
                assertEquals(SelectionKey.OP_READ, future.get(1, TimeUnit.SECONDS));
            }
        } finally {
            for (Pipe pipe : pipes) {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }
}
