package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Linux host-layer parity proof for four independent SO_REUSEPORT sockets.
 * This is NOT an executable Oreslang actor, transfer, or guest std/net test.
 */
@EnabledOnOs(OS.LINUX)
final class VmNioWorkerReusePortTest {
    @Test
    void fourSocketsOnOnePortAcceptOneClientExactlyOnce() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        List<VmNioTcpListener> listeners = new ArrayList<>();
        List<OresFuture<VmNioTcpListener.Connection>> pending = new ArrayList<>();
        try {
            listeners.add(VmNioTcpListener.loopbackReusePort(
                    vm.nioReadiness(), 0, 32, 8, 16));
            final int port = listeners.getFirst().localPort();
            assertTrue(port > 0);
            for (int i = 1; i < 4; i++) {
                VmNioTcpListener listener = VmNioTcpListener.loopbackReusePort(
                        vm.nioReadiness(), port, 32, 8, 16);
                listeners.add(listener);
                assertEquals(port, listener.localPort());
            }
            assertEquals(0, vm.nioReactorCount(),
                    "binding sockets cannot start NIO reactors");

            for (VmNioTcpListener listener : listeners)
                pending.add(listener.acceptAsync());
            assertEquals(1, vm.nioReactorCount(),
                    "four listening sockets share the same VM reactor");

            try (Socket client = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (pending.stream().noneMatch(OresFuture::isDone)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(2); // test thread only; no actor carrier parks
                }
                int accepted = 0;
                for (OresFuture<VmNioTcpListener.Connection> future : pending) {
                    if (!future.isDone()) continue;
                    VmNioTcpListener.Connection connection = future.get(3, TimeUnit.SECONDS);
                    accepted++;
                    connection.closeAsync().get(3, TimeUnit.SECONDS);
                }
                assertEquals(1, accepted,
                        "one incoming client must be accepted by exactly one socket");
            }
        } finally {
            for (OresFuture<VmNioTcpListener.Connection> future : pending)
                future.cancel(false);
            for (VmNioTcpListener listener : listeners)
                listener.close();
            vm.close();
        }
    }
}
