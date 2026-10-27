package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * Supervisor-owned nonblocking TCP connection backed only by the VM selector
 * readiness domain. This is the outbound counterpart of listener-accepted
 * NioTcpConnection values; guest code observes the same nominal type.
 */
public final class VmNioTcpConnection implements AutoCloseable {
    private final NioReadinessGroup readiness;
    private final SocketChannel channel;
    private final int maxPending;
    private final Runnable terminalHook;
    private final Object gate = new Object();
    private final Set<OresFuture<?>> outstanding = new HashSet<>();
    private final OresFuture<Void> connectionClosed = new OresFuture<>();
    private int activeOperations;
    private boolean terminated;
    private boolean terminalHookRun;

    VmNioTcpConnection(
            NioReadinessGroup readiness,
            SocketChannel channel,
            int maxPending,
            Runnable terminalHook) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.terminalHook = Objects.requireNonNull(terminalHook, "terminalHook");
        if (channel.isBlocking())
            throw new IllegalArgumentException("NIO TCP connection must be nonblocking");
        if (maxPending < 1 || maxPending > 65536)
            throw new IllegalArgumentException("invalid TCP operation bound");
        this.maxPending = maxPending;
    }

    public boolean isTerminated() {
        synchronized (gate) {
            return terminated && activeOperations == 0 && outstanding.isEmpty();
        }
    }

    private void runTerminalHookIfQuiesced() {
        final boolean run;
        synchronized (gate) {
            run = terminated && activeOperations == 0
                    && outstanding.isEmpty() && !terminalHookRun;
            if (run) terminalHookRun = true;
        }
        if (run) terminalHook.run();
    }

    private Throwable reserveSocketOperation() {
        synchronized (gate) {
            if (terminated) return new IOException("TCP connection closed");
            if (activeOperations >= maxPending)
                return new RejectedExecutionException("TCP operation capacity exhausted");
            ++activeOperations;
            return null;
        }
    }

    private void releaseSocketOperation() {
        boolean quiesced;
        synchronized (gate) {
            if (activeOperations <= 0)
                throw new IllegalStateException("TCP socket operation reservation underflow");
            --activeOperations;
            quiesced = terminated && activeOperations == 0;
        }
        if (quiesced) connectionClosed.completeFromRuntime(null);
        runTerminalHookIfQuiesced();
    }

    private <T> OresFuture<T> track(OresFuture<T> future) {
        boolean shutdown;
        synchronized (gate) {
            shutdown = terminated;
            if (!shutdown && !future.isDone()) outstanding.add(future);
        }
        future.whenCompleteRuntime((value, failure) -> {
            synchronized (gate) { outstanding.remove(future); }
            runTerminalHookIfQuiesced();
        });
        if (shutdown && !future.isDone()) future.cancel(false);
        return future;
    }

    public OresFuture<List<Long>> readBytesAsync(int maximum) {
        if (maximum < 1 || maximum > 65536)
            return OresFuture.failed(new IllegalArgumentException("invalid TCP read bound"));
        Throwable denial = reserveSocketOperation();
        if (denial != null) return OresFuture.failed(denial);

        final OresFuture<byte[]> raw;
        try {
            raw = readiness.readAsync(channel, maximum);
        } catch (RuntimeException | Error failure) {
            releaseSocketOperation();
            throw failure;
        }

        OresFuture<List<Long>> exposed = new OresFuture<>(
                () -> raw.cancel(false),
                () -> { },
                this::releaseSocketOperation);
        raw.whenCompleteRuntime((bytes, failure) -> {
            try {
                if (raw.isCancelled()) return;
                if (failure != null) {
                    exposed.failFromRuntime(failure);
                    return;
                }
                if (exposed.isDone()) return;
                ArrayList<Long> list = new ArrayList<>(bytes.length);
                for (byte b : bytes) list.add((long) (b & 0xff));
                exposed.completeFromRuntime(List.copyOf(list));
            } catch (RuntimeException | Error mappingFailure) {
                exposed.failFromRuntime(mappingFailure);
                if (mappingFailure instanceof VirtualMachineError fatal) throw fatal;
                if (mappingFailure instanceof ThreadDeath fatal) throw fatal;
            }
        });
        return track(exposed);
    }

    public OresFuture<Integer> writeBytesAsync(List<?> bytes) {
        if (bytes == null || bytes.size() > 65536)
            return OresFuture.failed(new IllegalArgumentException("invalid TCP write bytes"));
        byte[] snapshot = new byte[bytes.size()];
        for (int i = 0; i < snapshot.length; ++i) {
            Object value = bytes.get(i);
            if (!(value instanceof Long) && !(value instanceof Integer))
                return OresFuture.failed(new IllegalArgumentException("TCP bytes must be integers"));
            long b = ((Number) value).longValue();
            if (b < 0 || b > 255)
                return OresFuture.failed(new IllegalArgumentException("TCP byte outside 0..255"));
            snapshot[i] = (byte) b;
        }

        Throwable denial = reserveSocketOperation();
        if (denial != null) return OresFuture.failed(denial);

        final OresFuture<Integer> written;
        try {
            written = readiness.writeAsync(channel, snapshot);
        } catch (RuntimeException | Error failure) {
            releaseSocketOperation();
            throw failure;
        }

        OresFuture<Integer> exposed = new OresFuture<>(
                () -> written.cancel(false),
                () -> { },
                this::releaseSocketOperation);
        written.whenCompleteRuntime((value, failure) -> {
            if (written.isCancelled()) return;
            if (failure != null) exposed.failFromRuntime(failure);
            else exposed.completeFromRuntime(value);
        });
        return track(exposed);
    }

    public OresFuture<Void> closeAsync() {
        close();
        return connectionClosed;
    }

    @Override public void close() {
        final List<OresFuture<?>> snapshot;
        final boolean quiesced;
        synchronized (gate) {
            if (terminated) return;
            terminated = true;
            snapshot = new ArrayList<>(outstanding);
            quiesced = activeOperations == 0;
        }
        try { channel.close(); } catch (IOException ignored) { }
        for (OresFuture<?> pending : snapshot) pending.cancel(false);
        if (quiesced) connectionClosed.completeFromRuntime(null);
        runTerminalHookIfQuiesced();
    }
}
