package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.Channel;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Trusted host/NIO boundary: exactly one actor physical-retirement ticket
 * owns a Java Channel's close. The channel must not be handed to an actor or
 * native loan consumer until the ticket has been registered.
 *
 * This is for channels whose synchronous close()/isOpen() contract is the
 * actual physical release boundary. Asynchronous native callbacks, pinned
 * buffers and outstanding FFI loans need their OWN independent receipts; a
 * socket close request is not proof those separate loans have retired.
 *
 * Host-only. Do not expose this wrapper or its Channel as a source-language
 * value. A malicious/buggy host closing the raw Channel out of band leaves
 * this ticket outstanding (fail closed), never a false actor-DONE witness.
 */
final class ActorPhysicalChannelLease implements AutoCloseable {
    private final Channel channel;
    private final ActorPhysicalRetirementRegistry.Receipt receipt;
    private final Consumer<ActorPhysicalChannelLease> onClosed;
    private final AtomicBoolean stopCloseSubmitted = new AtomicBoolean();
    private boolean closeStarted;
    private boolean closed;
    private Thread closingThread;
    private Throwable closeFailure;

    ActorPhysicalChannelLease(Channel channel,
            ActorPhysicalRetirementRegistry.Receipt receipt,
            Consumer<ActorPhysicalChannelLease> onClosed) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        if (!channel.isOpen()) {
            receipt.failPhysicalRelease(new IllegalStateException(
                    "physical Channel was closed before registry handoff"));
            throw new IllegalArgumentException("physical Channel must be open");
        }
    }

    /** Trusted driver only; the wrapper must own the close transition. */
    Channel rawChannelForTrustedDriver() {
        return channel;
    }

    /** Queue host-only closure without parking an actor STOP callback. */
    void requestCloseOnActorStop(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        if (!stopCloseSubmitted.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    close();
                } catch (IOException | RuntimeException | Error ignored) {
                    // close() already records any physical close failure.
                }
            });
        } catch (RuntimeException | Error rejected) {
            receipt.failPhysicalRelease(rejected);
        }
    }

    /** The receipt only settles after actual Channel.close() and !isOpen(). */
    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (closeStarted) {
            if (closingThread == Thread.currentThread()) {
                throw new IOException("recursive physical channel close");
            }
            // With the synchronized monitor this branch normally only catches
            // same-thread recursion. Concurrent closers serialize and observe
            // the winner's final release/error result.
            if (closeFailure instanceof IOException io) throw io;
            if (closeFailure != null) {
                throw new IOException("prior physical close failed", closeFailure);
            }
            return;
        }
        closeStarted = true;
        closingThread = Thread.currentThread();
        try {
            channel.close();
            if (channel.isOpen()) {
                throw new IOException(
                        "Channel.close returned without confirming physical closure");
            }
            // Channel physical closure has completed before this receipt.
            // The registry callback can now complete the actor's STOP gate.
            // Detach the verified-closed handle before the receipt can
            // retire the actor. Separately admitted I/O loans remain pending.
            // Publish the physical close outcome BEFORE invoking owner
            // bookkeeping: onClosed/receipt notification may synchronously
            // reenter this lease (including via ActorRef.done observers).
            // Such reentrance must observe an already-closed channel, not
            // falsely report a recursive live close. A bookkeeping failure
            // still fails the receipt closed and quarantines the actor.
            closed = true;
            onClosed.accept(this);
            receipt.completeAfterPhysicalRelease();
        } catch (IOException | RuntimeException | Error failure) {
            closeFailure = failure;
            receipt.failPhysicalRelease(failure);
            throw failure;
        } finally {
            closingThread = null;
        }
    }
}
