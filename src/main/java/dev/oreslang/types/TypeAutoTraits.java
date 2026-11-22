package dev.oreslang.types;

import dev.oreslang.types.AutoTraitRules.AutoTrait;
import dev.oreslang.types.AutoTraitRules.Capabilities;
import dev.oreslang.types.AutoTraitRules.Kind;
import dev.oreslang.types.AutoTraitRules.TypeModel;
import dev.oreslang.types.Types.BoolLiteral;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Not;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.SelfType;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.TupleKind;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Union;
import dev.oreslang.types.Types.Unknown;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Canonical auto-trait classification for compiler-known value shapes.
 *
 * <p>Nominal user classes are intentionally delegated back to TypeChecker,
 * because their field graph and generic substitutions live in the compilation
 * environment. Built-ins, structural values, and smart-pointer wrappers are
 * classified here so async/actor/task gates do not grow independent rules.
 */
public final class TypeAutoTraits {
    private TypeAutoTraits() { }

    private static final Capabilities NONE = new Capabilities(false, false, false);
    private static final Capabilities SCALAR = new Capabilities(true, true, true);

    // The same compiler can encounter source-built type DAGs of extreme depth
    // or width. Failure to prove is never permission to cross a worker boundary.
    private static final int MAX_CLOSED_DEPTH = 256;
    private static final int MAX_CLOSED_STEPS = 100_000;
    private static final int MAX_METADATA_NAME_CHARS = 1_024;

    public static Capabilities inferClosed(Type type) {
        return new ClosedInference().infer(type, 0);
    }

    private static final class ClosedInference {
        private int remaining = MAX_CLOSED_STEPS;
        private final java.util.Set<Type> active = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        // Depth is part of the key: a safe shared DAG at a shallow depth must
        // not hide a later path that exceeds the maximum permitted nesting.
        private final java.util.IdentityHashMap<Type, java.util.Map<Integer, Capabilities>> memo =
                new java.util.IdentityHashMap<>();

        private Capabilities infer(Type type, int depth) {
            if (type == null || depth > MAX_CLOSED_DEPTH || remaining == 0)
                return NONE;
            remaining--;
            var depths = memo.get(type);
            if (depths != null && depths.containsKey(depth)) return depths.get(depth);
            if (!active.add(type)) return NONE;
            try {
                // Reject huge flat metadata before traversing all its children.
                // This also keeps the work budget effective for n-ary tuples.
                int width = type instanceof Tuple tuple ? tuple.elements().size()
                        : type instanceof Union union ? union.options().size()
                        : type instanceof Record record ? record.members().size()
                        : type instanceof Named named ? named.arguments().size() : 0;
                if (width > remaining) return NONE;
                Capabilities result = inferBuiltin(type,
                        child -> infer(child, depth + 1)).orElse(NONE);
                memo.computeIfAbsent(type, ignored -> new java.util.HashMap<>())
                        .put(depth, result);
                return result;
            } finally {
                active.remove(type);
            }
        }
    }

    public static Optional<Capabilities> inferBuiltin(
            Type type,
            java.util.function.Function<Type, Capabilities> nested) {
        if (type == null) return Optional.of(NONE);

        if (type instanceof Primitive primitive) {
            return Optional.of(primitive == Primitive.VOID || primitive == Primitive.NULL
                    ? NONE : SCALAR);
        }
        if (type instanceof StringLiteral literal) {
            return Optional.of(literal.value() == null ? NONE : SCALAR);
        }
        if (type instanceof BoolLiteral) {
            return Optional.of(SCALAR);
        }
        if (type == Unknown.INSTANCE || type instanceof Borrow
                || type instanceof Function || type instanceof ClassNamespace
                || type instanceof SelfType || type instanceof Generic
                || type instanceof Not) {
            return Optional.of(NONE);
        }
        if (type instanceof ListType list) {
            Capabilities child = nested.apply(list.element());
            return Optional.of(new Capabilities(child.send(), false, false));
        }
        if (type instanceof Tuple tuple) {
            Capabilities children = combine(tuple.elements(), nested);
            if (tuple.kind() == TupleKind.TUPLE) return Optional.of(children);
            return Optional.of(new Capabilities(children.send(), false, false));
        }
        if (type instanceof Union union) {
            return Optional.of(combine(union.options(), nested));
        }
        if (type instanceof Record record) {
            // Record field names are metadata too; invalid spelling cannot
            // be treated as a valid movable or shared value by an embedding.
            for (String field : record.members().keySet()) {
                if (field == null || field.isBlank()
                        || field.length() > MAX_METADATA_NAME_CHARS)
                    return Optional.of(NONE);
            }
            Capabilities members = combine(record.members().values().stream().toList(), nested);
            // readOnly is a view capability, not proof of a frozen allocation.
            // A writable alias may still exist (for example after a readonly
            // capability overlay). Match ThreadSafetyTraits: moving owned
            // fields is safe, but sharing this view needs a separate backing
            // allocation/exclusivity proof that Record does not carry.
            return Optional.of(new Capabilities(members.send(), false, false));
        }
        if (!(type instanceof Named named)) return Optional.of(NONE);
        // Types can be constructed directly by compiler transformations. A
        // malformed or overlarge nominal name must not crash auto-trait proof.
        if (named.name() == null || named.name().isBlank()
                || named.name().length() > MAX_METADATA_NAME_CHARS)
            return Optional.of(NONE);

        return switch (named.name()) {
            case "$builtin$Symbol", "ActorId", "ActorKind" ->
                    Optional.of(named.arguments().isEmpty() ? SCALAR : NONE);
            case "ActorRef" ->
                    Optional.of(named.arguments().size() == 1 ? SCALAR : NONE);
            // A NIO socket is an affine, single-owner execution capability:
            // moving it to an independent task is safe, sharing/copying it
            // is not. Listener stays non-Send and remains in the owner task.
            case "NioConnection", "NioReader", "NioWriter", "BinaryFile" ->
                    Optional.of(named.arguments().isEmpty()
                            ? new Capabilities(true, false, false) : NONE);
            case "OptionUnwrapError" ->
                    Optional.of(named.arguments().isEmpty() ? SCALAR : NONE);
            case "Option", "IteratorResult", "ActorMail", "ActorOutput" ->
                    Optional.of(named.arguments().size() == 1
                            ? nested.apply(named.arguments().getFirst())
                            : NONE);
            case "Result" ->
                    Optional.of(named.arguments().size() == 2
                            ? combine(named.arguments(), nested)
                            : NONE);
            // Abstract auto-trait composition (AutoTraitRules) is not executable
            // authority: no native guest allocator/atomic-refcount/ownership
            // lowering has been proven for these constructors. Deny even if T
            // is a safe scalar, and do not let a nested Option/Result launder it.
            case "Atomic", "AtomicRef", "AtomicBool", "AtomicInt", "AtomicLong",
                    "AtomicI8", "AtomicI16", "AtomicI32", "AtomicI64",
                    "AtomicU8", "AtomicU16", "AtomicU32", "AtomicU64",
                    "Box", "Arc", "Weak" -> Optional.of(NONE);
            case "Rc" -> Optional.of(wrapped(named, Kind.RC, false, nested));
            case "RefCell" -> Optional.of(wrapped(named, Kind.REFCELL, false, nested));
            // Mutex<T> is pinned to the actor/execution domain that created it: the
            // runtime refuses to move one across an async task boundary and throws
            // WrongMutexDomainException on foreign access. Rust's "Send if T is Send"
            // would admit programs that can only fail at runtime. SharedMutex is the
            // shareable form.
            case "Mutex" -> Optional.of(NONE);
            case "RwLock" -> Optional.of(wrapped(named, Kind.RWLOCK, false, nested));
            case "SharedMutex" -> {
                if (named.arguments().size() != 1) yield Optional.of(NONE);
                Capabilities child = nested.apply(named.arguments().getFirst());
                // SharedMutex owns the payload and supplies synchronized,
                // explicitly shared backing. It therefore requires Send, not
                // Sync, from T.
                yield Optional.of(new Capabilities(
                        child.send(), child.send(), child.send()));
            }
            // A Channel<T> handle is a runtime-synchronized, bounded transport
            // (like SharedMutex it supplies its own synchronization), so it may
            // be moved or shared between tasks when its payload is Send. This
            // is what lets a request carry its reply channel (CSP style).
            // Actor mail boundaries still reject Channel<T> separately.
            case "Channel" -> {
                if (named.arguments().size() != 1) yield Optional.of(NONE);
                boolean payload = nested.apply(named.arguments().getFirst()).send();
                yield Optional.of(new Capabilities(payload, payload, payload));
            }
            // Bytes v1 is an immutable, move-only owned byte string.
            case "Bytes" -> Optional.of(named.arguments().isEmpty()
                    ? new Capabilities(true, false, false) : NONE);
            // A typed Map owns its keys and values; moving it moves them.
            case "Map" -> {
                if (named.arguments().size() != 2) yield Optional.of(NONE);
                Capabilities entries = combine(named.arguments(), nested);
                yield Optional.of(new Capabilities(entries.send(), false, false));
            }
            case "ActorGroup", "Topic", "Subscription",
                    "Future", "Iterator", "AsyncIterator",
                    "MutexGuard", "SelectCase", "SelectSet", "SelectResult",
                    "TcpConnection", "HttpExchange", "HttpServer",
                    "HttpHandler", "HttpResponse", "HttpResponseDraft" ->
                    Optional.of(NONE);
            default -> Optional.empty();
        };
    }

    private static Capabilities wrapped(
            Named named,
            Kind kind,
            boolean shareableDomain,
            java.util.function.Function<Type, Capabilities> nested) {
        if (named.arguments().size() != 1) return NONE;
        Capabilities child = nested.apply(named.arguments().getFirst());
        return AutoTraitRules.infer(TypeModel.wrapped(
                kind, modelFromCapabilities(child), shareableDomain));
    }

    static TypeModel modelFromCapabilities(Capabilities capabilities) {
        EnumSet<AutoTrait> denied = EnumSet.noneOf(AutoTrait.class);
        if (!capabilities.send()) denied.add(AutoTrait.SEND);
        if (!capabilities.sync()) denied.add(AutoTrait.SYNC);
        if (!capabilities.share()) denied.add(AutoTrait.SHARE);
        return new TypeModel(
                Kind.NOMINAL,
                List.of(),
                java.util.Set.of(),
                capabilities.share(),
                denied);
    }

    private static Capabilities combine(
            List<? extends Type> values,
            java.util.function.Function<Type, Capabilities> nested) {
        boolean send = true;
        boolean sync = true;
        boolean share = true;
        for (Type value : values) {
            Capabilities capabilities = nested.apply(value);
            send &= capabilities.send();
            sync &= capabilities.sync();
            share &= capabilities.share();
        }
        return new Capabilities(send, sync, share);
    }
}

