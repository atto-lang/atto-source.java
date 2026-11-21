package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A public cancelled/closed Future is not proof the underlying Selector watch
 * has retired. This asserts close Futures join the actual host NIO leases.
 */
final class NioChannelPhysicalRetirementTest {
    @Test @Timeout(15)
    void listenerCloseJoinsCancelledKernelAcceptWatch() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (VmNioTcpListener listener = VmNioTcpListener.loopback(
                vm.nioReadiness(), 0, 16, 2, 4)) {
            OresFuture<VmNioTcpListener.Connection> accepting = listener.acceptAsync();
            assertEquals(1, vm.nioReadiness().activeAcceptLeases());
            listener.closeAsync().get(5, TimeUnit.SECONDS);
            assertTrue(listener.isTerminated());
            assertEquals(0, vm.nioReadiness().activeAcceptLeases(),
                    "listener close cannot complete ahead of physical accept lease");
            assertEquals(0, vm.nioReadiness().pendingCount(),
                    "listener close cannot complete with an outstanding Selector watch");
            assertTrue(accepting.isDone());
        } finally {
            vm.close();
        }
    }

    @Test @Timeout(15)
    void connectionAndListenerCloseJoinPendingNativeReadWatch() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (VmNioTcpListener listener = VmNioTcpListener.loopback(
                vm.nioReadiness(), 0, 16, 2, 4)) {
            var accept = listener.acceptAsync();
            try (Socket client = new Socket(InetAddress.getByName("127.0.0.1"),
                    listener.localPort())) {
                VmNioTcpListener.Connection connection = accept.get(5, TimeUnit.SECONDS);
                var pendingRead = connection.readBytesAsync(64);
                // Closing the parent also closes its accepted connection.
                listener.closeAsync().get(5, TimeUnit.SECONDS);
                connection.closeAsync().get(5, TimeUnit.SECONDS);
                assertEquals(0, vm.nioReadiness().activeReadLeases());
                assertEquals(0, vm.nioReadiness().pendingCount());
                assertTrue(pendingRead.isDone());
                assertTrue(listener.isTerminated());
            }
        } finally {
            vm.close();
        }
    }
}
