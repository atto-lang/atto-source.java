package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class OresVMNioReadinessLifecycleTest {
    @Test
    void vmOwnsLazyReadinessAndClosesReactorBeforeReturning() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
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
}
