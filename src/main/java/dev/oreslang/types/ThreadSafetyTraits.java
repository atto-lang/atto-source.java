package dev.oreslang.types;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
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
 * An erased dyn interface currently carries no Send/Sync auto-trait bound,
 * so it is always denied, even for a nominal callback claiming it safe.
 * Arc/Weak likewise remain denied until trusted intrinsic lowering exists.
 */
public final class ThreadSafetyTraits {
    private ThreadSafetyTraits() { }

    // Bound recursive compiler queries on attacker-controlled nested type input.
    private static final int MAX_TYPE_DEPTH = 256;
    // Depth limits Java stack usage; this separate budget caps very wide types
    // and repeated DAG traversals. Restored from the canonical #1439 proof engine.
    private static final int MAX_PROOF_STEPS = 100_000;
    private static final Set<String> UNPROVEN_CAPABILITIES = Set.of(
            "Mutex", "MutexGuard", "SharedMutex", "Future",
            "ActorRef", "ActorMail", "ActorOutput", "ActorOutputStream",
            "SelectCase", "SelectSet", "SelectResult",
            "Iterator", "AsyncIterator", "TcpConnection", "HttpExchange",
            "HttpServer", "HttpHandler", "ExecutionPermit",
            "ReadLoan", "WriteLoan", "PartitionLoan",
            // None of these may be trusted from a user-authored nominal name.
            // Arc/Weak are NOT guest-lowered yet; their future Send/Sync proofs
            // must depend on an independently verified allocation/control-block ABI.
            "Arc", "Weak", "Rc", "RcWeak", "Cell", "RefCell",
            "RwLock", "ReadGuard", "WriteGuard", "RawPointer",
            "NativePointer", "ThreadLocal", "NativeHandle",
            // Task/actor/FFI and unlowered shared-state capabilities must not
            // be made thread-safe by nominal classes spoofing their names.
            "ActorGroup", "Supervisor", "ActorContext", "ActorState",
            "Task", "ThreadBound", "Pointer", "OresPointer", "FfiHandle",
            "RwLockGuard", "BorrowGuard", "Shared", "AtomicRef",
            "TRef", "Ref", "UnsafeCell");

    public enum Trait { SEND, SYNC }

    // Keep depth-keyed memoization collision-free if the trusted trait set grows.
    private static final int TRAIT_COUNT = Trait.values().length;

    @FunctionalInterface
    public interface NominalProof {
        boolean has(Named type, Trait trait);

        /**
         * Only the compiler's resolved intrinsic registry may grant this proof.
         * A user-defined class or alias named Option/Result/Channel is not a
         * built-in, regardless of its arguments or nominal Send/Sync result.
         */
        default boolean trustedIntrinsic(Named type) {
            return false;
        }
    }

    public static boolean has(Type type, Trait trait, NominalProof nominalProof) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(trait, "trait");
        Objects.requireNonNull(nominalProof, "nominalProof");
        return has(type, trait, nominalProof,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new IdentityHashMap<>(), new int[] {MAX_PROOF_STEPS}, 0);
    }

    /**
     * Detached tasks receive owned values. A Send borrow alone is insufficient:
     * its source lifetime might end before the task physically becomes quiescent.
     * Scope-bound Task loans require a separate, independently checked path.
     */
    public static boolean isOwnedSend(Type type, NominalProof nominalProof) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(nominalProof, "nominalProof");
        return !hasDetachedOwnershipHazard(type) && has(type, Trait.SEND, nominalProof);
    }

    /**
     * Iterative preflight avoids Java-stack overflow on adversarial nested types,
     * and bounds the number of visited nodes. Detached Tasks require more than
     * a structural Send trait: borrows and readonly record capabilities can both
     * denote aliases whose backing storage remains live in another execution
     * context. Until the ownership checker carries a compiler-minted frozen/
     * exclusive-backing witness, both forms fail closed here.
     */
    private static boolean hasDetachedOwnershipHazard(Type root) {
        record Pending(Type type, int depth) { }
        java.util.ArrayDeque<Pending> pending = new java.util.ArrayDeque<>();
        Set<Type> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.push(new Pending(root, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            Pending current = pending.pop();
            Type type = current.type();
            if (type == null || current.depth() > MAX_TYPE_DEPTH
                    || ++nodes > 4096) return true;
            if (!visited.add(type)) continue;
            if (type instanceof Borrow) return true;
            // TypeChecker.overlayReadonlyCapabilities can turn writable storage
            // into a readonly view without proving the backing allocation frozen
            // or uniquely owned. Moving that view to a detached Task could race
            // an alias left behind, so provenance must be proved separately.
            if (type instanceof Record record && record.readOnly()) return true;
            int next = current.depth() + 1;
            if (type instanceof Named named) {
                for (Type arg : named.arguments()) pending.push(new Pending(arg, next));
            } else if (type instanceof Types.Dyn dyn) {
                pending.push(new Pending(dyn.interfaceType(), next));
            } else if (type instanceof ListType list) {
                pending.push(new Pending(list.element(), next));
            } else if (type instanceof Tuple tuple) {
                for (Type element : tuple.elements()) pending.push(new Pending(element, next));
            } else if (type instanceof Record record) {
                for (Type element : record.members().values()) pending.push(new Pending(element, next));
            } else if (type instanceof Union union) {
                for (Type element : union.options()) pending.push(new Pending(element, next));
            } else if (type instanceof Function function) {
                for (Type param : function.parameters()) pending.push(new Pending(param, next));
                pending.push(new Pending(function.result(), next));
            } else if (type instanceof SelfType self) {
                pending.push(new Pending(self.bound(), next));
            } else if (type instanceof Types.Not not) {
                pending.push(new Pending(not.excluded(), next));
            }
        }
        return false;
    }

    private static boolean has(
            Type type, Trait trait, NominalProof nominalProof, Set<Type> visiting,
            IdentityHashMap<Type, Map<Integer, Boolean>> proven, int[] steps, int depth) {
        if (type == null || depth > MAX_TYPE_DEPTH || --steps[0] < 0) return false;
        // Active-cycle detection must precede memoization; a cached proof may
        // never turn a recursive cycle into a positive result.
        if (!visiting.add(type)) return false;
        try {
            // A proof reached at a shallow depth cannot authorize the same
            // physical type node when another path reaches it deeper. Otherwise
            // identity memoization can bypass MAX_TYPE_DEPTH on shared DAGs.
            int proofKey = depth * TRAIT_COUNT + trait.ordinal();
            Map<Integer, Boolean> cached = proven.get(type);
            if (cached != null && cached.containsKey(proofKey)) {
                return cached.get(proofKey);
            }
            boolean result = derive(
                    type, trait, nominalProof, visiting, proven, steps, depth);
            proven.computeIfAbsent(type, ignored -> new HashMap<>())
                    .put(proofKey, result);
            return result;
        } finally {
            visiting.remove(type);
        }
    }

    private static boolean derive(
            Type type, Trait trait, NominalProof nominalProof, Set<Type> visiting,
            IdentityHashMap<Type, Map<Integer, Boolean>> proven, int[] steps, int depth) {
        if (type == Unknown.INSTANCE || type instanceof Generic
                || type instanceof Types.Dyn
                || type instanceof SelfType || type instanceof ClassNamespace
                || type instanceof Function || type instanceof Types.Not) return false;
        if (type instanceof Primitive primitive) return primitive != Primitive.VOID;
        if (type instanceof StringLiteral || type instanceof BoolLiteral) return true;

        if (type instanceof Borrow borrow) {
            // &T: Send iff T: Sync; &mut T: Send iff T: Send.
            // Both forms: Sync iff T: Sync.
            Trait required = trait == Trait.SYNC || !borrow.mutable()
                    ? Trait.SYNC : Trait.SEND;
            return has(borrow.target(), required, nominalProof,
                    visiting, proven, steps, depth + 1);
        }
        if (type instanceof ListType list) {
            // Mutable storage may move when its elements are Send, but shared
            // access does not gain Sync merely from element traits.
            return trait == Trait.SEND
                    && has(list.element(), Trait.SEND, nominalProof,
                            visiting, proven, steps, depth + 1);
        }
        if (type instanceof Tuple tuple) {
            if (trait == Trait.SYNC && tuple.kind() != Types.TupleKind.TUPLE) return false;
            for (Type member : tuple.elements()) {
                if (!has(member, trait, nominalProof,
                        visiting, proven, steps, depth + 1)) return false;
            }
            return true;
        }
        if (type instanceof Record record) {
            // readOnly is a capability/view, not a frozen-backing witness.
            if (trait == Trait.SYNC) return false;
            for (Type member : record.members().values()) {
                if (!has(member, trait, nominalProof,
                        visiting, proven, steps, depth + 1)) return false;
            }
            return true;
        }
        if (type instanceof Union union) {
            for (Type member : union.options()) {
                if (!has(member, trait, nominalProof,
                        visiting, proven, steps, depth + 1)) return false;
            }
            return true;
        }
        if (!(type instanceof Named named)) return false;
        String name = named.name();
        if (name.equals("OptionUnwrapError")) {
            return nominalProof.trustedIntrinsic(named) && named.arguments().isEmpty();
        }
        if (name.equals("Option") || name.equals("IteratorResult")) {
            return nominalProof.trustedIntrinsic(named) && named.arguments().size() == 1
                    && has(named.arguments().getFirst(), trait, nominalProof,
                            visiting, proven, steps, depth + 1);
        }
        if (name.equals("Result")) {
            return nominalProof.trustedIntrinsic(named) && named.arguments().size() == 2
                    && has(named.arguments().get(0), trait, nominalProof,
                            visiting, proven, steps, depth + 1)
                    && has(named.arguments().get(1), trait, nominalProof,
                            visiting, proven, steps, depth + 1);
        }
        if (name.equals("Channel")) {
            return trait == Trait.SEND && nominalProof.trustedIntrinsic(named)
                    && named.arguments().size() == 1
                    && has(named.arguments().getFirst(), Trait.SEND, nominalProof,
                            visiting, proven, steps, depth + 1);
        }
        if (UNPROVEN_CAPABILITIES.contains(name)) return false;

        // A nominal proof never excuses unsafe or unresolved generic arguments.
        for (Type argument : named.arguments()) {
            if (!has(argument, trait, nominalProof,
                    visiting, proven, steps, depth + 1)) return false;
        }
        return nominalProof.has(named, trait);
    }

}
