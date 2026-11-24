package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** Closed-file protocol actors lowered to the existing serial JVM DATA lane. */
public final class ProtocolActorMir {
    private static final byte[] DATA_MAGIC = { 'A', 'T', 'P', 'M', 1 };
    private static final byte[] RECEIPT_MAGIC = { 'A', 'T', 'P', 'R', 1 };
    private static final int HEADER_BYTES = 39;
    private static final int RECEIPT_BYTES = 34;

    private ProtocolActorMir() { }

    public static Plan lower(String source) {
        Objects.requireNonNull(source, "source");
        if (source.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw new IllegalArgumentException("E_PROTOCOL_LIMIT: actor source exceeds the byte budget");
        Ast.Program program = Parser.parseProtocolActors(source);
        return lower(program);
    }

    private static Plan lower(Ast.Program program) {
        Map<String, ActorPlan> actors = new HashMap<>();
        for (var declaration : program.modules().getFirst().declarations()) {
            if (declaration instanceof Ast.ProtocolActorDecl actor)
                actors.put(actor.name(), ActorPlan.lower(actor));
        }
        return new Plan(program, Map.copyOf(actors));
    }

    public static long executeGuest(Ast.ProtocolGuestDecl guest, ActorRuntime runtime,
            SharedCodeImageStore.CodeImage image) {
        Plan plan = lower(image.program());
        var actor = plan.spawn(runtime, image, guest.actor());
        long result = 0;
        try {
            actor.ready().get(15, TimeUnit.SECONDS);
            for (int i = 0; i < guest.turns().size(); i++) {
                var turn = guest.turns().get(i);
                long[] payload = turn.payload().stream().mapToLong(Long::longValue).toArray();
                actor.send(plan.encode(guest.actor(), turn.message(), payload));
                var next = actor.outputs().next().get(15, TimeUnit.SECONDS);
                if (next.done() || !next.value().actorId().equals(actor.id()))
                    throw new IllegalStateException("Missing protocol guest receipt");
                Receipt receipt = Receipt.decode(next.value().value());
                if (receipt.turn() != i + 1L)
                    throw new IllegalStateException("Unexpected protocol guest receipt order");
                if (i == guest.result()) result = receipt.status().code;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Protocol guest interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Protocol guest receipt failed", failure);
        } finally {
            actor.stop();
        }
        try {
            actor.getDone().get(15, TimeUnit.SECONDS);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Protocol guest stop interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Protocol guest stop failed", failure);
        }
    }

    public static final class Plan {
        private final Ast.Program program;
        private final Map<String, ActorPlan> actors;

        private Plan(Ast.Program program, Map<String, ActorPlan> actors) {
            this.program = program;
            this.actors = actors;
        }

        public Ast.Program program() { return program; }

        public ActorRuntime.ActorRef<Object> spawn(ActorRuntime runtime,
                SharedCodeImageStore.CodeImage image, String actor) {
            if (image.program() != program)
                throw new SecurityException("protocol actor code image identity mismatch");
            return runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE,
                    new Executor(actor(actor), image), actor);
        }

        /** Encode host scalar input in the units fixed by the checked schema. */
        public byte[] encode(String actor, String tag, long... payload) {
            var plan = actor(actor);
            Integer ordinal = plan.tags().get(tag);
            if (ordinal == null || payload.length != plan.messages().get(ordinal).parameters().size())
                throw new IllegalArgumentException("unexpected protocol message shape");
            var frame = littleEndian(HEADER_BYTES + Long.BYTES * payload.length);
            frame.put(DATA_MAGIC).put(plan.fingerprint()).putShort(ordinal.shortValue());
            for (long value : payload) frame.putLong(value);
            return frame.array();
        }

        public int stateOrdinal(String actor, String state) {
            Integer ordinal = actor(actor).states().get(state);
            if (ordinal == null) throw new IllegalArgumentException("unknown protocol state");
            return ordinal;
        }

        private ActorPlan actor(String name) {
            ActorPlan plan = actors.get(name);
            if (plan == null) throw new IllegalArgumentException("unknown protocol actor");
            return plan;
        }
    }

    private record EdgeKey(int state, int tag) { }

    private record ActorPlan(String name, int initial, Ast.ProtocolStateField field, Map<String, Integer> states,
                             Map<String, Integer> tags, List<Ast.ProtocolHandler> messages,
                             Map<EdgeKey, Integer> edges, byte[] fingerprint) {
        private static ActorPlan lower(Ast.ProtocolActorDecl actor) {
            var protocol = actor.protocol();
            List<String> states = Stream.concat(Stream.of(protocol.initialState()),
                    protocol.edges().stream().flatMap(edge -> Stream.of(edge.source(), edge.target())))
                    .distinct().sorted().toList();
            var messages = actor.handlers().stream()
                    .sorted(Comparator.comparing(Ast.ProtocolHandler::message)).toList();
            var edges = protocol.edges().stream().sorted(Comparator.comparing(Ast.ProtocolEdge::source)
                    .thenComparing(Ast.ProtocolEdge::message)).toList();
            var stateIds = ordinals(states);
            var tags = ordinals(messages.stream().map(Ast.ProtocolHandler::message).toList());
            Map<EdgeKey, Integer> destinations = new HashMap<>();
            for (var edge : edges) {
                int tag = tags.get(edge.message());
                if (!messages.get(tag).destination().equals(edge.target()))
                    throw new IllegalStateException("protocol edge differs from the verified handler destination");
                destinations.put(new EdgeKey(stateIds.get(edge.source()), tag), stateIds.get(edge.target()));
            }
            return new ActorPlan(actor.name(), stateIds.get(protocol.initialState()), actor.field(), stateIds,
                    tags, messages, Map.copyOf(destinations),
                    ProtocolActorMir.fingerprint(protocol, states, messages, edges));
        }
    }

    static final class State implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorId owner;
        private int current;
        private long value;

        private State(ActorRuntime.ActorId owner, int initial, long value) {
            this.owner = owner;
            this.current = initial;
            this.value = value;
        }

        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }
        void publish(int destination, long stagedValue) {
            current = destination;
            value = stagedValue;
        }
    }

    private record Executor(ActorPlan plan, SharedCodeImageStore.CodeImage codeImage)
            implements ActorRuntime.ActorCodeExecutor {
        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(String name,
                ActorRuntime.ActorContext<Object> context) {
            requireActor(name, context);
            return new State(context.self().id(), plan.initial(), plan.field() == null ? 0 : plan.field().initial());
        }

        @Override
        public void receiveActor(String name, ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorInboxMail<Object> mail, ActorRuntime.ActorContext<Object> context) {
            requireActor(name, context);
            if (!(raw instanceof State state) || !state.ownerActorId().equals(context.self().id())
                    || !mail.recipient().equals(context.self().id()))
                throw new SecurityException("invalid protocol actor mail/state authority");
            int source = state.current;
            Status status = dispatch(mail.value(), context.runtime(), state);
            var domain = ActorRuntime.currentActorExecutionContext().allocationDomain();
            var receipt = littleEndian(RECEIPT_BYTES).put(RECEIPT_MAGIC)
                    .putLong(domain.domain()).putLong(domain.generation()).putLong(Math.addExact(mail.sequence(), 1))
                    .put((byte) status.code).putShort((short) source).putShort((short) state.current);
            try {
                context.runtime().emitCurrentActorOutput(receipt.array());
            } catch (ActorRuntime.ActorTerminatedException stopped) {
                // STOP can close observation after the transition decision.
                if (context.self().isAlive()) throw stopped;
            }
        }

        private void requireActor(String name, ActorRuntime.ActorContext<Object> context) {
            if (!plan.name().equals(name) || context.kind() != ActorRuntime.ActorKind.PRIVATE
                    || context.codeImage().orElse(null) != codeImage)
                throw new SecurityException("protocol actor source/code-image mismatch");
        }

        private Status dispatch(Object mail, ActorRuntime runtime, State state) {
            byte[] bytes = privateFrame(mail, HEADER_BYTES + 16 * Long.BYTES);
            if (bytes == null || bytes.length < HEADER_BYTES
                    || !Arrays.equals(bytes, 0, 5, DATA_MAGIC, 0, 5)
                    || !Arrays.equals(bytes, 5, 37, plan.fingerprint(), 0, 32))
                return Status.INVALID_ENVELOPE;
            ByteBuffer frame = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).position(37);
            int tag = Short.toUnsignedInt(frame.getShort());
            if (tag >= plan.messages().size()) return Status.INVALID_ENVELOPE;
            Integer destination = plan.edges().get(new EdgeKey(state.current, tag));
            if (destination == null) return Status.ILLEGAL_EDGE;
            var handler = plan.messages().get(tag);
            var fields = handler.parameters();
            if (frame.remaining() != Long.BYTES * fields.size()) return Status.INVALID_PAYLOAD;
            for (var field : fields) {
                long value = frame.getLong();
                if (value < field.type().minimum() || value > field.type().maximum())
                    return Status.INVALID_PAYLOAD;
            }
            long staged = state.value;
            if (handler.incrementParameter() >= 0) {
                try {
                    staged = Math.addExact(staged,
                            frame.getLong(HEADER_BYTES + Long.BYTES * handler.incrementParameter()));
                } catch (ArithmeticException overflow) {
                    return Status.INVARIANT_FAILED;
                }
                var field = plan.field();
                if (staged < field.minimum() || staged > field.maximum()) return Status.INVARIANT_FAILED;
            }
            return runtime.tryPublishProtocolState(state, destination, staged)
                    ? Status.COMMITTED : Status.INTERRUPTED;
        }
    }

    public enum Status {
        COMMITTED(1), ILLEGAL_EDGE(2), INVALID_PAYLOAD(3), INVALID_ENVELOPE(4), INTERRUPTED(5), INVARIANT_FAILED(6);

        private final int code;

        Status(int code) { this.code = code; }

        static Status decode(int code) {
            return switch (code) {
                case 1 -> COMMITTED;
                case 2 -> ILLEGAL_EDGE;
                case 3 -> INVALID_PAYLOAD;
                case 4 -> INVALID_ENVELOPE;
                case 5 -> INTERRUPTED;
                case 6 -> INVARIANT_FAILED;
                default -> throw new IllegalArgumentException("invalid protocol receipt status");
            };
        }
    }

    public record Receipt(long actorOrdinal, long generation, long turn, Status status,
                          int source, int destination) {
        public static Receipt decode(Object value) {
            byte[] bytes = privateFrame(value, RECEIPT_BYTES);
            if (bytes == null || bytes.length != RECEIPT_BYTES
                    || !Arrays.equals(bytes, 0, 5, RECEIPT_MAGIC, 0, 5))
                throw new IllegalArgumentException("invalid protocol receipt");
            var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).position(5);
            long actor = input.getLong(), generation = input.getLong(), turn = input.getLong();
            var status = Status.decode(Byte.toUnsignedInt(input.get()));
            return new Receipt(actor, generation, turn, status,
                    Short.toUnsignedInt(input.getShort()), Short.toUnsignedInt(input.getShort()));
        }
    }

    // Private actor transport converts arrays to a detached list of scalar bytes.
    private static byte[] privateFrame(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.size() > limit) return null;
        byte[] bytes = new byte[list.size()];
        for (int i = 0; i < bytes.length; i++) {
            if (!(list.get(i) instanceof Byte b)) return null;
            bytes[i] = b;
        }
        return bytes;
    }

    private static Map<String, Integer> ordinals(List<String> names) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < names.size(); i++) result.put(names.get(i), i);
        return Map.copyOf(result);
    }

    private static byte[] fingerprint(Ast.ProtocolDecl protocol, List<String> states,
            List<Ast.ProtocolHandler> messages, List<Ast.ProtocolEdge> edges) {
        var writer = new Fingerprint();
        writer.text("atto.protocol.mailbox.v1");
        writer.text(protocol.name());
        writer.text(protocol.initialState());
        writer.count(states.size());
        for (String state : states) {
            writer.text(state);
            writer.flag(protocol.terminalStates().contains(state));
        }
        writer.count(messages.size());
        for (var handler : messages) {
            writer.text(handler.message());
            writer.count(handler.parameters().size());
            for (var field : handler.parameters()) {
                var type = field.type();
                writer.flag(type.i32());
                writer.bound(type.minimum());
                writer.bound(type.maximum());
                writer.count(type.dimension().size());
                type.dimension().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(dimension -> {
                    writer.text(dimension.getKey());
                    writer.count(dimension.getValue());
                });
                writer.text(type.scaleNumerator().toString());
                writer.text(type.scaleDenominator().toString());
            }
        }
        writer.count(edges.size());
        for (var edge : edges) {
            writer.text(edge.source());
            writer.text(edge.message());
            writer.text(edge.target());
        }
        return writer.digest.digest();
    }

    private static ByteBuffer littleEndian(int length) {
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static final class Fingerprint {
        private final MessageDigest digest;
        private final ByteBuffer scratch = littleEndian(Long.BYTES);

        private Fingerprint() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException missing) {
                throw new IllegalStateException("SHA-256 is unavailable", missing);
            }
        }

        private void flag(boolean value) { digest.update((byte) (value ? 1 : 0)); }
        private void count(int value) {
            scratch.clear().putInt(value);
            digest.update(scratch.array(), 0, Integer.BYTES);
        }
        private void bound(long value) {
            scratch.clear().putLong(value);
            digest.update(scratch.array(), 0, Long.BYTES);
        }
        private void text(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            int length = bytes.length;
            while (length >= 128) {
                digest.update((byte) ((length & 127) | 128));
                length >>>= 7;
            }
            digest.update((byte) length);
            digest.update(bytes);
        }
    }
}
