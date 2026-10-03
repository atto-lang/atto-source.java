package dev.oreslang.runtime;

import dev.oreslang.net.NativeSocketBridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Capability-gated OS networking primitives for the Oreslang stdlib.
 *
 * This is deliberately not a public FFI facility. Guest code reaches this
 * object only through the reserved native_net builtin, and every operation
 * requires NETWORK from both the context and the current actor policy.
 * HTTP, URI and other protocol semantics belong in .ores source.
 */
public final class NativeNetBuiltin implements BuiltinValue {
    private final OresContext context;

    /**
     * Runtime-owned linear-ish socket capability. The numeric liboresnet token
     * is intentionally never exposed to guest code as an Int.
     *
     * Ordinary supervisor code may use a handle from any non-actor turn in the
     * same OresContext. A handle created from actor execution is bound to that
     * stable actor execution domain and cannot be used by another actor.
     * Generic actor transport rejects this opaque host capability; explicit
     * HTTP lease handoff is the only supported cross-domain ownership path.
     */
    public static final class NativeSocketHandle {
        private final OresContext context;
        private final Object actorDomain;
        private long token;
        private boolean closed;

        private NativeSocketHandle(OresContext context, long token) {
            this(context, token, ActorRuntime.currentActorExecutionDomain());
        }

        private NativeSocketHandle(OresContext context, long token, Object actorDomain) {
            this.context = Objects.requireNonNull(context, "context");
            this.actorDomain = actorDomain;
            this.token = token;
        }

        private synchronized DetachedNativeSocket detachForLease(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (context.actors() != runtime) {
                throw new SecurityException(
                        "native socket handle belongs to a different ActorRuntime/OresContext");
            }
            if (actorDomain != null) {
                throw new SecurityException(
                        "only a supervisor-owned native socket handle can be transferred to an actor lease");
            }
            if (ActorRuntime.inActorExecution() || ActorRuntime.inRootExecution()) {
                throw new SecurityException(
                        "guest/root execution cannot transfer native socket ownership; use the host supervisor");
            }
            if (closed) {
                throw new IllegalStateException("native socket handle is already closed or transferred");
            }
            long detachedToken = token;
            token = 0L;
            closed = true;
            return new DetachedNativeSocket(context, detachedToken);
        }

        private synchronized long requireToken(OresContext expectedContext, String operation) {
            if (context != expectedContext) {
                throw new SecurityException("native socket handle belongs to a different OresContext");
            }
            if (closed) {
                throw new IllegalStateException("native socket handle is closed: " + operation);
            }
            Object currentActorDomain = ActorRuntime.currentActorExecutionDomain();
            if (actorDomain == null) {
                if (currentActorDomain != null) {
                    throw new SecurityException(
                            "supervisor-owned native socket handle cannot be used from actor execution: "
                                    + operation);
                }
            } else if (currentActorDomain != actorDomain) {
                throw new SecurityException(
                        "native socket handle may only be used by its owning actor: " + operation);
            }
            return token;
        }

        private synchronized long takeForClose(OresContext expectedContext) {
            long current = requireToken(expectedContext, "close");
            closed = true;
            token = 0L;
            return current;
        }

        public synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public String toString() {
            return "NativeSocketHandle[opaque]";
        }
    }

    static record DetachedNativeSocket(OresContext context, long token) {
        DetachedNativeSocket {
            Objects.requireNonNull(context, "context");
            if (token <= 0L) throw new IllegalArgumentException("native socket token must be positive");
        }
    }

    static DetachedNativeSocket detachSupervisorSocket(
            NativeSocketHandle handle,
            ActorRuntime runtime) {
        Objects.requireNonNull(handle, "handle");
        return handle.detachForLease(runtime);
    }

    static NativeSocketHandle restoreSupervisorSocket(
            DetachedNativeSocket detached,
            ActorRuntime runtime) {
        Objects.requireNonNull(detached, "detached");
        Objects.requireNonNull(runtime, "runtime");
        if (detached.context().actors() != runtime) {
            throw new SecurityException(
                    "detached native socket belongs to a different ActorRuntime/OresContext");
        }
        if (ActorRuntime.inActorExecution() || ActorRuntime.inRootExecution()) {
            throw new SecurityException(
                    "guest/root execution cannot reclaim native socket ownership; use the host supervisor");
        }
        return new NativeSocketHandle(detached.context(), detached.token(), null);
    }

    public NativeNetBuiltin(OresContext context) {
        this.context = context;
    }

    @Override
    public Object member(String name) {
        return switch (name) {
            case "connect" -> (BuiltinCallable) args -> {
                require(args, 3, name);
                admit(name);
                long token = yieldIo(() -> NativeSocketBridge.connect(
                        str(args, 0), integer(args, 1), integer(args, 2)));
                return new NativeSocketHandle(context, token);
            };
            case "listen" -> (BuiltinCallable) args -> {
                require(args, 4, name); admit(name);
                long token = yieldIo(() -> NativeSocketBridge.listen(
                        str(args, 0), integer(args, 1), integer(args, 2), bool(args, 3)));
                return new NativeSocketHandle(context, token);
            };
            case "accept" -> (BuiltinCallable) args -> {
                require(args, 1, name); admit(name);
                NativeSocketHandle listener = socketHandle(args, 0, name);
                long token = yieldIo(() -> NativeSocketBridge.accept(
                        listener.requireToken(context, "accept")));
                return new NativeSocketHandle(context, token);
            };
            case "read" -> (BuiltinCallable) args -> {
                require(args, 4, name); admit(name);
                List<Object> target = byteList(args.get(1), name);
                byte[] bytes = toBytes(target);
                NativeSocketHandle handle = socketHandle(args, 0, name);
                int count = yieldIo(() -> NativeSocketBridge.read(
                        handle.requireToken(context, "read"),
                        bytes, integer(args, 2), integer(args, 3)));
                if (count > 0) {
                    for (int i = 0; i < bytes.length; i++) target.set(i, (long) (bytes[i] & 0xff));
                }
                return (long) count;
            };
            case "write" -> (BuiltinCallable) args -> {
                require(args, 4, name); admit(name);
                byte[] bytes = toBytes(byteList(args.get(1), name));
                NativeSocketHandle handle = socketHandle(args, 0, name);
                return (long) yieldIo(() -> NativeSocketBridge.write(
                        handle.requireToken(context, "write"),
                        bytes, integer(args, 2), integer(args, 3)));
            };
            case "close" -> (BuiltinCallable) args -> {
                require(args, 1, name); admit(name);
                NativeSocketHandle handle = socketHandle(args, 0, name);
                long token = handle.takeForClose(context);
                try {
                    yieldIoVoid(() -> NativeSocketBridge.close(token));
                } catch (RuntimeException failure) {
                    // The native registry revokes before physical close. Never
                    // resurrect a guest capability after close has begun.
                    throw failure;
                }
                return OresNull.INSTANCE;
            };
            case "resolve_all" -> (BuiltinCallable) args -> {
                require(args, 1, name); admit(name);
                String[] values = yieldIo(() -> NativeSocketBridge.resolveAll(str(args, 0)));
                ArrayList<Object> result = new ArrayList<>(values.length);
                for (String value : values) result.add(value);
                return result;
            };
            default -> throw new IllegalArgumentException("unknown native_net primitive " + name);
        };
    }

    private void admit(String operation) {
        context.requireCapability(IsolatePolicy.Capability.NETWORK, "native_net." + operation);
    }

    private static void require(List<Object> args, int count, String operation) {
        if (args.size() != count) {
            throw new IllegalArgumentException("native_net." + operation + " expects " + count + " arguments");
        }
    }

    private static String str(List<Object> args, int index) {
        if (args.get(index) instanceof String value) return value;
        throw new IllegalArgumentException("native_net expects String argument " + index);
    }

    private static int integer(List<Object> args, int index) {
        long value = integer64(args, index);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("integer out of range");
        return (int) value;
    }

    private static long integer64(List<Object> args, int index) {
        if (args.get(index) instanceof Number value) return value.longValue();
        throw new IllegalArgumentException("native_net expects integer argument " + index);
    }

    private static NativeSocketHandle socketHandle(
            List<Object> args,
            int index,
            String operation) {
        Object value = args.get(index);
        if (value instanceof NativeSocketHandle handle) return handle;
        throw new IllegalArgumentException(
                "native_net." + operation + " expects NativeSocketHandle argument " + index);
    }

    private static boolean bool(List<Object> args, int index) {
        if (args.get(index) instanceof Boolean value) return value;
        throw new IllegalArgumentException("native_net expects Bool argument " + index);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> byteList(Object value, String operation) {
        if (value instanceof List<?> list) return (List<Object>) list;
        throw new IllegalArgumentException("native_net." + operation + " expects a byte array");
    }

    private static byte[] toBytes(List<Object> values) {
        byte[] result = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object item = values.get(i);
            if (!(item instanceof Number number) || number.longValue() < 0 || number.longValue() > 255) {
                throw new IllegalArgumentException("native_net byte arrays contain integers 0..255");
            }
            result[i] = (byte) number.intValue();
        }
        return result;
    }

    private static <T> T yieldIo(IOSupplier<T> supplier) {
        try { return supplier.get(); }
        catch (IOException error) { throw new IllegalStateException(error.getMessage(), error); }
    }

    private static void yieldIoVoid(IORunnable runnable) {
        try { runnable.run(); }
        catch (IOException error) { throw new IllegalStateException(error.getMessage(), error); }
    }

    @FunctionalInterface private interface IOSupplier<T> { T get() throws IOException; }
    @FunctionalInterface private interface IORunnable { void run() throws IOException; }
}
