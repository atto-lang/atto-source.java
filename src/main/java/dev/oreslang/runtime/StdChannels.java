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

    /** Read-only to guest source. Runtime producer may write internally. */
    public static final class InputChannel extends ChannelRuntime.Channel<String> {
        private InputChannel() { super(0); }
    }

    /** Write-only to guest source. Runtime sink may read internally. */
    public static final class OutputChannel extends ChannelRuntime.Channel<String> {
        private OutputChannel() { super(0); }
    }

    private final OresContext context;
    private final InputChannel input = new InputChannel();
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

    public StdChannels(OresContext context) {
        this.context = context;
    }

    public InputChannel input() {
        context.requireCapability(IsolatePolicy.Capability.STDIN, "stdio.in");
        if (closed.get()) throw new IllegalStateException("stdio is closed");
        if (startedInput.compareAndSet(false, true)) {
            inputThread = Thread.ofPlatform().daemon().name("ores-stdio-in").start(this::pumpInput);
        }
        return input;
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
                failure = root;
                input.close(root);
            }
        } catch (ChannelRuntime.ChannelClosedException ignored) {
            // A clean channel close was requested by its owner.
        } catch (Throwable err) {
            if (!closed.get()) {
                failure = err;
                input.close(err);
            }
        } finally {
            BRIDGE.remove();
            input.close();
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
            failure = err;
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
        input.close();
        if (inputThread != null) inputThread.interrupt();
        // Closing rendezvous channels settles parked sink receives after
        // any already committed frame has been handed to its worker.
        output.close();
        error.close();
        joinQuietly(outputThread);
        joinQuietly(errorThread);
        Throwable failed = failure;
        if (failed != null) throw new IllegalStateException("stdio channel failed", failed);
    }

    private static void joinQuietly(Thread worker) {
        if (worker == null || worker == Thread.currentThread()) return;
        try {
            worker.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
