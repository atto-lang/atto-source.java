package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@Timeout(20)
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ_WRITE)
final class VmNioTcpClientTest {
    @Test
    void strictModeClientUsesSharedVmSelectorAndEchoes() throws Exception {
        String previous = System.getProperty(NioOnlyPolicy.PROPERTY);
        System.setProperty(NioOnlyPolicy.PROPERTY, "nio-only");
        OresVM vm = OresVM.create(Runnable::run);
        VmNioTcpListener listener =
                vm.openNioTcpLoopback(0, 16, 4, 16);
        try {
            OresFuture<VmNioTcpListener.Connection> accepting =
                    listener.acceptAsync();
            OresFuture<VmNioTcpClient.Connection> connecting =
                    vm.connectNioTcp(
                            "127.0.0.1",
                            listener.localPort(),
                            5_000);

            VmNioTcpClient.Connection client =
                    connecting.get(5, TimeUnit.SECONDS);
            VmNioTcpListener.Connection server =
                    accepting.get(5, TimeUnit.SECONDS);
            try {
                assertEquals(1, vm.nioReactorCount(),
                        "listener and client must share the VM selector");
                assertEquals(1, vm.nioTcpClientCount());

                assertEquals(
                        1,
                        client.writeBytesAsync(List.of(91L))
                                .get(5, TimeUnit.SECONDS));
                assertEquals(
                        List.of(91L),
                        server.readBytesAsync(8)
                                .get(5, TimeUnit.SECONDS));

                assertEquals(
                        1,
                        server.writeBytesAsync(List.of(92L))
                                .get(5, TimeUnit.SECONDS));
                assertEquals(
                        List.of(92L),
                        client.readBytesAsync(8)
                                .get(5, TimeUnit.SECONDS));
            } finally {
                client.closeAsync().get(5, TimeUnit.SECONDS);
                server.closeAsync().get(5, TimeUnit.SECONDS);
            }

            long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (vm.nioTcpClientCount() != 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(0, vm.nioTcpClientCount());
        } finally {
            listener.close();
            vm.close();
            if (previous == null) {
                System.clearProperty(NioOnlyPolicy.PROPERTY);
            } else {
                System.setProperty(NioOnlyPolicy.PROPERTY, previous);
            }
        }
    }

    @Test
    void hostnameConnectFailsClosedWithoutStartingResolverOrReactor()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> vm.connectNioTcp(
                            "localhost", 80, 1_000));
            assertTrue(failure.getMessage().contains("numeric IPv4"));
            assertEquals(0, vm.nioTcpClientCount());
            assertEquals(0, vm.nioReactorCount());
        } finally {
            vm.close();
        }
    }

    @Test
    void invalidConnectBoundsFailBeforeSelectorAdmission() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> vm.connectNioTcp(
                            "127.0.0.1", 0, 1_000));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> vm.connectNioTcp(
                            "127.0.0.1", 80, 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> vm.connectNioTcp(
                            "127.0.0.1", 80, 120_001));
            assertEquals(0, vm.nioTcpClientCount());
            assertEquals(0, vm.nioReactorCount());
        } finally {
            vm.close();
        }
    }

    @Test
    void vmCloseRetiresEstablishedClientAndItsSocketOperations()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        VmNioTcpListener listener =
                vm.openNioTcpLoopback(0, 8, 1, 8);
        OresFuture<VmNioTcpListener.Connection> accepting =
                listener.acceptAsync();
        OresFuture<VmNioTcpClient.Connection> connecting =
                vm.connectNioTcp(
                        "127.0.0.1",
                        listener.localPort(),
                        5_000);

        VmNioTcpClient.Connection client =
                connecting.get(5, TimeUnit.SECONDS);
        VmNioTcpListener.Connection server =
                accepting.get(5, TimeUnit.SECONDS);
        try {
            OresFuture<List<Long>> pending =
                    client.readBytesAsync(1);

            vm.close();

            assertEquals(0, vm.nioReactorCount());
            assertEquals(0, vm.nioTcpClientCount());
            assertTrue(listener.isTerminated());

            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> pending.get(2, TimeUnit.SECONDS));

            OresFuture<Integer> afterClose =
                    client.writeBytesAsync(List.of(1L));
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> afterClose.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, failure.getCause());
        } finally {
            client.close();
            server.close();
            listener.close();
            vm.close();
        }
    }

    @Test
    void clientConnectionRemainsSingleOperationUntilCancelDrainsWatch()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        VmNioTcpListener listener =
                vm.openNioTcpLoopback(0, 8, 1, 8);
        try {
            OresFuture<VmNioTcpListener.Connection> accepting =
                    listener.acceptAsync();
            VmNioTcpClient.Connection client =
                    vm.connectNioTcp(
                            "127.0.0.1",
                            listener.localPort(),
                            5_000)
                            .get(5, TimeUnit.SECONDS);
            VmNioTcpListener.Connection server =
                    accepting.get(5, TimeUnit.SECONDS);
            try {
                OresFuture<List<Long>> pending =
                        client.readBytesAsync(1);

                OresFuture<Integer> denied =
                        client.writeBytesAsync(List.of(7L));
                ExecutionException denial = assertThrows(
                        ExecutionException.class,
                        () -> denied.get(2, TimeUnit.SECONDS));
                assertInstanceOf(
                        IllegalStateException.class,
                        denial.getCause());

                assertTrue(pending.cancel(false));
                assertTrue(vm.nioReadiness().awaitIdle(
                        5, TimeUnit.SECONDS));

                assertEquals(
                        1,
                        client.writeBytesAsync(List.of(8L))
                                .get(5, TimeUnit.SECONDS));
                assertEquals(
                        List.of(8L),
                        server.readBytesAsync(1)
                                .get(5, TimeUnit.SECONDS));
            } finally {
                client.close();
                server.close();
            }
        } finally {
            listener.close();
            vm.close();
        }
    }
}
