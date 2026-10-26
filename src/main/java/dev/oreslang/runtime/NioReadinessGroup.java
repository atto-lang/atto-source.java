package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class NioReadinessGroup implements AutoCloseable {
    private static final ThreadLocal<Boolean> ON_REACTOR = new ThreadLocal<>();
    private final NioReadinessCore core;

    static boolean onReactorThread() {
        return Boolean.TRUE.equals(ON_REACTOR.get());
    }

    static void enterReactor() { ON_REACTOR.set(Boolean.TRUE); }
    static void leaveReactor() { ON_REACTOR.remove(); }

    public NioReadinessGroup() throws IOException {
        this(4, 8192, 1024);
    }

    public NioReadinessGroup(int maxReactors, int maxPending, int commandCapacity)
            throws IOException {
        this(maxReactors, maxPending, commandCapacity, null, null);
    }

    NioReadinessGroup(int maxReactors, int maxPending, int commandCapacity,
            Executor vmCompletionExecutor) throws IOException {
        this(maxReactors, maxPending, commandCapacity, vmCompletionExecutor, null);
    }

    NioReadinessGroup(int maxReactors, int maxPending, int commandCapacity,
            Executor vmCompletionExecutor, ThreadFactory ownedCompletionFactory)
            throws IOException {
        core = new NioReadinessCore(
                this, maxReactors, maxPending, commandCapacity,
                vmCompletionExecutor, ownedCompletionFactory);
    }

    public int reactorCount() { return core.reactorCount(); }
    public int pendingCount() { return core.pendingCount(); }
    int pendingNotifications() { return core.pendingNotifications(); }
    int stagedNotificationCount() { return core.stagedNotificationCount(); }
    int unexpectedSubmissionFailures() { return core.unexpectedSubmissionFailures(); }
    int activeAcceptLeases() { return core.activeAcceptLeases(); }
    int activeReadLeases() { return core.activeReadLeases(); }
    int activeWriteLeases() { return core.activeWriteLeases(); }
    boolean usesVmCompletionCarriers() { return core.usesVmCompletionCarriers(); }
    int completionCarrierCount() { return core.completionCarrierCount(); }

    void bindVmCompletionExecutor(Executor executor) {
        core.bindVmCompletionExecutor(executor, false);
    }

    void bindVmCompletionExecutor(Executor executor, boolean coldStartOffReactor) {
        core.bindVmCompletionExecutor(executor, coldStartOffReactor);
    }

    void retryStagedNotifications() { core.retryStagedNotifications(); }

    public OresFuture<Integer> watch(SelectableChannel channel, int interestOps) {
        return core.watch(channel, interestOps);
    }

    public OresFuture<SocketChannel> acceptAsync(ServerSocketChannel listener) {
        return NioTcpOps.accept(core, listener);
    }

    public OresFuture<byte[]> readAsync(SocketChannel channel, int maximum) {
        return NioTcpOps.read(core, channel, maximum);
    }

    public OresFuture<Integer> writeAsync(SocketChannel channel, byte[] bytes) {
        return NioTcpOps.write(core, channel, bytes);
    }

    @Override public void close() { core.close(); }

    boolean awaitStopped(long time, TimeUnit unit) throws InterruptedException {
        return core.awaitStopped(time, unit);
    }
}
