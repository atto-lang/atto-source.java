package dev.oreslang.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host descriptor bridge only. Channel iteration, generators, observables and
 * formatting live in Oreslang source. The current Truffle embedder must bridge
 * OS streams; guest source never imports a Java class.
 *
 * All three channels are rendezvous channels (capacity zero). The input pump
 * owns at most one read line plus one pending rendezvous write. Output pumps
 * own at most one accepted frame. Slow consumers/sinks therefore eventually
 * backpressure the OS pipe instead of growing unbounded queues.
 */
public final class StdChannels implements AutoCloseable {
    public static final int MAX_LINE_CHARS = 65_536;
    private static final ThreadLocal<Boolean> BRIDGE = ThreadLocal.withInitial(() -> false);
    static boolean isBridgeCurrent() { return Boolean.TRUE.equals(BRIDGE.get()); }

    /**
     * A process-owned descriptor is NOT an ordinary guest-owned channel.
     * Channel.close() is public for user-created channels, but must not allow
     * any Oreslang alias to shut down the ambient process input.
     */
    public static final class InputChannel extends ChannelRuntime.Channel<String> {
        private final StdChannels owner;

        private InputChannel(StdChannels owner) {
            super(0);
            this.owner = owner;
        }

        // Creating a descriptor handle, ReadableStream, or Observable is pure:
        // start the OS reader only when an actual receive is registered.
        void activateReadDemand() { owner.startInputPumpOnDemand(); }

        @Override
        public java.util.Optional<String> tryRead() {
            activateReadDemand();
            return super.tryRead();
        }

        @Override
        public void close() { close(null); }

        @Override
        public void close(Throwable cause) {
            if (!isBridgeCurrent()) {
                throw new SecurityException("stdin is process-owned; consumer cannot close the descriptor");
            }
            super.close(cause);
        }

        private void finishFromContext(Throwable cause) { super.close(cause); }
    }

    /** Process-owned writable descriptor; the context retains close authority. */
    public static final class OutputChannel extends ChannelRuntime.Channel<String> {
        private OutputChannel() { super(0); }

        @Override
        public void close() { close(null); }

        @Override
        public void close(Throwable cause) {
            if (!isBridgeCurrent()) {
                throw new SecurityException("stdout/stderr are process-owned; guest cannot close the descriptor");
            }
            super.close(cause);
        }

        private void finishFromContext(Throwable cause) { super.close(cause); }
    }

    private final OresContext context;
    private final InputChannel input = new InputChannel(this);
    private final OutputChannel output = new OutputChannel();
    private final OutputChannel error = new OutputChannel();
    private final AtomicBoolean startedInput = new AtomicBoolean();
    private final AtomicBoolean startedOutput = new AtomicBoolean();
    private final AtomicBoolean startedError = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread inputThread;
    private volatile Thread outputThread;
    private volatile Thread errorThread;
    private volatile Throwable failure;

    private synchronized void recordFailure(Throwable error) {
        if (failure == null) failure = error;
        else if (failure != error) failure.addSuppressed(error);
    }

    public StdChannels(OresContext context) {
        this.context = context;
    }

    public InputChannel input() {
        context.requireCapability(IsolatePolicy.Capability.STDIN, "stdio.in");
        if (closed.get()) throw new IllegalStateException("stdio is closed");
        return input;
    }

    private void startInputPumpOnDemand() {
        if (closed.get()) return;
        if (startedInput.compareAndSet(false, true)) {
            Thread worker = Thread.ofPlatform().daemon()
                    .name("ores-stdio-in").unstarted(this::pumpInput);
            inputThread = worker;
            // Shutdown may race with admission; pumpInput checks closed
            // before touching the host stream, and finalizes the channel.
            worker.start();
        }
    }

    public OutputChannel output() {
        context.requireCapability(IsolatePolicy.Capability.STDOUT, "stdio.out");
        if (closed.get()) throw new IllegalStateException("stdio is closed");
        if (startedOutput.compareAndSet(false, true)) {
            outputThread = Thread.ofPlatform().daemon().name("ores-stdio-out").start(
                    () -> pumpOutput(output, context.output(), "stdout"));
        }
        return output;
    }

    public OutputChannel error() {
        // Legacy policy groups output descriptors under STDOUT. Explicit
        // separate STDERR authority can be introduced with a policy migration.
        context.requireCapability(IsolatePolicy.Capability.STDOUT, "stdio.err");
        if (closed.get()) throw new IllegalStateException("stdio is closed");
        if (startedError.compareAndSet(false, true)) {
            errorThread = Thread.ofPlatform().daemon().name("ores-stdio-err").start(
                    () -> pumpOutput(error, new PrintWriter(context.env().err(), true), "stderr"));
        }
        return error;
    }

    private void pumpInput() {
        BRIDGE.set(true);
        try {
            BufferedReader reader = context.input();
            while (!closed.get()) {
                String line = readBoundedLine(reader);
                if (line == null) {
                    input.close();
                    return;
                }
                // The kernel read does NOT happen on an actor/root carrier.
                // This join waits for a consumer to accept the previous line.
                input.writeAsync(line).join();
            }
        } catch (java.util.concurrent.CompletionException failed) {
            Throwable root = OresFuture.unwrap(failed);
            if (!(root instanceof ChannelRuntime.ChannelClosedException ended
                    && ended.getCause() == null) && !closed.get()) {
                recordFailure(root);
                input.close(root);
            }
        } catch (ChannelRuntime.ChannelClosedException ignored) {
            // A clean channel close was requested by its owner.
        } catch (Throwable err) {
            if (!closed.get()) {
                recordFailure(err);
                input.close(err);
            }
        } finally {
            // Close while bridge authority is still held; guest aliases
            // never obtain the privilege to close this descriptor.
            input.finishFromContext(null);
            BRIDGE.remove();
        }
    }

    private static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder value = new StringBuilder();
        for (;;) {
            int ch = reader.read();
            if (ch < 0) return value.isEmpty() ? null : value.toString();
            if (ch == '\n') {
                int length = value.length();
                if (length > 0 && value.charAt(length - 1) == '\r') value.setLength(length - 1);
                return value.toString();
            }
            if (value.length() >= MAX_LINE_CHARS) {
                throw new IOException("stdin line exceeds " + MAX_LINE_CHARS + " UTF-16 code units");
            }
            value.append((char) ch);
        }
    }

    private void pumpOutput(OutputChannel channel, PrintWriter target, String descriptor) {
        BRIDGE.set(true);
        try {
            for (;;) {
                String frame;
                try {
                    frame = channel.readAsync().join();
                } catch (java.util.concurrent.CompletionException done) {
                    Throwable root = OresFuture.unwrap(done);
                    if (root instanceof ChannelRuntime.ChannelClosedException closed && closed.getCause() == null) return;
                    throw done;
                }
                target.print(frame);
                target.flush();
                if (target.checkError()) {
                    throw new IOException(descriptor + " sink failed");
                }
            }
        } catch (Throwable err) {
            // A sink failure after graceful close was initiated is still a
            // real I/O failure and must be reported to the context owner.
            recordFailure(err);
            channel.close(err);
        } finally {
            BRIDGE.remove();
        }
    }

    public boolean isInput(Object channel) { return channel == input; }
    public boolean isOutput(Object channel) { return channel == output || channel == error; }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        input.finishFromContext(null);
        if (inputThread != null) inputThread.interrupt();
        // Context teardown retains descriptor-close authority. A guest alias
        // cannot call close(), including through an ordinary Channel<T> type.
        output.finishFromContext(null);
        error.finishFromContext(null);
        // Bounded close must report an undrained/stuck sink instead of
        // silently claiming writes were flushed when its worker is still alive.
        joinSink(outputThread, "stdout");
        joinSink(errorThread, "stderr");
        Throwable failed = failure;
        if (failed != null) throw new IllegalStateException("stdio channel failed", failed);
    }

    private void joinSink(Thread worker, String descriptor) {
        if (worker == null || worker == Thread.currentThread()) return;
        try {
            worker.join(2_000L);
            if (worker.isAlive()) {
                recordFailure(new IllegalStateException(
                        descriptor + " sink did not finish within the 2-second shutdown budget"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordFailure(new IllegalStateException(
                    descriptor + " sink shutdown interrupted", interrupted));
        }
    }
}
