package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** NIO2 completions run on VM-owned, bounded carriers that the VM shuts down. */
final class VmNioCompletionGroupTest {

    @Test
    void acceptCompletesOnAVmOwnedNioWorkerAndVmCloseRetiresTheGroup() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        AsynchronousServerSocketChannel server;
        try {
            var group = vm.nioCompletionGroup();
            assertSame(group, vm.nioCompletionGroup(), "one group per VM");
            server = AsynchronousServerSocketChannel.open(group)
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            AtomicReference<String> completionThread = new AtomicReference<>();
            AtomicBoolean onNioWorker = new AtomicBoolean();
            CountDownLatch accepted = new CountDownLatch(1);
            server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override public void completed(AsynchronousSocketChannel channel, Void ignored) {
                    completionThread.set(Thread.currentThread().getName());
                    onNioWorker.set(OresVM.onNioWorker());
                    accepted.countDown();
                    try { channel.close(); } catch (Exception ignoredClose) { }
                }
                @Override public void failed(Throwable failure, Void ignored) {
                    accepted.countDown();
                }
            });
            try (SocketChannel client = SocketChannel.open(server.getLocalAddress())) {
                assertTrue(client.isConnected());
                assertTrue(accepted.await(5, TimeUnit.SECONDS));
            }
            assertTrue(completionThread.get().startsWith("ores-nio-completion-"),
                    "completion ran on " + completionThread.get());
            assertTrue(onNioWorker.get(), "completion carriers are marked as NIO workers");
            assertTrue(vm.nioCompletionGroupLive());
        } finally {
            vm.close();
        }
        assertFalse(vm.nioCompletionGroupLive(), "VM close terminates the completion group");
        assertFalse(server.isOpen(), "VM close closes channels still open in the group");
        assertThrows(IllegalStateException.class, vm::nioCompletionGroup);
    }

    @Test
    void pendingOperationsFailWhenTheVmCloses() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch settled = new CountDownLatch(1);
        try {
            AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel
                    .open(vm.nioCompletionGroup())
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override public void completed(AsynchronousSocketChannel channel, Void ignored) {
                    settled.countDown();
                }
                @Override public void failed(Throwable cause, Void ignored) {
                    failure.set(cause);
                    settled.countDown();
                }
            });
        } finally {
            vm.close();
        }
        assertTrue(settled.await(5, TimeUnit.SECONDS), "a pending accept settles on close");
        assertTrue(failure.get() instanceof AsynchronousCloseException
                        || failure.get() instanceof ClosedChannelException,
                "pending accept fails with a close exception, got " + failure.get());
    }

    @Test
    void completionThreadBoundsAreValidated() {
        assertEquals(2, OresVM.configuredNioCompletionThreads(null));
        assertEquals(4, OresVM.configuredNioCompletionThreads(" 4 "));
        assertThrows(IllegalArgumentException.class, () -> OresVM.configuredNioCompletionThreads("0"));
        assertThrows(IllegalArgumentException.class, () -> OresVM.configuredNioCompletionThreads("65"));
        assertThrows(IllegalArgumentException.class, () -> OresVM.configuredNioCompletionThreads("two"));
    }
}
