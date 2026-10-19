package dev.oreslang.types;

import java.util.Objects;
import java.util.function.Predicate;

import static dev.oreslang.types.Types.*;

/**
 * Compiler-known, auto-inferred cross-worker marker proofs.
 *
 * <p>Send: moving an owned T onto a different scheduler worker is safe.
 * Sync: transmitting a shared &T reference to another worker is safe
 * (equivalent to &T: Send). Neither proof transfers an actor-private arena,
 * grants mutable aliasing, or exports a pointer across allocation domains.
 *
 * <p>Unknown generics, FFI values and runtime capabilities fail closed.
 * Nominal proofs come only from a compiler-owned verified layout resolver;
 * user declarations cannot forge a marker or assert "unsafe impl".
 */
public final class SendSyncContracts {
    private SendSyncContracts() { }

    public enum Marker { SEND, SYNC }

    @FunctionalInterface
    public interface NominalProof {
        /** Compiler-verifiable field/parent layout only; never trust annotations. */
        boolean proves(Types.Named named, Marker marker);
    }

    public static boolean isSend(Type type, NominalProof nominal) {
        return proves(type, Marker.SEND, nominal);
    }

    public static boolean isSync(Type type, NominalProof nominal) {
        return proves(type, Marker.SYNC, nominal);
    }

    public static void requireSend(Type type, NominalProof nominal, String boundary) {
        if (!isSend(type, nominal)) {
            throw new IllegalArgumentException(
                    boundary + " requires Send: type is not provably worker-transferable: " + type);
        }
    }

    public static void requireSync(Type type, NominalProof nominal, String boundary) {
        if (!isSync(type, nominal)) {
            throw new IllegalArgumentException(
                    boundary + " requires Sync: shared references are not provably thread-safe: " + type);
        }
    }

    private static boolean proves(Type type, Marker marker, NominalProof nominal) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(nominal, "nominal");
        // Negative types do not establish ownership capabilities: an unknown
        // value which merely isn't Foo may still contain an unsafe pointer.
        if (type == Unknown.INSTANCE || type instanceof Generic
                || type instanceof SelfType || type instanceof Not
                || type instanceof Function || type instanceof ClassNamespace) return false;
        if (type instanceof Primitive primitive) return primitive != Primitive.NULL;
        if (type instanceof StringLiteral || type instanceof BoolLiteral) return true;
        if (type instanceof Borrow borrowed) {
            // &T: Send iff T: Sync; &mut T: Send iff T: Send.
            // Either borrowed reference is Sync iff T: Sync.
            // This is a TRAIT property, not permission to escape a borrow:
            // async/Task boundaries still require an explicit checked scope.
            Marker required = marker == Marker.SYNC
                    ? Marker.SYNC
                    : borrowed.mutable() ? Marker.SEND : Marker.SYNC;
            return proves(borrowed.target(), required, nominal);
        }
        if (type instanceof Record record) {
            return record.members().values().stream().allMatch(t -> proves(t, marker, nominal));
        }
        if (type instanceof Tuple tuple) {
            return tuple.elements().stream().allMatch(t -> proves(t, marker, nominal));
        }
        if (type instanceof Union union) {
            return union.options().stream().allMatch(t -> proves(t, marker, nominal));
        }
        if (type instanceof ListType list) return proves(list.element(), marker, nominal);
        if (!(type instanceof Named named)) return false;

        var name = named.name();
        var args = named.arguments();
        // Built-in data-only algebraic types are structurally inferred.
        if (name.equals("Option") || name.equals("IteratorResult")) {
            return args.size() == 1 && proves(args.getFirst(), marker, nominal);
        }
        if (name.equals("Result")) {
            return args.size() == 2
                    && proves(args.get(0), marker, nominal)
                    && proves(args.get(1), marker, nominal);
        }
        if (name.equals("OptionUnwrapError")) return args.isEmpty();
        // A Channel<T> can be moved/shared if its runtime mailbox is
        // synchronized and the transferred PAYLOAD is Send. Do not require T:
        // Sync: a mutable but owned T can be handed from sender to receiver.
        if (name.equals("Channel")) {
            return args.size() == 1 && proves(args.getFirst(), Marker.SEND, nominal);
        }
        // These are deliberately NOT inferred from generic arguments alone.
        // Shared<T> needs a verified allocator-domain/lifetime proof;
        // ActorRef<T> needs the runtime capability export gate.
        if (name.equals("Shared") || name.equals("ActorRef")
                || name.equals("Mutex") || name.equals("MutexGuard")
                || name.equals("SharedMutex") || name.equals("Future")
                || name.equals("Task") || name.equals("Iterator")
                || name.equals("AsyncIterator") || name.equals("NativeHandle")
                || name.equals("ThreadToken") || name.equals("GpuContext")
                || name.equals("TcpConnection") || name.equals("HttpExchange")
                || name.equals("HttpServer") || name.equals("HttpHandler")) return false;
        // Reject generic aliases unless their entire substitutions are proven.
        if (args.stream().anyMatch(t -> !proves(t, marker, nominal))) return false;
        return nominal.proves(named, marker);
    }
}
