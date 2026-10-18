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
    // A real selectable channel with a test-owned close barrier: no reliance
    // on sleeps or a lucky race against the OS's normally fast close().
    private static final class ClosingProbeServer extends java.nio.channels.ServerSocketChannel {
        final java.util.concurrent.CountDownLatch closeEntered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finishClose =
                new java.util.concurrent.CountDownLatch(1);
        private final boolean injectFailure;

        ClosingProbeServer(boolean injectFailure) {
            super(java.nio.channels.spi.SelectorProvider.provider());
            this.injectFailure = injectFailure;
        }

        @Override protected void implCloseSelectableChannel() throws java.io.IOException {
            closeEntered.countDown();
            try {
                if (!finishClose.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("test server close did not receive release");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("test server close was interrupted", interrupted);
            }
            if (injectFailure)
                throw new java.io.IOException("injected server descriptor close failure");
        }

        @Override protected void implConfigureBlocking(boolean blocked) { }
        @Override public java.nio.channels.ServerSocketChannel bind(
                java.net.SocketAddress address, int backlog) { return this; }
        @Override public <T> java.nio.channels.ServerSocketChannel setOption(
                java.net.SocketOption<T> option, T value) { return this; }
        @Override public <T> T getOption(java.net.SocketOption<T> option) { return null; }
        @Override public java.util.Set<java.net.SocketOption<?>> supportedOptions() {
            return java.util.Set.of();
        }
        @Override public java.net.ServerSocket socket() {
            throw new UnsupportedOperationException("close-fence test server");
        }
        @Override public java.nio.channels.SocketChannel accept() {
            throw new UnsupportedOperationException("close-fence test server");
        }
        @Override public java.net.SocketAddress getLocalAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 12345);
        }
    }

    private static void verifyServerDescriptorCloseFence(boolean failClose) throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        ClosingProbeServer descriptor = new ClosingProbeServer(failClose);
        VmNioTcpListener listener = new VmNioTcpListener(
                vm.nioReadiness(), descriptor, 1, 1);
        Thread closer = Thread.ofPlatform().start(listener::close);
        try {
            assertTrue(descriptor.closeEntered.await(3, TimeUnit.SECONDS),
                    "close never attempted to retire server FD");
            assertFalse(listener.isTerminated(),
                    "listener reports termination before physical server close returns");
            var completion = listener.closeAsync();
            assertFalse(completion.isDone(),
                    "listener close Future crossed the descriptor-retirement barrier");
        } finally {
            descriptor.finishClose.countDown();
            closer.join(3000);
            vm.close();
        }
        assertFalse(closer.isAlive(), "server close thread stranded");
        if (failClose) {
            var failure = assertThrows(ExecutionException.class,
                    () -> listener.closeAsync().get(3, TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("injected server descriptor"));
            assertFalse(listener.isTerminated(),
                    "physical descriptor error must not be reported as clean termination");
        } else {
            listener.closeAsync().get(3, TimeUnit.SECONDS);
            assertTrue(listener.isTerminated(),
                    "listener becomes quiescent after close actually returns");
        }
    }

    @Test void listenerCloseFutureAndTerminatedWaitForPhysicalServerClose() throws Exception {
        verifyServerDescriptorCloseFence(false);
    }

    @Test void failedServerDescriptorCloseFailsTheExportedCloseFuture() throws Exception {
        verifyServerDescriptorCloseFence(true);
    }

    @Test void twoListenersAndGuestShapeEchoShareTheSingleVmSelector() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var first = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 16, 4, 16);
             var second = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 16, 4, 16)) {
            assertEquals(0, vm.nioReactorCount(),
                    "opening listeners alone must not start a selector thread");
            var firstAccept = first.acceptAsync();
            var secondAccept = second.acceptAsync();
            assertEquals(1, vm.nioReactorCount(),
                    "first accepted readiness watch lazily starts reactor #0");
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

    @Test void connectionCloseFutureWaitsForReadSubscriberDrain() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 8, 1, 2)) {
            var accepting = listener.acceptAsync();
            try (var peer = new Socket(InetAddress.getByName("127.0.0.1"), listener.localPort())) {
                var connection = accepting.get(3, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (listener.pendingOperations() != 0 && System.nanoTime() < deadline)
                    Thread.sleep(1);
                var read = connection.readBytesAsync(1);
                var subscriberEntered = new java.util.concurrent.CountDownLatch(1);
                var releaseSubscriber = new java.util.concurrent.CountDownLatch(1);
                read.whenCompleteRuntime((value, failure) -> {
                    subscriberEntered.countDown();
                    try { releaseSubscriber.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                peer.getOutputStream().write(55);
                assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS));
                assertEquals(1, listener.pendingOperations(),
                        "operation credit is retained while terminal subscriber is running");
                var closed = connection.closeAsync();
                assertFalse(closed.isDone(),
                        "connection close Future must not overtake admitted callback drain");
                releaseSubscriber.countDown();
                assertEquals(List.of(55L), read.get(3, TimeUnit.SECONDS));
                closed.get(3, TimeUnit.SECONDS);
                assertEquals(0, listener.pendingOperations());
            }
        } finally {
            vm.close();
        }
    }

    @Test void listenerCloseFutureWaitsForConnectionOperationDrain() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 8, 1, 2);
        try {
            var accepting = listener.acceptAsync();
            try (var peer = new Socket(InetAddress.getByName("127.0.0.1"), listener.localPort())) {
                var connection = accepting.get(3, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (listener.pendingOperations() != 0 && System.nanoTime() < deadline)
                    Thread.sleep(1);
                var read = connection.readBytesAsync(1);
                var subscriberEntered = new java.util.concurrent.CountDownLatch(1);
                var releaseSubscriber = new java.util.concurrent.CountDownLatch(1);
                read.whenCompleteRuntime((value, failure) -> {
                    subscriberEntered.countDown();
                    try { releaseSubscriber.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                peer.getOutputStream().write(56);
                assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS));
                var closed = listener.closeAsync();
                assertFalse(closed.isDone(),
                        "listener close Future must wait for admitted connection callback drain");
                releaseSubscriber.countDown();
                assertEquals(List.of(56L), read.get(3, TimeUnit.SECONDS));
                closed.get(3, TimeUnit.SECONDS);
                assertTrue(listener.isTerminated());
                assertEquals(0, listener.pendingOperations());
            }
        } finally {
            listener.close();
            vm.close();
        }
    }

    @Test void listenerCloseRevokesUnacceptedFutureWithoutLeakingOperations() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 8, 2, 1)) {
            var pending = listener.acceptAsync();
            assertEquals(1, listener.pendingOperations());
            listener.closeAsync().get(3, TimeUnit.SECONDS);
            assertTrue(listener.isTerminated());
            assertTrue(pending.isDone(), "closing listener must settle a pending accept");
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> pending.get(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((listener.pendingOperations() != 0
                    || vm.nioReadiness().activeAcceptLeases() != 0)
                    && System.nanoTime() < deadline)
                Thread.sleep(1);
            assertEquals(0, listener.pendingOperations(),
                    "closing listener must return its reserved accept credit");
            assertEquals(0, vm.nioReadiness().activeAcceptLeases(),
                    "close must fence the old listener key before VM teardown");
        } finally {
            vm.close();
        }
    }

    @Test void connectionCloseRevokesPendingReadAndReturnsListenerCapacity() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 8, 1, 1)) {
            var accept = listener.acceptAsync();
            try (var peer = new Socket(InetAddress.getByName("127.0.0.1"), listener.localPort())) {
                var connection = accept.get(3, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (listener.pendingOperations() != 0 && System.nanoTime() < deadline)
                    Thread.sleep(1);
                assertEquals(0, listener.pendingOperations());
                var pending = connection.readBytesAsync(1);
                assertFalse(pending.isDone(), "no socket bytes were sent");
                assertEquals(1, listener.pendingOperations());
                connection.closeAsync().get(3, TimeUnit.SECONDS);
                assertTrue(pending.isDone(), "close must revoke or fail pending read");
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> pending.get(3, TimeUnit.SECONDS));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((listener.pendingOperations() != 0
                        || vm.nioReadiness().activeReadLeases() != 0)
                        && System.nanoTime() < deadline)
                    Thread.sleep(1);
                assertEquals(0, listener.pendingOperations(), "terminal read must release credit");
                assertEquals(0, vm.nioReadiness().activeReadLeases(),
                        "closed connection must release kernel watch ownership");
                assertEquals(1, vm.nioReactorCount(),
                        "connection retirement cannot create a second selector");
            }
        } finally {
            vm.close();
        }
    }

    @Test void invalidReadBoundsDoNotConsumePendingCapacity() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 8, 1, 1)) {
            var accepting = listener.acceptAsync();
            try (var peer = new Socket(InetAddress.getByName("127.0.0.1"), listener.localPort())) {
                var connection = accepting.get(3, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (listener.pendingOperations() != 0 && System.nanoTime() < deadline)
                    Thread.sleep(1);
                assertEquals(0, listener.pendingOperations());
                for (int invalid : new int[] {0, -1, 65537}) {
                    var rejected = connection.readBytesAsync(invalid);
                    var failure = assertThrows(ExecutionException.class,
                            () -> rejected.get(2, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                    assertEquals(0, listener.pendingOperations(),
                            "invalid read bounds must fail before admission");
                }
                var read = connection.readBytesAsync(1);
                peer.getOutputStream().write(9);
                assertEquals(List.of(9L), read.get(3, TimeUnit.SECONDS));
            }
        } finally {
            vm.close();
        }
    }

    @Test void concurrentListenerAndConnectionCloseAlwaysQuiesce() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            for (int round = 0; round < 24; round++) {
                try (var listener = VmNioTcpListener.loopback(
                        vm.nioReadiness(), 0, 8, 1, 8)) {
                    var accepting = listener.acceptAsync();
                    try (var peer = new Socket(
                            InetAddress.getByName("127.0.0.1"), listener.localPort())) {
                        var connection = accepting.get(3, TimeUnit.SECONDS);
                        // Leave an actual VM selector read outstanding while
                        // both teardown owners race: close must retire the FD
                        // and drain terminal read credit exactly once.
                        var pendingRead = connection.readBytesAsync(1);
                        var go = new java.util.concurrent.CountDownLatch(1);
                        var failures = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                        Thread a = Thread.ofPlatform().start(() -> {
                            try { go.await(); connection.close(); }
                            catch (Throwable t) { failures.compareAndSet(null, t); }
                        });
                        Thread b = Thread.ofPlatform().start(() -> {
                            try { go.await(); listener.close(); }
                            catch (Throwable t) { failures.compareAndSet(null, t); }
                        });
                        go.countDown();
                        try {
                            listener.closeAsync().get(3, TimeUnit.SECONDS);
                        } finally {
                            a.join(3000);
                            b.join(3000);
                        }
                        assertFalse(a.isAlive(), "connection closer blocked");
                        assertFalse(b.isAlive(), "listener closer blocked");
                        assertNull(failures.get(), "parallel close failed");
                        assertTrue(listener.isTerminated());
                        assertTrue(pendingRead.isDone(),
                                "pending read stranded by concurrent shutdown");
                        connection.closeAsync().get(3, TimeUnit.SECONDS);
                        assertEquals(0, listener.pendingOperations());
                    }
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
    private static final class ClosingProbeSocket extends java.nio.channels.SocketChannel {
        final java.util.concurrent.CountDownLatch closeEntered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseClose =
                new java.util.concurrent.CountDownLatch(1);
        private final boolean injectFailure;

        ClosingProbeSocket(boolean injectFailure) {
            super(java.nio.channels.spi.SelectorProvider.provider());
            this.injectFailure = injectFailure;
        }

        @Override protected void implCloseSelectableChannel() throws java.io.IOException {
            closeEntered.countDown();
            try {
                if (!releaseClose.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("socket-close probe timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("socket-close probe interrupted", interrupted);
            }
            if (injectFailure)
                throw new java.io.IOException("injected socket descriptor close failure");
        }
        @Override protected void implConfigureBlocking(boolean blocked) { }
        @Override public java.nio.channels.SocketChannel bind(java.net.SocketAddress address) {
            return this;
        }
        @Override public <T> java.nio.channels.SocketChannel setOption(
                java.net.SocketOption<T> option, T value) { return this; }
        @Override public <T> T getOption(java.net.SocketOption<T> option) { return null; }
        @Override public java.util.Set<java.net.SocketOption<?>> supportedOptions() {
            return java.util.Set.of();
        }
        @Override public java.nio.channels.SocketChannel shutdownInput() { return this; }
        @Override public java.nio.channels.SocketChannel shutdownOutput() { return this; }
        @Override public java.net.Socket socket() {
            throw new UnsupportedOperationException("socket-close probe");
        }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(java.net.SocketAddress address) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public java.net.SocketAddress getRemoteAddress() { return null; }
        @Override public int read(java.nio.ByteBuffer target) { return -1; }
        @Override public long read(java.nio.ByteBuffer[] target, int offset, int length) {
            return -1;
        }
        @Override public int write(java.nio.ByteBuffer source) { return 0; }
        @Override public long write(java.nio.ByteBuffer[] source, int offset, int length) {
            return 0;
        }
        @Override public java.net.SocketAddress getLocalAddress() { return null; }
    }

    private static void verifyConnectionCloseFailurePropagation(boolean failClose)
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try (var listener = VmNioTcpListener.loopback(vm.nioReadiness(), 0, 8, 2, 2)) {
            ClosingProbeSocket probe = new ClosingProbeSocket(failClose);
            // Package-only controlled channel creation exercises the real
            // connection close path without performing fake kernel I/O.
            var connection = listener.new Connection(probe);
            var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread closer = Thread.ofPlatform().start(() -> {
                try { connection.close(); }
                catch (Throwable failure) { unexpected.set(failure); }
            });
            try {
                assertTrue(probe.closeEntered.await(3, TimeUnit.SECONDS));
                assertFalse(connection.closeAsync().isDone(),
                        "connection close Future must wait for physical socket close");
            } finally {
                probe.releaseClose.countDown();
                closer.join(3000);
            }
            assertFalse(closer.isAlive(), "socket closer stranded");
            assertNull(unexpected.get(), "connection close unexpectedly threw");
            if (failClose) {
                var connectionError = assertThrows(ExecutionException.class,
                        () -> connection.closeAsync().get(3, TimeUnit.SECONDS));
                assertInstanceOf(java.io.IOException.class, connectionError.getCause());
                assertTrue(connectionError.getCause().getMessage()
                        .contains("injected socket descriptor"));
                var listenerError = assertThrows(ExecutionException.class,
                        () -> listener.closeAsync().get(3, TimeUnit.SECONDS));
                assertInstanceOf(java.io.IOException.class, listenerError.getCause());
                assertTrue(listenerError.getCause().getMessage()
                        .contains("injected socket descriptor"),
                        "child close failure must not be hidden by listener quiescence");
                assertFalse(listener.isTerminated(),
                        "failure to retire child descriptor is not clean shutdown");
            } else {
                connection.closeAsync().get(3, TimeUnit.SECONDS);
                listener.closeAsync().get(3, TimeUnit.SECONDS);
                assertTrue(listener.isTerminated());
            }
        } finally {
            vm.close();
        }
    }

    @Test void connectionCloseFutureWaitsForPhysicalSocketRetirement()
            throws Exception {
        verifyConnectionCloseFailurePropagation(false);
    }

    @Test void failedSocketClosePropagatesThroughConnectionAndListener()
            throws Exception {
        verifyConnectionCloseFailurePropagation(true);
    }

}
