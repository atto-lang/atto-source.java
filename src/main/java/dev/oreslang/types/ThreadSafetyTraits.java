package dev.oreslang.types;

import java.util.HashSet;
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

    public enum Trait { SEND, SYNC }

    @FunctionalInterface
    public interface NominalProof {
        boolean has(Named type, Trait trait);
    }

    public static boolean has(Type type, Trait trait, NominalProof nominalProof) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(trait, "trait");
        Objects.requireNonNull(nominalProof, "nominalProof");
        return has(type, trait, nominalProof, new HashSet<>());
    }

    private static boolean has(Type type, Trait trait, NominalProof nominalProof, Set<Type> visiting) {
        // Never infer thread safety from cycles, unresolved types or missing
        // generic bounds. Genuine recursive nominal definitions need a separate
        // fixed-point proof over their field graph.
        if (!visiting.add(type)) return false;
        try {
            if (type == Unknown.INSTANCE || type instanceof Generic
                    || type instanceof SelfType || type instanceof ClassNamespace
                    || type instanceof Function || type instanceof Types.Not) return false;
            if (type instanceof Primitive primitive) return primitive != Primitive.VOID;
            if (type instanceof StringLiteral || type instanceof BoolLiteral) return true;

            if (type instanceof Borrow borrow) {
                // &T: Send iff T: Sync; &mut T: Send iff T: Send.
                // Both forms: Sync iff T: Sync (shared access does not grant
                // mutable access through a borrowed mutable reference).
                Trait required = trait == Trait.SYNC || !borrow.mutable()
                        ? Trait.SYNC : Trait.SEND;
                return has(borrow.target(), required, nominalProof, visiting);
            }
            if (type instanceof ListType list) {
                return has(list.element(), trait, nominalProof, visiting);
            }
            if (type instanceof Tuple tuple) {
                return tuple.elements().stream().allMatch(
                        member -> has(member, trait, nominalProof, visiting));
            }
            if (type instanceof Record record) {
                return record.members().values().stream().allMatch(
                        member -> has(member, trait, nominalProof, visiting));
            }
            if (type instanceof Union union) {
                return union.options().stream().allMatch(
                        member -> has(member, trait, nominalProof, visiting));
            }
            if (!(type instanceof Named named)) return false;
            String name = named.name();
            if (name.equals("OptionUnwrapError")) return named.arguments().isEmpty();
            if (name.equals("Option") || name.equals("IteratorResult")) {
                return named.arguments().size() == 1
                        && has(named.arguments().getFirst(), trait, nominalProof, visiting);
            }
            if (name.equals("Result")) {
                return named.arguments().size() == 2
                        && has(named.arguments().get(0), trait, nominalProof, visiting)
                        && has(named.arguments().get(1), trait, nominalProof, visiting);
            }
            // Oreslang's Channel is an owned synchronized transport. The
            // payload must be Send to cross its worker boundary. Do NOT claim
            // Sync for the handle until the underlying runtime sharing proof
            // and immutable-reference API have been audited separately.
            if (name.equals("Channel")) {
                return trait == Trait.SEND && named.arguments().size() == 1
                        && has(named.arguments().getFirst(), Trait.SEND, nominalProof, visiting);
            }
            if (Set.of("Mutex", "MutexGuard", "SharedMutex", "Future",
                    "ActorRef", "ActorMail", "ActorOutput", "ActorOutputStream",
                    "SelectCase", "SelectSet", "SelectResult",
                    "Iterator", "AsyncIterator", "TcpConnection", "HttpExchange",
                    "HttpServer", "HttpHandler", "ExecutionPermit",
                    "ReadLoan", "WriteLoan", "PartitionLoan").contains(name)) {
                return false;
            }
            // Unknown nominal types are *not* automatically Send or Sync.
            // Only a compiler-backed proof of the class's actual fields can
            // allow a transfer. This also avoids trait spoofing by type names.
            return nominalProof.has(named, trait);
        } finally {
            visiting.remove(type);
        }
    }
}
