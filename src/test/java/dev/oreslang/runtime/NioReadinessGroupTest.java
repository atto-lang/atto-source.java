package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class NioReadinessGroupTest {
    @Test void slowFutureSubscribersNeverRunOnSelectorAndBackpressureIsBounded() throws Exception {
        // A runtime completion subscriber is allowed to be adversarial host
        // code; it must not pin the selector or grow the completion queue.
        try (var group = new NioReadinessGroup(1, 2, 8)) {
            // Pipe is not AutoCloseable: its two channels are individually owned.
            var one = java.nio.channels.Pipe.open();
            var two = java.nio.channels.Pipe.open();
            var three = java.nio.channels.Pipe.open();
            try {
            one.source().configureBlocking(false);
            two.source().configureBlocking(false);
            three.source().configureBlocking(false);
            var callbacksEntered = new java.util.concurrent.CountDownLatch(2);
            var release = new java.util.concurrent.CountDownLatch(1);
            var callbackThreads = new java.util.concurrent.CopyOnWriteArrayList<String>();
            java.util.function.BiConsumer<Integer, Throwable> slow = (value, failure) -> {
                callbackThreads.add(Thread.currentThread().getName());
                callbacksEntered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            };
            var first = group.watch(one.source(), SelectionKey.OP_READ);
            var second = group.watch(two.source(), SelectionKey.OP_READ);
            first.whenCompleteRuntime(slow);
            second.whenCompleteRuntime(slow);
            try {
                assertEquals(1, one.sink().write(java.nio.ByteBuffer.wrap(new byte[]{1})));
                assertEquals(1, two.sink().write(java.nio.ByteBuffer.wrap(new byte[]{2})));
                assertTrue(callbacksEntered.await(2, TimeUnit.SECONDS),
                        "both slow subscribers should occupy only completion workers");
                assertTrue(callbackThreads.stream().allMatch(t -> t.startsWith("ores-nio-completion-")),
                        "selector must never call arbitrary runtime subscribers");
                assertEquals(0, group.pendingCount(),
                        "selector must retire both watches even while subscribers wait");
                assertEquals(2, group.pendingNotifications());
                var denied = group.watch(three.source(), SelectionKey.OP_READ);
                var exhausted = assertThrows(ExecutionException.class,
                        () -> denied.get(2, TimeUnit.SECONDS));
                assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                        exhausted.getCause(), "notification backlog must reject admission");
            } finally {
                release.countDown();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (group.pendingNotifications() != 0 && System.nanoTime() < deadline)
                Thread.sleep(1); // JUnit driver only, never an Ores actor
            assertEquals(0, group.pendingNotifications(), "callback completion must release quota");
            var third = group.watch(three.source(), SelectionKey.OP_READ);
            assertEquals(1, three.sink().write(java.nio.ByteBuffer.wrap(new byte[]{3})));
            assertTrue((third.get(2, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            assertEquals(0, group.pendingNotifications());
            } finally {
                one.source().close();
                one.sink().close();
                two.source().close();
                two.sink().close();
                three.source().close();
                three.sink().close();
            }
        }
    }

    @Test void fivePendingReadsUseOneReactorNotFiveIoThreads() throws Exception {
        try (var group = new NioReadinessGroup(4, 32, 64);
             var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            var incoming = new ArrayList<SocketChannel>();
            var peers = new ArrayList<Socket>();
            var pending = new ArrayList<OresFuture<Integer>>();
            try {
                for (int i = 0; i < 5; i++) {
                    var peer = new Socket(InetAddress.getLoopbackAddress(), port);
                    peers.add(peer);
                    SocketChannel channel = null;
                    for (int n = 0; n < 1000 && channel == null; n++) {
                        channel = listener.accept();
                        if (channel == null) Thread.sleep(1); // JUnit host thread only
                    }
                    assertNotNull(channel);
                    channel.configureBlocking(false);
                    incoming.add(channel);
                    pending.add(group.watch(channel, SelectionKey.OP_READ));
                }
                assertEquals(1, group.reactorCount());
                assertEquals(5, group.pendingCount());
                assertEquals(0, pending.stream().filter(OresFuture::isDone).count());
                for (var peer : peers) peer.getOutputStream().write(0x41);
                for (var future : pending)
                    assertTrue((future.get(2, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
                assertEquals(0, group.pendingCount());

                // Cancelling a pending watch must not occupy a carrier until
                // the socket becomes readable, nor retain the registration.
                var abandoned = group.watch(incoming.get(0), SelectionKey.OP_READ);
                assertTrue(abandoned.cancel(false));
                for (int n = 0; n < 1000 && group.pendingCount() != 0; n++)
                    Thread.sleep(1);
                assertEquals(0, group.pendingCount());

                var stopped = group.watch(incoming.get(1), SelectionKey.OP_READ);
                group.close();
                assertThrows(ExecutionException.class, () -> stopped.get(2, TimeUnit.SECONDS));
                assertTrue(group.awaitStopped(2, TimeUnit.SECONDS));
            } finally {
                for (var peer : peers) peer.close();
                for (var channel : incoming) channel.close();
            }
        }
    }

    @Test void selectedKeysBeyondOneQuantumAreNotStranded() throws Exception {
        // More than one readiness quantum on one selector. The reactor must
        // service the residual selected set without waiting for new I/O.
        try (var group = new NioReadinessGroup(1, 192, 256)) {
            var pipes = new ArrayList<java.nio.channels.Pipe>();
            var awaiting = new ArrayList<OresFuture<Integer>>();
            try {
                for (int i = 0; i < 160; ++i) {
                    var pipe = java.nio.channels.Pipe.open();
                    pipes.add(pipe);
                    pipe.source().configureBlocking(false);
                    pipe.sink().configureBlocking(false);
                    awaiting.add(group.watch(pipe.source(), SelectionKey.OP_READ));
                }
                assertEquals(1, group.reactorCount());
                for (var pipe : pipes)
                    assertEquals(1, pipe.sink().write(
                            java.nio.ByteBuffer.wrap(new byte[] {42})));
                for (var future : awaiting)
                    assertTrue((future.get(3, TimeUnit.SECONDS) & SelectionKey.OP_READ) != 0);
                assertEquals(0, group.pendingCount());
            } finally {
                for (var pipe : pipes) {
                    pipe.source().close();
                    pipe.sink().close();
                }
            }
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test void cancellationBurstDrainsWithoutScanningIdleWatchers() throws Exception {
        try (var group = new NioReadinessGroup(1, 256, 256)) {
            var pipes = new ArrayList<java.nio.channels.Pipe>();
            var waiting = new ArrayList<OresFuture<Integer>>();
            try {
                for (int i = 0; i < 192; ++i) {
                    var pipe = java.nio.channels.Pipe.open();
                    pipes.add(pipe);
                    pipe.source().configureBlocking(false);
                    waiting.add(group.watch(pipe.source(), SelectionKey.OP_READ));
                }
                assertEquals(192, group.pendingCount());
                for (var future : waiting) assertTrue(future.cancel(false));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (group.pendingCount() != 0 && System.nanoTime() < deadline)
                    Thread.sleep(1); // test harness only
                assertEquals(0, group.pendingCount(),
                        "cancelled watches must be reclaimed within bounded quanta");
                assertEquals(1, group.reactorCount());
            } finally {
                for (var pipe : pipes) {
                    pipe.source().close();
                    pipe.sink().close();
                }
            }
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test void failClosedOnInvalidAdmissionAndMaxPending() throws Exception {
        try (var group = new NioReadinessGroup(1, 1, 8);
             var listener = ServerSocketChannel.open()) {
            assertThrows(IllegalArgumentException.class,
                    () -> group.watch(listener, SelectionKey.OP_ACCEPT));
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            var waiting = group.watch(listener, SelectionKey.OP_ACCEPT);
            assertFalse(waiting.isDone());
            try (var other = ServerSocketChannel.open()) {
                other.configureBlocking(false);
                other.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                var exhausted = assertThrows(ExecutionException.class,
                        () -> group.watch(other, SelectionKey.OP_ACCEPT).get());
                assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                        exhausted.getCause());
            }
            // A second watch on the same channel fails independently of count.
            assertThrows(ExecutionException.class,
                    () -> group.watch(listener, SelectionKey.OP_ACCEPT).get());
            assertTrue(waiting.cancel(false));
            group.close();
            assertTrue(group.awaitStopped(2, TimeUnit.SECONDS));
        }
    }

    @Test void abandonedPrestartReactorClosesSelector() throws Exception {
        try (var group = new NioReadinessGroup(1, 16, 16)) {
            // Exercise the constructor-error cleanup path without depending on
            // OS pthread exhaustion or process-wide artificial failure flags.
            Class<?> type = Class.forName(
                    "dev.oreslang.runtime.NioReadinessGroup$Reactor");
            var constructor = type.getDeclaredConstructor(NioReadinessGroup.class, int.class);
            constructor.setAccessible(true);
            Object unstarted = constructor.newInstance(group, 7);
            var field = type.getDeclaredField("selector");
            field.setAccessible(true);
            var selector = (java.nio.channels.Selector) field.get(unstarted);
            assertTrue(selector.isOpen());
            var abort = type.getDeclaredMethod("abortUnstarted");
            abort.setAccessible(true);
            abort.invoke(unstarted);
            assertFalse(selector.isOpen(), "failed bootstrap must release its selector");
            abort.invoke(unstarted); // cleanup is idempotent
            assertFalse(selector.isOpen());
            group.close();
            assertTrue(group.awaitStopped(2, TimeUnit.SECONDS));
        }
    }

    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(0, 10, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(5, 10, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(1, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(1, 10, 7));
    }
}
