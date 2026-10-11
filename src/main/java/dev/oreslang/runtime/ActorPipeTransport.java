package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted host substrate for a forward-only, supervised, bounded actor pipe.
 *
 * The supervisor is bound to the ActorGroup before this transport is visible.
 * Guests must only receive their compiler-minted per-stage sender or receiver;
 * the generic host factory is not a guest-visible source intrinsic.
 *
 * This class does not spawn actors or implement source-level PipelineExpr.
 */
public final class ActorPipeTransport implements AutoCloseable {
    private final ActorRuntime.ActorGroup group;
    private final List<ActorPipelineContract.Connection> schema;
    private final List<Class<?>> payloadClasses;
    private final List<ChannelRuntime.Channel<Object>> links;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ActorPipeTransport(
            ActorRuntime runtime,
            ActorRuntime.ActorSupervisor supervisor,
            List<ActorPipelineContract.Stage> stages,
            List<Class<?>> payloadClasses,
            int capacity) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(supervisor, "supervisor");
        this.schema = ActorPipelineContract.validate(stages);
        this.payloadClasses = List.copyOf(payloadClasses);
        if (payloadClasses.size() != schema.size())
            throw new IllegalArgumentException("each actor pipe edge requires one host payload type");
        if (capacity < 1 || capacity > runtime.policyCeiling().maxMailboxMessages())
            throw new IllegalArgumentException("actor pipe capacity exceeds isolate mailbox budget");
        for (int i = 0; i < schema.size(); i++) {
            Class<?> type = Objects.requireNonNull(payloadClasses.get(i), "edge payload type");
            if (type.isPrimitive() || type.isArray() || type == Object.class
                    || !schema.get(i).payloadType().equals(type.getName()))
                throw new IllegalArgumentException("actor pipe edge " + i
                        + " must use the exact declared non-Object payload class");
        }
        ArrayList<ChannelRuntime.Channel<Object>> reserved = new ArrayList<>(schema.size());
        for (int i = 0; i < schema.size(); i++)
            reserved.add(new ChannelRuntime.Channel<>(capacity));
        this.links = List.copyOf(reserved);
        // Construction-time supervisor assignment is authoritative. There
        // must never be a published, but unsupervised, transport.
        this.group = runtime.createActorGroup(supervisor);
        try {
            group.registerInfrastructureClose(this::closeChannelsFromGroup);
        } catch (RuntimeException | Error failure) {
            closeChannelsFromGroup();
            try { group.close(); } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public static ActorPipeTransport open(
            ActorRuntime runtime,
            ActorRuntime.ActorSupervisor supervisor,
            List<ActorPipelineContract.Stage> stages,
            List<Class<?>> payloadClasses,
            int capacity) {
        return new ActorPipeTransport(runtime, supervisor, stages, payloadClasses, capacity);
    }

    public ActorRuntime.ActorGroupId groupId() { return group.id(); }

    public ActorRuntime.SupervisorId supervisorId() { return group.supervisorId(); }

    public List<ActorPipelineContract.Connection> connections() { return schema; }

    public int connectionCount() { return links.size(); }

    public boolean closed() { return closed.get() || group.closed(); }

    private void requireOpen() {
        if (closed()) throw new ChannelRuntime.ChannelClosedException("actor pipe is closed");
    }

    private <T> ChannelRuntime.Channel<Object> requireEdge(int index, Class<T> type) {
        Objects.requireNonNull(type, "payload type");
        requireOpen();
        if (index < 0 || index >= links.size())
            throw new IndexOutOfBoundsException("actor pipe edge: " + index);
        if (payloadClasses.get(index) != type)
            throw new IllegalArgumentException("actor pipe port payload type mismatch: "
                    + type.getName() + " vs " + payloadClasses.get(index).getName());
        return links.get(index);
    }

    /** Host-only stage wiring. The returned write capability cannot read. */
    public <T> ForwardWriter<T> writer(int edgeIndex, Class<T> type) {
        return new ForwardWriter<>(requireEdge(edgeIndex, type), type);
    }

    /** Host-only stage wiring. The returned read capability cannot write. */
    public <T> ForwardReader<T> reader(int edgeIndex, Class<T> type) {
        return new ForwardReader<>(requireEdge(edgeIndex, type), type);
    }

    public final class ForwardWriter<T> {
        private final ChannelRuntime.Channel<Object> channel;
        private final Class<T> type;

        private ForwardWriter(ChannelRuntime.Channel<Object> channel, Class<T> type) {
            this.channel = channel;
            this.type = type;
        }

        private T checked(T message) {
            Objects.requireNonNull(message, "pipe message");
            if (!type.isInstance(message))
                throw new IllegalArgumentException("pipe message has wrong runtime type");
            return message;
        }

        /** Nonblocking admission; pending send releases the scheduler carrier. */
        public OresFuture<Void> writeAsync(T message) {
            T value = checked(message);
            if (closed()) return OresFuture.failed(
                    new ChannelRuntime.ChannelClosedException("actor pipe is closed"));
            return channel.writeAsync(value);
        }

        public boolean tryWrite(T message) {
            T value = checked(message);
            return !closed() && channel.tryWrite(value);
        }
    }

    public final class ForwardReader<T> {
        private final ChannelRuntime.Channel<Object> channel;
        private final Class<T> type;

        private ForwardReader(ChannelRuntime.Channel<Object> channel, Class<T> type) {
            this.channel = channel;
            this.type = type;
        }

        public OresFuture<T> readAsync() {
            // Channel receipt is typed by the matching class token and
            // guarded sender. No source-level reinterpretation is allowed.
            @SuppressWarnings("unchecked")
            OresFuture<T> result = (OresFuture<T>) (OresFuture<?>) channel.readAsync();
            return result;
        }

        public Optional<T> tryRead() {
            return channel.tryRead().map(type::cast);
        }
    }

    /** Group shutdown also invokes this callback, including runtime shutdown. */
    private void closeChannelsFromGroup() {
        if (!closed.compareAndSet(false, true)) return;
        for (ChannelRuntime.Channel<Object> channel : links)
            channel.close();
    }

    @Override
    public void close() {
        closeChannelsFromGroup();
        group.close();
    }
}
