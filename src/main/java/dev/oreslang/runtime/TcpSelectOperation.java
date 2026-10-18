package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-only lazy TCP operation descriptors for the future socket-select bridge.
 *
 * <p>Kernel readiness is only eligibility. No read/write/accept is attempted
 * until the trusted select coordinator marks the descriptor ready and wins it.
 * These descriptors are deliberately not ChannelRuntime.SelectCase yet: source
 * lowering must remain fail-closed until the coordinator owns reserve/commit/
 * cancel tokens and fd/incarnation fencing.</p>
 */
final class TcpSelectOperation {
    private TcpSelectOperation() { }

    enum Phase { DESCRIBED, READY, WON, COMMITTED, CANCELLED }
    enum CommitStatus { COMPLETE, REARM }

    record Commit<T>(CommitStatus status, T value, int bytesCommitted) {
        Commit {
            Objects.requireNonNull(status, "status");
            if (bytesCommitted < 0) throw new IllegalArgumentException("negative committed bytes");
        }
    }

    abstract static class Operation<T> {
        private final AtomicReference<Phase> phase =
                new AtomicReference<>(Phase.DESCRIBED);

        final Phase phase() { return phase.get(); }

        /** Trusted readiness bridge only. A readiness edge consumes no I/O. */
        final boolean markReady() {
            return phase.compareAndSet(Phase.DESCRIBED, Phase.READY);
        }

        /** Atomic select winner reservation. Losing operations never commit. */
        final boolean tryWin() {
            return phase.compareAndSet(Phase.READY, Phase.WON);
        }

        /** Cancellation is rollback-safe only before winner reservation. */
        final boolean cancelBeforeWin() {
            for (;;) {
                Phase current = phase.get();
                if (current == Phase.CANCELLED) return true;
                if (current == Phase.WON || current == Phase.COMMITTED) return false;
                if (phase.compareAndSet(current, Phase.CANCELLED)) return true;
            }
        }

        final Commit<T> commit() throws IOException {
            if (phase.get() != Phase.WON) {
                throw new IllegalStateException(
                        "TCP selectable operation must win before kernel commit");
            }
            Commit<T> result = commitKernel();
            if (result.status() == CommitStatus.COMPLETE) {
                if (!phase.compareAndSet(Phase.WON, Phase.COMMITTED)) {
                    throw new IllegalStateException("TCP selectable commit state changed");
                }
            }
            return result;
        }

        abstract Commit<T> commitKernel() throws IOException;
    }

    static final class Read extends Operation<byte[]> {
        private final SocketChannel channel;
        private final int maximum;

        Read(SocketChannel channel, int maximum) {
            this.channel = Objects.requireNonNull(channel, "channel");
            if (maximum < 1 || maximum > 65536)
                throw new IllegalArgumentException("invalid TCP read bound");
            this.maximum = maximum;
        }

        @Override Commit<byte[]> commitKernel() throws IOException {
            if (channel.isBlocking())
                throw new IllegalStateException("TCP selectable channel became blocking");
            ByteBuffer buffer = ByteBuffer.allocate(maximum);
            int count = channel.read(buffer);
            if (count == 0) return new Commit<>(CommitStatus.REARM, null, 0);
            if (count < 0) return new Commit<>(CommitStatus.COMPLETE, new byte[0], 0);
            return new Commit<>(
                    CommitStatus.COMPLETE,
                    Arrays.copyOf(buffer.array(), count),
                    count);
        }
    }

    static final class Write extends Operation<Integer> {
        private final SocketChannel channel;
        private final ByteBuffer buffer;
        private int committed;

        Write(SocketChannel channel, byte[] bytes) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.buffer = ByteBuffer.wrap(Arrays.copyOf(
                    Objects.requireNonNull(bytes, "bytes"), bytes.length));
        }

        @Override Commit<Integer> commitKernel() throws IOException {
            if (channel.isBlocking())
                throw new IllegalStateException("TCP selectable channel became blocking");
            int count = channel.write(buffer);
            if (count < 0) throw new IOException("negative TCP write result");
            committed = Math.addExact(committed, count);
            if (buffer.hasRemaining())
                return new Commit<>(CommitStatus.REARM, committed, count);
            return new Commit<>(CommitStatus.COMPLETE, committed, count);
        }
    }

    static final class Accept extends Operation<SocketChannel> {
        private final ServerSocketChannel listener;

        Accept(ServerSocketChannel listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        @Override Commit<SocketChannel> commitKernel() throws IOException {
            if (listener.isBlocking())
                throw new IllegalStateException("TCP selectable listener became blocking");
            SocketChannel accepted = listener.accept();
            if (accepted == null) return new Commit<>(CommitStatus.REARM, null, 0);
            accepted.configureBlocking(false);
            return new Commit<>(CommitStatus.COMPLETE, accepted, 0);
        }
    }
}
