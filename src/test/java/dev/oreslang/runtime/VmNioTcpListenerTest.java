package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Host-level regression for the same transport called by compiled std/net. */
final class VmNioTcpListenerTest {
    @Test void twoListenersAndGuestShapeEchoShareTheSingleVmSelector() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var first = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 16, 4, 16);
             var second = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 16, 4, 16)) {
            assertEquals(1, vm.nioReactorCount());
            var firstAccept = first.acceptAsync();
            var secondAccept = second.acceptAsync();
            try (var client1 = new Socket(InetAddress.getByName("127.0.0.1"), first.localPort());
                 var client2 = new Socket(InetAddress.getByName("127.0.0.1"), second.localPort())) {
                client1.setSoTimeout(3000);
                client2.setSoTimeout(3000);
                var server1 = firstAccept.get(3, TimeUnit.SECONDS);
                var server2 = secondAccept.get(3, TimeUnit.SECONDS);
                try {
                    client1.getOutputStream().write(71);
                    assertEquals(List.of(71L), server1.readBytesAsync(64).get(3, TimeUnit.SECONDS));
                    assertEquals(1, server1.writeBytesAsync(List.of(71L)).get(3, TimeUnit.SECONDS));
                    assertEquals(71, client1.getInputStream().read());
                    client2.getOutputStream().write(72);
                    assertEquals(List.of(72L), server2.readBytesAsync(64).get(3, TimeUnit.SECONDS));
                    assertEquals(1, server2.writeBytesAsync(List.of(72L)).get(3, TimeUnit.SECONDS));
                    assertEquals(72, client2.getInputStream().read());
                    assertEquals(1, vm.nioReactorCount(),
                            "multiple std/net listeners must not start selector threads");
                } finally {
                    server1.close();
                    server2.close();
                }
            }
            first.closeAsync().get(3, TimeUnit.SECONDS);
            second.closeAsync().get(3, TimeUnit.SECONDS);
        } finally {
            vm.close();
        }
    }

    @Test void cancelledAcceptFreesListenerLeaseAndDoesNotConsumeNextConnection() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 16, 1, 16)) {
            var abandoned = listener.acceptAsync();
            assertTrue(abandoned.cancel(false));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((vm.nioReadiness().pendingCount() != 0
                    || vm.nioReadiness().activeAcceptLeases() != 0)
                    && System.nanoTime() < deadline) {
                Thread.sleep(1); // JUnit host thread, not actor
            }
            assertEquals(0, vm.nioReadiness().pendingCount());
            assertEquals(0, vm.nioReadiness().activeAcceptLeases());
            var armed = listener.acceptAsync();
            try (var peer = new Socket(InetAddress.getByName("127.0.0.1"), listener.localPort())) {
                var accepted = armed.get(3, TimeUnit.SECONDS);
                try {
                    var rejected = listener.acceptAsync();
                    var failure = assertThrows(ExecutionException.class,
                            () -> rejected.get(3, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof java.util.concurrent.RejectedExecutionException
                                    || failure.getCause() instanceof IllegalStateException);
                } finally {
                    accepted.close();
                }
            }
            listener.closeAsync().get(3, TimeUnit.SECONDS);
            assertTrue(listener.isTerminated());
        } finally {
            vm.close();
        }
    }

    @Test void listenerWideMaxPendingDeniesExtraSocketOperationsAndRecovers() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 16, 1, 1)) {
            var accepting = listener.acceptAsync();
            try (var peer = new Socket(InetAddress.getByName("127.0.0.1"), listener.localPort())) {
                peer.setSoTimeout(3000);
                var connection = accepting.get(3, TimeUnit.SECONDS);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (listener.pendingOperations() != 0 && System.nanoTime() < deadline)
                        Thread.sleep(1); // host test driver only
                    assertEquals(0, listener.pendingOperations());
                    var pendingRead = connection.readBytesAsync(1);
                    assertEquals(1, listener.pendingOperations());
                    var deniedWrite = connection.writeBytesAsync(List.of(17L));
                    var failure = assertThrows(ExecutionException.class,
                            () -> deniedWrite.get(3, TimeUnit.SECONDS));
                    assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                            failure.getCause());
                    assertTrue(pendingRead.cancel(false));
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while ((listener.pendingOperations() != 0
                            || vm.nioReadiness().pendingCount() != 0
                            || vm.nioReadiness().activeReadLeases() != 0)
                            && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    assertEquals(0, listener.pendingOperations(),
                            "cancelled operation must release listener credit");
                    assertEquals(0, vm.nioReadiness().activeReadLeases(),
                            "cancelled read must also drain its channel lease");
                    assertEquals(1, connection.writeBytesAsync(List.of(17L))
                            .get(3, TimeUnit.SECONDS));
                    assertEquals(17, peer.getInputStream().read());
                } finally {
                    connection.close();
                }
            }
        } finally {
            vm.close();
        }
    }

    @Test void completedAndClosedConnectionFailClosedForSubsequentOperations() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 16, 1, 16)) {
            var accepting = listener.acceptAsync();
            try (var peer = new Socket(InetAddress.getByName("127.0.0.1"), listener.localPort())) {
                var connected = accepting.get(3, TimeUnit.SECONDS);
                connected.closeAsync().get(3, TimeUnit.SECONDS);
                var late = connected.readBytesAsync(1);
                var failure = assertThrows(ExecutionException.class,
                        () -> late.get(3, TimeUnit.SECONDS));
                assertInstanceOf(java.io.IOException.class, failure.getCause());
                var invalidWrite = connected.writeBytesAsync(List.of(999L));
                var denied = assertThrows(ExecutionException.class,
                        () -> invalidWrite.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IllegalArgumentException.class, denied.getCause());
            }
        } finally {
            vm.close();
        }
    }
}
