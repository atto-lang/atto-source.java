package dev.oreslang.types;

import dev.oreslang.types.Types.*;
import dev.oreslang.types.Types.Record;

import java.util.Objects;

/**
 * Conservative structural proof of cross-execution-context transfer.
 *
 * <p>Send permits moving an owned value to another logical Task/worker;
 * Sync permits sending a shared borrow. Neither permits an extra mutable
 * alias, bypasses actor trust domains, or substitutes for a lock.</p>
 *
 * <p>This is a type-level proof helper, not a guest implementation of Arc,
 * Mutex, RwLock, or Send/Sync source trait declarations. A nominal resolver
 * must validate ALL instance fields/parents and must fail closed on unknown
 * host capabilities. Callers also enforce ownership and lifetime rules.</p>
 */
public final class SendSyncTraits {
    private SendSyncTraits() { }

    public enum Trait { SEND, SYNC }

    /** A trusted compiler callback, not an arbitrary user-provided trait assertion. */
    @FunctionalInterface
    public interface NominalProof {
        boolean proven(Types.Named type, Trait trait);
    }

    public static boolean isSend(Type type, NominalProof nominal) {
        return satisfies(type, Trait.SEND, nominal);
    }

    public static boolean isSync(Type type, NominalProof nominal) {
        return satisfies(type, Trait.SYNC, nominal);
    }

    /**
     * Transfer an owned value to an independent task with no scoped lifetime
     * token. A borrowed type can be Send in Rust, but its *lifetime* must be
     * proven separately. Until Oreslang has scoped-Task lowering, reject
     * borrowed values even when nested in a tuple, option, or nominal generic.
     */
    public static boolean isOwnedSend(Type type, NominalProof nominal) {
        return !hasEmbeddedBorrow(type, 0) && isSend(type, nominal);
    }

    private static boolean hasEmbeddedBorrow(Type type, int depth) {
        if (depth > 256) return true;
        int next = depth + 1;
        if (type instanceof Borrow) return true;
        if (type instanceof ListType list) return hasEmbeddedBorrow(list.element(), next);
        if (type instanceof Tuple tuple) {
            for (Type part : tuple.elements())
                if (hasEmbeddedBorrow(part, next)) return true;
        } else if (type instanceof Union union) {
            for (Type part : union.options())
                if (hasEmbeddedBorrow(part, next)) return true;
        } else if (type instanceof Record record) {
            for (Type field : record.members().values())
                if (hasEmbeddedBorrow(field, next)) return true;
        } else if (type instanceof Named named) {
            for (Type argument : named.arguments())
                if (hasEmbeddedBorrow(argument, next)) return true;
        }
        // A user nominal's hidden fields are independently audited by the
        // compiler's NominalProof; absence of generic borrow types is not
        // permission to skip that second check.
        return false;
    }

    public static boolean satisfies(Type type, Trait trait, NominalProof nominal) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(trait, "trait");
        Objects.requireNonNull(nominal, "nominal proof");
        return derive(type, trait, nominal, 0);
    }

    private static boolean derive(Type type, Trait trait, NominalProof nominal, int depth) {
        // Disallow adversarially deep structural signatures. Nominal cycles
        // are handled by the compiler's class-graph checker, not this bound.
        if (depth > 256) return false;
        int next = depth + 1;
        if (type instanceof Primitive primitive) return primitive != Primitive.VOID;
        if (type instanceof StringLiteral || type instanceof BoolLiteral) return true;
        if (type instanceof Borrow borrow) {
            // The caller must additionally prove the borrow lives long enough.
            // &T: Send iff T: Sync; &mut T: Send iff T: Send.
            // &T and &mut T are Sync iff T: Sync.
            Trait required = trait == Trait.SEND && !borrow.mutable()
                    ? Trait.SYNC : trait;
            return derive(borrow.target(), required, nominal, next);
        }
        if (type instanceof ListType list) {
            return derive(list.element(), trait, nominal, next);
        }
        if (type instanceof Tuple tuple) {
            for (Type element : tuple.elements())
                if (!derive(element, trait, nominal, next)) return false;
            return true;
        }
        if (type instanceof Union union) {
            for (Type alternative : union.options())
                if (!derive(alternative, trait, nominal, next)) return false;
            return true;
        }
        if (type instanceof Record record) {
            for (Type element : record.members().values())
                if (!derive(element, trait, nominal, next)) return false;
            return true;
        }
        if (type instanceof Generic || type instanceof SelfType
                || type instanceof Function || type instanceof ClassNamespace
                || type instanceof Not || type instanceof Unknown) return false;
        if (!(type instanceof Named named)) return false;

        String name = named.name();
        var args = named.arguments();
        switch (name) {
            case "Rc", "RcWeak", "MutexGuard", "ReadGuard",
                    "WriteGuard", "RawPointer", "NativePointer", "ThreadLocal",
                    "TcpConnection", "HttpExchange", "HttpServer", "HttpHandler",
                    "Future", "Iterator", "AsyncIterator", "ActorRef" -> {
                return false; // No guest proof for these capabilities.
            }
            case "Cell", "RefCell" -> {
                // Rust-style interior mutability: movable if T is Send,
                // but not Sync without a synchronizing wrapper.
                return trait == Trait.SEND && args.size() == 1
                        && derive(args.getFirst(), Trait.SEND, nominal, next);
            }
            case "Arc", "Weak" -> {
                // Atomic reference-count ownership is freely clonable. Both
                // moving and sharing the handle need T: Send + Sync.
                return args.size() == 1
                        && derive(args.getFirst(), Trait.SEND, nominal, next)
                        && derive(args.getFirst(), Trait.SYNC, nominal, next);
            }
            case "RwLock" -> {
                // The lock can itself be moved when T: Send. It can only
                // be shared concurrently if T is ALSO Sync, because an
                // admitted reader exposes &T to other execution contexts.
                return args.size() == 1
                        && derive(args.getFirst(), Trait.SEND, nominal, next)
                        && (trait == Trait.SEND
                                || derive(args.getFirst(), Trait.SYNC, nominal, next));
            }
            case "Mutex", "SharedMutex", "Channel" -> {
                // A mutex/channel can transport a non-Sync but Send payload.
                // Runtime availability and permissions are separate gates.
                return args.size() == 1
                        && derive(args.getFirst(), Trait.SEND, nominal, next);
            }
            case "Option", "IteratorResult", "List", "Array", "Vector",
                    "Slice", "FixedArray", "FixedList" -> {
                return args.size() == 1
                        && derive(args.getFirst(), trait, nominal, next);
            }
            case "Result" -> {
                return args.size() == 2
                        && derive(args.get(0), trait, nominal, next)
                        && derive(args.get(1), trait, nominal, next);
            }
            case "OptionUnwrapError" -> {
                return args.isEmpty();
            }
            default -> {
                // Never mark an unknown nominal Send/Sync by name, even if
                // its generic arguments happen to be Send/Sync.
                return nominal.proven(named, trait);
            }
        }
    }
}
