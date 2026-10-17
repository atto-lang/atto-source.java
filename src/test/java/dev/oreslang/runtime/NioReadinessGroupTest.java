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
    @Test void cancelledSelectionKeyNeedsSelectorSweepBeforeReregistration() throws Exception {
        // JDK selector contract behind the reactor command-batch fence:
        // cancel() is lazy; same-channel register() before selectNow() fails.
        var pipe = java.nio.channels.Pipe.open();
        try (var selector = java.nio.channels.Selector.open();
             var source = pipe.source();
             var sink = pipe.sink()) {
            source.configureBlocking(false);
            var old = source.register(selector, SelectionKey.OP_READ);
            old.cancel();
            assertThrows(java.nio.channels.CancelledKeyException.class,
                    () -> source.register(selector, SelectionKey.OP_READ));
            selector.selectNow();
            assertTrue(source.register(selector, SelectionKey.OP_READ).isValid());
        }
    }

    @Test void repeatedSameSocketReadRearmsAfterCancelledKeyPurge() throws Exception {
        // A producer can enqueue the next watch as soon as a Future settles.
        // Every next command must register AFTER the old cancelled key is
        // swept, including under rapid readable/not-readable transitions.
        try (var group = new NioReadinessGroup(1, 64, 64);
             var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            try (var peer = new Socket(InetAddress.getLoopbackAddress(), port)) {
                SocketChannel accepted = null;
                for (int attempt = 0; attempt < 1000 && accepted == null; attempt++) {
                    accepted = listener.accept();
                    if (accepted == null) Thread.sleep(1); // trusted test driver only
                }
                assertNotNull(accepted);
                try (var socket = accepted) {
                    socket.configureBlocking(false);
                    for (int round = 0; round < 64; round++) {
                        var future = group.readAsync(socket, 1);
                        peer.getOutputStream().write(round);
                        assertArrayEquals(new byte[]{(byte) round},
                                future.get(3, TimeUnit.SECONDS),
                                "same socket must re-register after previous key cancellation");
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while ((group.activeReadLeases() != 0 || group.pendingCount() != 0)
                                && System.nanoTime() < deadline)
                            Thread.sleep(1);
                        assertEquals(0, group.activeReadLeases());
                        assertEquals(0, group.pendingCount());
                    }
                    peer.setSoTimeout(3000);
                    for (int round = 0; round < 32; round++) {
                        var outbound = group.writeAsync(socket, new byte[]{(byte) round});
                        assertEquals(1, outbound.get(3, TimeUnit.SECONDS));
                        assertEquals(round, peer.getInputStream().read(),
                                "same channel must re-arm OP_WRITE after key cancellation");
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while ((group.activeWriteLeases() != 0 || group.pendingCount() != 0)
                                && System.nanoTime() < deadline)
                            Thread.sleep(1);
                        assertEquals(0, group.activeWriteLeases());
                        assertEquals(0, group.pendingCount());
                    }
                }
            }
            assertEquals(1, group.reactorCount(),
                    "re-armed TCP reads/writes must use one selector, not per-I/O workers");
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test void vmSelectorTcpReadEnforcesLeaseAndRearmsAfterCancellation() throws Exception {
        try (var group = new NioReadinessGroup(1, 32, 64);
             var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            try (var peer = new Socket(InetAddress.getLoopbackAddress(), port)) {
                SocketChannel channel = null;
                for (int attempt = 0; attempt < 1500 && channel == null; ++attempt) {
                    channel = listener.accept();
                    if (channel == null) Thread.sleep(1); // host driver only
                }
                assertNotNull(channel);
                try (var incoming = channel) {
                    incoming.configureBlocking(false);
                    var first = group.readAsync(incoming, 8);
                    var overlapping = group.readAsync(incoming, 8);
                    var denied = assertThrows(ExecutionException.class,
                            () -> overlapping.get(2, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class, denied.getCause());
                    assertEquals(1, group.activeReadLeases());
                    // TCP is a byte stream, not a message transport: use
                    // one byte so no test assumes a full multi-byte read.
                    peer.getOutputStream().write(10);
                    assertArrayEquals(new byte[] {10}, first.get(3, TimeUnit.SECONDS));

                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (group.activeReadLeases() != 0 && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    assertEquals(0, group.activeReadLeases());

                    var cancelled = group.readAsync(incoming, 2);
                    assertTrue(cancelled.cancel(false),
                            "cancellation before kernel claim must be accepted");
                    while ((group.activeReadLeases() != 0 || group.pendingCount() != 0)
                            && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    assertEquals(0, group.activeReadLeases());
                    assertEquals(0, group.pendingCount());

                    var next = group.readAsync(incoming, 1);
                    peer.getOutputStream().write(42);
                    assertArrayEquals(new byte[] {42}, next.get(3, TimeUnit.SECONDS));
                    assertEquals(1, group.reactorCount(),
                            "read cancellation does not provision more selectors");
                }
            }
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            assertEquals(0, group.activeReadLeases());
            assertEquals(0, group.pendingNotifications());
        }
    }

    @Test void vmSelectorTcpWriteSnapshotsBytesAndProtectsDuplexLease() throws Exception {
        try (var group = new NioReadinessGroup(1, 32, 64);
             var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            try (var peer = new Socket(InetAddress.getLoopbackAddress(), port)) {
                peer.setSoTimeout(3000);
                SocketChannel accepted = null;
                for (int n = 0; n < 1000 && accepted == null; ++n) {
                    accepted = listener.accept();
                    if (accepted == null) Thread.sleep(1);
                }
                assertNotNull(accepted);
                try (var incoming = accepted) {
                    incoming.configureBlocking(false);
                    var pendingRead = group.readAsync(incoming, 1);
                    var overlappingWrite = group.writeAsync(incoming, new byte[]{99});
                    var denied = assertThrows(ExecutionException.class,
                            () -> overlappingWrite.get(2, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class, denied.getCause());
                    var zeroWhileBusy = group.writeAsync(incoming, new byte[0]);
                    var zeroDenied = assertThrows(ExecutionException.class,
                            () -> zeroWhileBusy.get(2, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class, zeroDenied.getCause());
                    assertTrue(pendingRead.cancel(false));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ((group.activeReadLeases() != 0 || group.pendingCount() != 0)
                            && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    assertEquals(0, group.activeReadLeases());
                    assertEquals(0, group.writeAsync(incoming, new byte[0])
                            .get(2, TimeUnit.SECONDS));

                    byte[] mutable = new byte[]{4, 5, 6};
                    var write = group.writeAsync(incoming, mutable);
                    mutable[0] = 99; // mutable host input cannot cross admission
                    assertEquals(3, write.get(3, TimeUnit.SECONDS));
                    assertEquals(4, peer.getInputStream().read());
                    assertEquals(5, peer.getInputStream().read());
                    assertEquals(6, peer.getInputStream().read());

                    while (group.activeWriteLeases() != 0 && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    assertEquals(0, group.activeWriteLeases());
                    var response = group.readAsync(incoming, 1);
                    peer.getOutputStream().write(7);
                    assertArrayEquals(new byte[]{7}, response.get(3, TimeUnit.SECONDS));
                    assertEquals(1, group.reactorCount(),
                            "host TCP read and write share a single selector");
                }
            }
            var closedChannel = SocketChannel.open();
            closedChannel.configureBlocking(false);
            closedChannel.close();
            var closedWrite = group.writeAsync(closedChannel, new byte[0]);
            var closedFailure = assertThrows(ExecutionException.class,
                    () -> closedWrite.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, closedFailure.getCause());
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            assertEquals(0, group.activeWriteLeases());
            assertEquals(0, group.activeReadLeases());
        }
    }

    @Test void vmSelectorTcpReadReportsEofWithoutBlockingCarrier() throws Exception {
        try (var group = new NioReadinessGroup(1, 8, 8);
             var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            try (var peer = new Socket(InetAddress.getLoopbackAddress(), port)) {
                SocketChannel accepted = null;
                for (int n = 0; n < 1000 && accepted == null; ++n) {
                    accepted = listener.accept();
                    if (accepted == null) Thread.sleep(1);
                }
                assertNotNull(accepted);
                try (var incoming = accepted) {
                    incoming.configureBlocking(false);
                    var eof = group.readAsync(incoming, 32);
                    peer.shutdownOutput();
                    assertArrayEquals(new byte[0], eof.get(3, TimeUnit.SECONDS));
                }
            }
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test void concurrentAcceptCannotOvertakeBlockedTerminalSubscriber() throws Exception {
        // The selector retires a watch before the Future's subscribers finish.
        // A second accept must NOT steal the listener during that interval.
        try (var group = new NioReadinessGroup(1, 32, 64);
             var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            var callbackEntered = new java.util.concurrent.CountDownLatch(1);
            var releaseCallback = new java.util.concurrent.CountDownLatch(1);
            var original = group.acceptAsync(listener);
            original.whenCompleteRuntime((socket, failure) -> {
                callbackEntered.countDown();
                try { releaseCallback.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            try {
                try (var peer = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
                    assertEquals(0, group.pendingCount(),
                            "readiness watch already retired while subscriber is blocked");
                    assertEquals(1, group.activeAcceptLeases(),
                            "listener still belongs to first operation");
                    var duplicate = group.acceptAsync(listener);
                    var denied = assertThrows(ExecutionException.class,
                            () -> duplicate.get(2, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class, denied.getCause());
                    assertEquals(1, group.activeAcceptLeases());
                    try (var socket = original.get(2, TimeUnit.SECONDS)) {
                        assertFalse(socket.isBlocking());
                    }
                }
            } finally {
                releaseCallback.countDown();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (group.activeAcceptLeases() != 0 && System.nanoTime() < deadline)
                Thread.sleep(1); // JUnit driver only
            assertEquals(0, group.activeAcceptLeases(),
                    "terminal callback drain must release listener lease");
            var next = group.acceptAsync(listener);
            try (var peer = new Socket(InetAddress.getLoopbackAddress(), port);
                 var socket = next.get(3, TimeUnit.SECONDS)) {
                assertTrue(socket.isOpen(), "listener can accept again after drain");
            }
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            assertEquals(0, group.activeAcceptLeases());
        }
    }

    @Test void cancelledTcpAcceptReleasesWatchAndAllowsRearm() throws Exception {
        // The VM-owned selector accepts one-shot readiness without creating a
        // listener-specific I/O thread. Cancellation must reclaim the watch
        // before the same caller-owned listener can be armed again.
        try (var group = new NioReadinessGroup(1, 32, 64);
             var listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = ((InetSocketAddress) listener.getLocalAddress()).getPort();
            for (int round = 0; round < 48; ++round) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                var abandoned = group.acceptAsync(listener);
                assertTrue(abandoned.cancel(false));
                while ((group.pendingCount() != 0 || group.activeAcceptLeases() != 0)
                        && System.nanoTime() < deadline)
                    Thread.sleep(1); // host test driver only
                assertEquals(0, group.pendingCount(),
                        "cancelled one-shot accept must retire the readiness watch");
                assertEquals(0, group.activeAcceptLeases(),
                        "cancelled accept must drain its callback before rearming");
                assertEquals(1, group.reactorCount());
            }
            var rearmed = group.acceptAsync(listener);
            try (var peer = new Socket(InetAddress.getLoopbackAddress(), port);
                 var accepted = rearmed.get(3, TimeUnit.SECONDS)) {
                assertFalse(accepted.isBlocking());
                assertTrue(accepted.isOpen());
            }
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            assertEquals(0, group.pendingNotifications());
        }
    }

    @Test void selectorStopLatchFollowsCompletionDrainFence() throws Exception {
        // Inspect the internal publication order directly after first demand.
        // Construction itself must remain thread-free.
        try (var group = new NioReadinessGroup(1, 8, 8)) {
            assertEquals(0, group.reactorCount());
            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                var pending = group.watch(pipe.source(), SelectionKey.OP_READ);
                assertEquals(1, group.reactorCount());
                var reactors = NioReadinessGroup.class.getDeclaredField("reactors");
                reactors.setAccessible(true);
                Object reactor = ((java.util.List<?>) reactors.get(group)).getFirst();
                var stop = reactor.getClass().getDeclaredField("stopped");
                stop.setAccessible(true);
                var stopped = (java.util.concurrent.CountDownLatch) stop.get(reactor);
                var completion = NioReadinessGroup.class.getDeclaredField("completionExecutor");
                completion.setAccessible(true);
                var executor = (java.util.concurrent.ThreadPoolExecutor) completion.get(group);
                group.close();
                assertTrue(stopped.await(3, TimeUnit.SECONDS));
                assertTrue(executor.isShutdown(),
                        "completion executor shutdown must precede reactor stop-latch publication");
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
                assertTrue(pending.isDone());
            } finally {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }

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

    @Test void reactorShutdownWaitsForOutstandingCompletionCallbacks() throws Exception {
        try (var group = new NioReadinessGroup(1, 1, 8)) {
            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                var entered = new java.util.concurrent.CountDownLatch(1);
                var release = new java.util.concurrent.CountDownLatch(1);
                var watcher = group.watch(pipe.source(), SelectionKey.OP_READ);
                watcher.whenCompleteRuntime((events, failure) -> {
                    entered.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
                try {
                    pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[]{1}));
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    group.close();
                    assertFalse(group.awaitStopped(50, TimeUnit.MILLISECONDS),
                            "selector shutdown alone is not notification quiescence");
                } finally {
                    release.countDown();
                }
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
                assertEquals(0, group.pendingNotifications());
            } finally {
                pipe.source().close();
                pipe.sink().close();
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

    @Test void lateCancellationAfterStopFenceDoesNotRetainChannel() throws Exception {
        try (var group = new NioReadinessGroup(1, 8, 8)) {
            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                var watch = group.watch(pipe.source(), SelectionKey.OP_READ);
                // Set the reactor's stop fence without waking its selector.
                // This deterministic race models a cancellation arriving
                // after shutdown owns the terminal queues, before retirement.
                var reactorsField = NioReadinessGroup.class.getDeclaredField("reactors");
                reactorsField.setAccessible(true);
                Object reactor = ((java.util.List<?>) reactorsField.get(group)).getFirst();
                var stopField = reactor.getClass().getDeclaredField("stopping");
                stopField.setAccessible(true);
                ((java.util.concurrent.atomic.AtomicBoolean) stopField.get(reactor)).set(true);
                assertTrue(watch.cancel(false));
                var queueField = reactor.getClass().getDeclaredField("cancellations");
                queueField.setAccessible(true);
                var queue = (java.util.Queue<?>) queueField.get(reactor);
                assertTrue(queue.isEmpty(),
                        "terminal cancellation must not resurrect a retired watch");
                group.close();
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
                assertTrue(queue.isEmpty());
                assertEquals(0, group.pendingCount());
                assertEquals(0, group.pendingNotifications());
            } finally {
                pipe.source().close();
                pipe.sink().close();
            }
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

    @Test void saturatedBoundSchedulerStagesAndDrainsWithoutStrandingReservations()
            throws Exception {
        var saturated = new java.util.concurrent.atomic.AtomicBoolean(true);
        var rejected = new java.util.concurrent.CountDownLatch(1);
        var delivered = new java.util.concurrent.CountDownLatch(1);
        try (var carrier = java.util.concurrent.Executors.newSingleThreadExecutor(
                    task -> new Thread(task, "test-actor-capacity-carrier"));
             var group = new NioReadinessGroup(1, 8, 8)) {
            java.util.concurrent.Executor dispatcher = task -> {
                if (saturated.get()) {
                    rejected.countDown();
                    throw new java.util.concurrent.RejectedExecutionException("actor queue full");
                }
                carrier.execute(task);
            };
            group.bindVmCompletionExecutor(dispatcher);
            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                var readiness = group.watch(pipe.source(), SelectionKey.OP_READ);
                readiness.whenCompleteRuntime((events, failure) -> delivered.countDown());
                pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[] {42}));
                assertTrue(rejected.await(3, TimeUnit.SECONDS));
                group.close();
                assertFalse(group.awaitStopped(100, TimeUnit.MILLISECONDS),
                        "shutdown must not report quiescence before accepted notifications run");
                assertEquals(1, group.pendingNotifications());
                assertEquals(0, group.completionCarrierCount());
                saturated.set(false);
                group.retryStagedNotifications();
                assertTrue(delivered.await(3, TimeUnit.SECONDS));
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
                assertEquals(0, group.pendingNotifications());
                assertEquals(0, group.reactorCount());
            } finally {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }

    @Test void inlineHostExecutorNeverRunsOnReactorOrReplaysReentrantCompletion()
            throws Exception {
        var rejectedReactorInline = new java.util.concurrent.CountDownLatch(2);
        var firstCount = new java.util.concurrent.atomic.AtomicInteger();
        var secondCount = new java.util.concurrent.atomic.AtomicInteger();
        var callerOnly = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.Executor inline = task -> {
            if (Thread.currentThread().getName().startsWith("ores-nio-reactor-"))
                rejectedReactorInline.countDown();
            task.run();
        };
        try (var group = new NioReadinessGroup(1, 8, 8)) {
            group.bindVmCompletionExecutor(inline);
            var first = java.nio.channels.Pipe.open();
            var second = java.nio.channels.Pipe.open();
            try {
                first.source().configureBlocking(false);
                second.source().configureBlocking(false);
                OresFuture<Integer> a = group.watch(first.source(), SelectionKey.OP_READ);
                OresFuture<Integer> b = group.watch(second.source(), SelectionKey.OP_READ);
                a.whenCompleteRuntime((events, failure) -> {
                    firstCount.incrementAndGet();
                    callerOnly.compareAndSet(true,
                            !Thread.currentThread().getName().startsWith("ores-nio-reactor-"));
                    // Reentrant direct delivery must not replay the head.
                    group.retryStagedNotifications();
                });
                b.whenCompleteRuntime((events, failure) -> {
                    secondCount.incrementAndGet();
                    callerOnly.compareAndSet(true,
                            !Thread.currentThread().getName().startsWith("ores-nio-reactor-"));
                });
                first.sink().write(java.nio.ByteBuffer.wrap(new byte[] {1}));
                second.sink().write(java.nio.ByteBuffer.wrap(new byte[] {2}));
                assertTrue(rejectedReactorInline.await(3, TimeUnit.SECONDS),
                        "inline selector delivery must fail closed");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (group.stagedNotificationCount() < 2
                        && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(2, group.stagedNotificationCount(),
                        "both reactor notifications must remain staged before host retry");
                assertFalse(a.isDone(), "reactor must not settle arbitrary subscribers");
                assertFalse(b.isDone(), "reactor must not settle arbitrary subscribers");

                // An explicit trusted host thread may retry its own
                // synchronous executor, and callbacks may reenter retry.
                group.retryStagedNotifications();
                assertEquals(1, firstCount.get());
                assertEquals(1, secondCount.get());
                assertTrue(callerOnly.get());
                assertEquals(0, group.pendingNotifications(),
                        "each accepted readiness reservation must drain once");
                group.close();
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            } finally {
                first.source().close();
                first.sink().close();
                second.source().close();
                second.sink().close();
            }
        }
    }

    @Test void vmBoundCompletionsUseExistingExecutorAndNoNioWorkers() throws Exception {
        // The single explicit executor represents an actor carrier; the
        // NIO manager may not create any dedicated completion carrier.
        var delivered = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var host = java.util.concurrent.Executors.newSingleThreadExecutor(
                    task -> new Thread(task, "test-actor-carrier"));
             var group = new NioReadinessGroup(1, 8, 8)) {
            group.bindVmCompletionExecutor(host);
            assertTrue(group.usesVmCompletionCarriers());
            assertEquals(0, group.completionCarrierCount());
            assertEquals(0, group.reactorCount());

            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                var watch = group.watch(pipe.source(), SelectionKey.OP_READ);
                watch.whenCompleteRuntime((value, error) ->
                        delivered.set(Thread.currentThread().getName()));
                assertEquals(1, group.reactorCount());
                assertThrows(IllegalStateException.class,
                        () -> group.bindVmCompletionExecutor(host),
                        "the completion execution domain cannot change after admission");
                pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[] {42}));
                assertTrue(watch.get(3, TimeUnit.SECONDS) != 0);
                group.close();
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
                assertEquals("test-actor-carrier", delivered.get(),
                        "Future subscribers must not run on the selector or CONTROL");
                assertEquals(0, group.completionCarrierCount());
            } finally {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }

    @Test void neverUsedGroupStartsAndStopsWithZeroReactorThreads() throws Exception {
        var group = new NioReadinessGroup(4, 16, 16);
        assertEquals(0, group.reactorCount());
        assertEquals(0, group.completionCarrierCount());
        group.close();
        assertTrue(group.awaitStopped(2, TimeUnit.SECONDS));
        assertEquals(0, group.reactorCount());
        assertEquals(0, group.completionCarrierCount());
    }

    @Test void inlineExecutorRejectingAfterDeliveryDoesNotReplayNotification()
            throws Exception {
        var selectorAttempt = new java.util.concurrent.CountDownLatch(1);
        var completions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor hostile = task -> {
            boolean selector = Thread.currentThread().getName()
                    .startsWith("ores-nio-reactor-");
            if (selector) selectorAttempt.countDown();
            task.run(); // rejected before a claim if this is the selector
            // An executor can propagate a callback's rejection or reject
            // after running it; neither may trigger a second settlement.
            throw new java.util.concurrent.RejectedExecutionException(
                    "executor rejected after invoking the task");
        };
        try (var group = new NioReadinessGroup(1, 8, 8)) {
            group.bindVmCompletionExecutor(hostile);
            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                var future = group.watch(pipe.source(), SelectionKey.OP_READ);
                future.whenCompleteRuntime((events, failure) ->
                        completions.incrementAndGet());
                pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[] {1}));
                assertTrue(selectorAttempt.await(3, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (group.stagedNotificationCount() == 0
                        && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(1, group.stagedNotificationCount());
                group.retryStagedNotifications(); // trusted caller, inline
                assertTrue(future.isDone());
                assertEquals(1, completions.get());
                assertEquals(0, group.stagedNotificationCount());
                assertEquals(0, group.pendingNotifications());
                group.retryStagedNotifications();
                assertEquals(1, completions.get());
                group.close();
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            } finally {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }

    @Test void executorAcceptThenRejectCannotDuplicateOrStrandNotification()
            throws Exception {
        // This executor violates the conventional rejection contract by
        // queueing a task before throwing. The scheduler must still preserve
        // at-most-once delivery and release the reservation exactly once.
        var queued = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        var firstRejected = new java.util.concurrent.CountDownLatch(1);
        var submissions = new java.util.concurrent.atomic.AtomicInteger();
        var completions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor hostile = task -> {
            queued.add(task);
            if (submissions.getAndIncrement() == 0) {
                firstRejected.countDown();
                throw new java.util.concurrent.RejectedExecutionException(
                        "already scheduled, then rejected");
            }
        };
        try (var group = new NioReadinessGroup(1, 8, 8)) {
            group.bindVmCompletionExecutor(hostile);
            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                var future = group.watch(pipe.source(), SelectionKey.OP_READ);
                future.whenCompleteRuntime((events, failure) ->
                        completions.incrementAndGet());
                pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[] {2}));
                assertTrue(firstRejected.await(3, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (group.stagedNotificationCount() == 0
                        && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(1, group.stagedNotificationCount());
                // Replay the same delivery token while its first accepted
                // copy has not yet run, then execute both copies.
                group.retryStagedNotifications();
                assertEquals(2, queued.size());
                Runnable first = queued.poll();
                Runnable duplicate = queued.poll();
                assertNotNull(first);
                assertNotNull(duplicate);
                first.run();
                duplicate.run();
                assertTrue(future.isDone());
                assertEquals(1, completions.get());
                assertEquals(0, group.pendingNotifications());
                assertEquals(0, group.stagedNotificationCount());
                group.close();
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            } finally {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }

    @Test void lateAcceptedExecutorTaskCleansItsRejectedQueueEntry()
            throws Exception {
        var queued = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        var rejected = new java.util.concurrent.CountDownLatch(1);
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor hostile = task -> {
            queued.add(task);
            rejected.countDown();
            throw new java.util.concurrent.RejectedExecutionException(
                    "task accepted but executor lied about rejection");
        };
        try (var group = new NioReadinessGroup(1, 8, 8)) {
            group.bindVmCompletionExecutor(hostile);
            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                var future = group.watch(pipe.source(), SelectionKey.OP_READ);
                future.whenCompleteRuntime((value, failure) ->
                        notifications.incrementAndGet());
                pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[] {3}));
                assertTrue(rejected.await(3, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (group.stagedNotificationCount() == 0
                        && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(1, group.stagedNotificationCount());
                // Execute the already-accepted original instead of retrying
                // first; it must remove its own stale queued retry entry.
                Runnable accepted = queued.poll();
                assertNotNull(accepted);
                accepted.run();
                assertEquals(1, notifications.get());
                assertEquals(0, group.stagedNotificationCount());
                assertEquals(0, group.pendingNotifications());
                group.close();
                assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
            } finally {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }

    @Test void thousandsOfInlineReentrantNotificationsDrainWithoutStackRecursion()
            throws Exception {
        // Dispatch is intentionally private: use reflection only to
        // synthesize completion records without opening thousands of FDs.
        var publish = NioReadinessGroup.class.getDeclaredMethod(
                "dispatchNotification", Runnable.class);
        publish.setAccessible(true);
        try (var group = new NioReadinessGroup(1, 8, 8)) {
            group.bindVmCompletionExecutor(Runnable::run);
            var callbacks = new java.util.concurrent.atomic.AtomicInteger();
            Runnable[] chained = new Runnable[1];
            chained[0] = () -> {
                if (callbacks.incrementAndGet() < 4_000) {
                    try {
                        publish.invoke(group, chained[0]);
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError("nested notification publication failed", failure);
                    }
                }
            };
            publish.invoke(group, chained[0]);
            assertEquals(4_000, callbacks.get());
            assertEquals(0, group.stagedNotificationCount());
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test void slowInlineHostCallbackDoesNotBlockCompetingNotificationAdmission()
            throws Exception {
        var publish = NioReadinessGroup.class.getDeclaredMethod(
                "dispatchNotification", Runnable.class);
        publish.setAccessible(true);
        var callbackEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseCallback = new java.util.concurrent.CountDownLatch(1);
        var followupDelivered = new java.util.concurrent.CountDownLatch(1);
        try (var group = new NioReadinessGroup(1, 8, 8)) {
            group.bindVmCompletionExecutor(Runnable::run);
            Thread host = new Thread(() -> {
                try {
                    publish.invoke(group, (Runnable) () -> {
                        callbackEntered.countDown();
                        try {
                            releaseCallback.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }, "slow-inline-host-callback");
            host.start();
            try {
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
                long started = System.nanoTime();
                publish.invoke(group, (Runnable) followupDelivered::countDown);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started);
                assertTrue(elapsedMillis < 500,
                        "notification ingress waited behind an inline subscriber: "
                                + elapsedMillis + "ms");
            } finally {
                releaseCallback.countDown();
                host.join(3000);
            }
            assertFalse(host.isAlive());
            assertTrue(followupDelivered.await(3, TimeUnit.SECONDS));
            assertEquals(0, group.stagedNotificationCount());
            group.close();
            assertTrue(group.awaitStopped(3, TimeUnit.SECONDS));
        }
    }

    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(0, 10, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(5, 10, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(1, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new NioReadinessGroup(1, 10, 7));
    }
}
