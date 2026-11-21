package dev.oreslang.types;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.BoolLiteral;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.SelfType;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Union;
import dev.oreslang.types.Types.Unknown;

/**
 * Conservative, compiler-side Rust-style thread-safety auto-trait query.
 *
 * <p>Send means owned value transfer across worker threads is safe. Sync means
 * immutable references can be shared across worker threads. Neither trait
 * grants an actor-message transport contract or proves a borrow is 'static.
 * The borrow checker must still establish ownership, exclusivity and scope.
 *
 * <p>No guest declarations or unsafe/user-defined implementations can forge a
 * trait proof. NominalProof is a trusted compiler callback, not a guest hook.
 */
public final class ThreadSafetyTraits {
    private ThreadSafetyTraits() { }

    // Bound recursive compiler queries on attacker-controlled nested type input.
    private static final int MAX_TYPE_DEPTH = 256;
    private static final Set<String> UNPROVEN_CAPABILITIES = Set.of(
            "Mutex", "MutexGuard", "SharedMutex", "Future", "Task",
            "Channel", "ActorRef", "ActorMail", "ActorOutput",
            "ActorOutputStream", "ActorGroup", "Supervisor", "ActorContext",
            "SelectCase", "SelectSet", "SelectResult",
            "Iterator", "AsyncIterator", "TcpConnection", "HttpExchange",
            "HttpServer", "HttpHandler", "ExecutionPermit",
            "ReadLoan", "WriteLoan", "PartitionLoan", "BorrowGuard",
            "RawPointer", "Pointer", "OresPointer",
            "NativeHandle", "FfiHandle", "ThreadBound", "ThreadLocal",
            "Rc", "RcWeak", "RefCell", "Cell", "RwLockGuard",
            // Runtime wrappers need an authenticated intrinsic/layout witness.
            // A nominal callback (even a permissive one) cannot enable them.
            "Arc", "Weak", "Shared", "RwLock", "ReadGuard", "WriteGuard",
            "NativePointer", "ActorState", "UnsafeCell", "AtomicRef", "TRef", "Ref");

    public enum Trait { SEND, SYNC }

    @FunctionalInterface
    public interface NominalProof {
        boolean has(Named type, Trait trait);
    }

    public static boolean has(Type type, Trait trait, NominalProof nominalProof) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(trait, "trait");
        Objects.requireNonNull(nominalProof, "nominalProof");
        return has(type, trait, nominalProof, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static boolean has(Type type, Trait trait, NominalProof nominalProof, Set<Type> visiting, int depth) {
        if (depth > MAX_TYPE_DEPTH) return false;
        // Never infer thread safety from cycles, unresolved types or missing
        // generic bounds. Genuine recursive nominal definitions need a separate
        // fixed-point proof over their field graph.
        if (!visiting.add(type)) return false;
        try {
            if (type == Unknown.INSTANCE || type instanceof Generic
                    || type instanceof SelfType || type instanceof ClassNamespace
                    || type instanceof Function || type instanceof Types.Not) return false;
            // NULL is an absence sentinel, not owned, transferable data.
            if (type instanceof Primitive primitive) {
                return primitive != Primitive.VOID && primitive != Primitive.NULL;
            }
            if (type instanceof StringLiteral || type instanceof BoolLiteral) return true;

            if (type instanceof Borrow borrow) {
                // &T: Send iff T: Sync; &mut T: Send iff T: Send.
                // Both forms: Sync iff T: Sync (shared access does not grant
                // mutable access through a borrowed mutable reference).
                Trait required = trait == Trait.SYNC || !borrow.mutable()
                        ? Trait.SYNC : Trait.SEND;
                return has(borrow.target(), required, nominalProof, visiting, depth + 1);
            }
            if (type instanceof ListType list) {
                // Array/List/Vector/Slice expose mutable storage. Moving the
                // owning container is Send when its elements are Send, but
                // sharing &List<T> does not provide a Sync proof merely because
                // T is Sync. A future immutable buffer gets its own type.
                return trait == Trait.SEND
                        && has(list.element(), Trait.SEND, nominalProof, visiting, depth + 1);
            }
            if (type instanceof Tuple tuple) {
                // FixedArray/FixedList are mutable aggregate storage. The
                // ordinary Tuple is immutable under shared access.
                if (trait == Trait.SYNC && tuple.kind() != Types.TupleKind.TUPLE) return false;
                for (Type member : tuple.elements()) {
                    if (!has(member, trait, nominalProof, visiting, depth + 1)) return false;
                }
                return true;
            }
            if (type instanceof Record record) {
                // A readonly projection does not freeze a backing allocation:
                // a different writable alias may still mutate its fields.
                // No structural Record has Sync without an authenticated
                // frozen-backing proof. Owned Send remains field-sensitive.
                if (trait == Trait.SYNC) return false;
                for (Type member : record.members().values()) {
                    if (!has(member, Trait.SEND, nominalProof, visiting, depth + 1)) return false;
                }
                return true;
            }
            if (type instanceof Union union) {
                for (Type member : union.options()) {
                    if (!has(member, trait, nominalProof, visiting, depth + 1)) return false;
                }
                return true;
            }
            if (!(type instanceof Named named)) return false;
            String name = named.name();
            // A Types.Named contains only a spelling, not an authenticated
            // intrinsic/symbol identity. Accepting "Option"/"Result" based
            // on that string would grant auto-traits to a shadowing guest
            // nominal class, even if the compiler's nominal callback is
            // permissive. Keep the provisional resolved-type Task admission
            // closed until MIR carries a trusted built-in identity witness.
            if (name.equals("OptionUnwrapError") || name.equals("Option")
                    || name.equals("IteratorResult") || name.equals("Result")) {
                return false;
            }
            // Channel/Task/runtime handles are denied until a separately
            // verified handle ownership ABI exists. Payload Send alone does
            // NOT prove carrier transfer of the enclosing capability.
            if (UNPROVEN_CAPABILITIES.contains(name)) {
                return false;
            }
            // The nominal callback must never turn an unresolved or
            // capability-bearing generic argument into a valid auto-trait.
            // This is intentionally stricter than Rust's PhantomData-style
            // exceptions: a future trusted variance/phantom proof is needed
            // before any argument may be ignored.
            for (Type argument : named.arguments()) {
                if (!has(argument, trait, nominalProof, visiting, depth + 1)) {
                    return false;
                }
            }
            // Unknown nominal types are *not* automatically Send or Sync.
            // The callback is compiler-trusted, but its result is still
            // conditional on all nested type arguments passing the same trait.
            return nominalProof.has(named, trait);
        } finally {
            visiting.remove(type);
        }
    }
}
