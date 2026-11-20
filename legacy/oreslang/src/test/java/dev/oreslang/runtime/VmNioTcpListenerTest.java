package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class VmNioTcpListenerTest {
    @Test
    void twoVmOwnedListenersShareOneSelectorAndEcho() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        VmNioTcpListener first =
                vm.openNioTcpLoopback(0, 16, 4, 16);
        VmNioTcpListener second =
                vm.openNioTcpLoopback(0, 16, 4, 16);
        try {
            assertEquals(2, vm.nioTcpListenerCount());
            assertEquals(0, vm.nioReactorCount(),
                    "opening listeners must not create selector threads");

            OresFuture<VmNioTcpListener.Connection> firstAccept =
                    first.acceptAsync();
            OresFuture<VmNioTcpListener.Connection> secondAccept =
                    second.acceptAsync();

            try (Socket peer1 = new Socket(
                         InetAddress.getByName("127.0.0.1"), first.localPort());
                 Socket peer2 = new Socket(
                         InetAddress.getByName("127.0.0.1"), second.localPort())) {
                peer1.setSoTimeout(3_000);
                peer2.setSoTimeout(3_000);

                VmNioTcpListener.Connection server1 =
                        firstAccept.get(5, TimeUnit.SECONDS);
                VmNioTcpListener.Connection server2 =
                        secondAccept.get(5, TimeUnit.SECONDS);
                try {
                    peer1.getOutputStream().write(71);
                    assertEquals(
                            List.of(71L),
                            server1.readBytesAsync(64)
                                    .get(5, TimeUnit.SECONDS));
                    assertEquals(
                            1,
                            server1.writeBytesAsync(List.of(72L))
                                    .get(5, TimeUnit.SECONDS));
                    assertEquals(72, peer1.getInputStream().read());

                    peer2.getOutputStream().write(73);
                    assertEquals(
                            List.of(73L),
                            server2.readBytesAsync(64)
                                    .get(5, TimeUnit.SECONDS));
                    assertEquals(
                            1,
                            server2.writeBytesAsync(List.of(74L))
                                    .get(5, TimeUnit.SECONDS));
                    assertEquals(74, peer2.getInputStream().read());

                    assertEquals(1, vm.nioReactorCount(),
                            "all VM listeners must share one selector reactor");
                } finally {
                    server1.closeAsync().get(5, TimeUnit.SECONDS);
                    server2.closeAsync().get(5, TimeUnit.SECONDS);
                }
            }

            first.closeAsync().get(5, TimeUnit.SECONDS);
            second.closeAsync().get(5, TimeUnit.SECONDS);
            assertTrue(first.isTerminated());
            assertTrue(second.isTerminated());
            assertEquals(0, vm.nioTcpListenerCount());
        } finally {
            first.close();
            second.close();
            vm.close();
        }
    }

    @Test
    void cancelledAcceptReleasesReadinessLeaseAndDoesNotConsumeNextPeer()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        VmNioTcpListener listener =
                vm.openNioTcpLoopback(0, 8, 1, 4);
        try {
            OresFuture<VmNioTcpListener.Connection> abandoned =
                    listener.acceptAsync();
            assertTrue(abandoned.cancel(false));
            assertTrue(vm.nioReadiness().awaitIdle(5, TimeUnit.SECONDS));
            assertEquals(0, listener.pendingOperations());

            OresFuture<VmNioTcpListener.Connection> armed =
                    listener.acceptAsync();
            try (Socket peer = new Socket(
                    InetAddress.getByName("127.0.0.1"),
                    listener.localPort())) {
                VmNioTcpListener.Connection accepted =
                        armed.get(5, TimeUnit.SECONDS);
                accepted.closeAsync().get(5, TimeUnit.SECONDS);
            }

            listener.closeAsync().get(5, TimeUnit.SECONDS);
            assertTrue(listener.isTerminated());
        } finally {
            listener.close();
            vm.close();
        }
    }

    @Test
    void connectionIsSingleOperationUntilCancellationRetiresItsWatch()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        VmNioTcpListener listener =
                vm.openNioTcpLoopback(0, 8, 1, 4);
        try {
            OresFuture<VmNioTcpListener.Connection> accepting =
                    listener.acceptAsync();
            try (Socket peer = new Socket(
                    InetAddress.getByName("127.0.0.1"),
                    listener.localPort())) {
                peer.setSoTimeout(3_000);
                VmNioTcpListener.Connection connection =
                        accepting.get(5, TimeUnit.SECONDS);

                OresFuture<List<Long>> pending =
                        connection.readBytesAsync(1);
                OresFuture<Integer> denied =
                        connection.writeBytesAsync(List.of(17L));
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> denied.get(2, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class,
                        failure.getCause());

                assertTrue(pending.cancel(false));
                assertTrue(vm.nioReadiness().awaitIdle(
                        5, TimeUnit.SECONDS));

                long deadline =
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (listener.pendingOperations() != 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(0, listener.pendingOperations());

                assertEquals(
                        1,
                        connection.writeBytesAsync(List.of(18L))
                                .get(5, TimeUnit.SECONDS));
                assertEquals(18, peer.getInputStream().read());
                connection.closeAsync().get(5, TimeUnit.SECONDS);
            }
        } finally {
            listener.close();
            vm.close();
        }
    }

    @Test
    void vmCloseRevokesPendingSocketWorkAndRetiresDescriptors()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        VmNioTcpListener listener =
                vm.openNioTcpLoopback(0, 8, 1, 4);
        OresFuture<List<Long>> pendingRead;
        try (Socket peer = new Socket(
                InetAddress.getByName("127.0.0.1"),
                listener.localPort())) {
            OresFuture<VmNioTcpListener.Connection> accepting =
                    listener.acceptAsync();
            VmNioTcpListener.Connection connection =
                    accepting.get(5, TimeUnit.SECONDS);
            pendingRead = connection.readBytesAsync(1);

            vm.close();

            assertTrue(listener.isTerminated());
            assertEquals(0, listener.pendingOperations());
            assertEquals(0, vm.nioReactorCount());
            assertEquals(0, vm.nioTcpListenerCount());
            assertThrows(CancellationException.class,
                    () -> pendingRead.get(2, TimeUnit.SECONDS));
        } finally {
            listener.close();
            vm.close();
        }
    }

    @Test
    void byteAndListenerBoundsFailClosedWithoutOperationLeak()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        assertThrows(
                IllegalArgumentException.class,
                () -> vm.openNioTcpLoopback(-1, 8, 1, 4));

        VmNioTcpListener listener =
                vm.openNioTcpLoopback(0, 8, 1, 4);
        try {
            OresFuture<VmNioTcpListener.Connection> accepting =
                    listener.acceptAsync();
            try (Socket peer = new Socket(
                    InetAddress.getByName("127.0.0.1"),
                    listener.localPort())) {
                VmNioTcpListener.Connection connection =
                        accepting.get(5, TimeUnit.SECONDS);

                ExecutionException readFailure = assertThrows(
                        ExecutionException.class,
                        () -> connection.readBytesAsync(0)
                                .get(2, TimeUnit.SECONDS));
                assertInstanceOf(
                        IllegalArgumentException.class,
                        readFailure.getCause());

                ExecutionException writeFailure = assertThrows(
                        ExecutionException.class,
                        () -> connection.writeBytesAsync(
                                List.of(0L, 256L))
                                .get(2, TimeUnit.SECONDS));
                assertInstanceOf(
                        IllegalArgumentException.class,
                        writeFailure.getCause());
                assertEquals(0, listener.pendingOperations());

                connection.closeAsync().get(5, TimeUnit.SECONDS);
            }
        } finally {
            listener.close();
            vm.close();
        }
    }
}
