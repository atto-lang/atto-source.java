package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative AST-only Send/Sync shape preflight.
 *
 * <p>This walker is deliberately NOT a permission authority. A positive result
 * only means the unresolved syntax is not rejected before semantic resolution.
 * Canonical Send/Sync and detached-transfer permission must be decided later
 * from resolved Types.Type by ThreadSafetyTraits on the convergence branch.
 *
 * <p>Actor Sendable and wire Serializable are separate. No user-asserted unsafe
 * trait implementation is consulted here.
 */
public final class SendSyncTraits {
    private static final int MAX_TYPE_DERIVATION_DEPTH = 64;
    private static final int MAX_TYPE_NODES = 4096;
    private record PendingType(Ast.TypeRef type, int depth) { }
    public record Traits(boolean send, boolean sync) {
        public static final Traits NONE = new Traits(false, false);
        public static final Traits BOTH = new Traits(true, true);
    }
    private record Key(Ast.TypeRef type, Map<String, Ast.TypeRef> substitutions) { }
    private static final Set<String> SCALARS = Set.of(
            "void", "bool", "Bool", "i8", "i16", "i32", "i64",
            "u8", "u16", "u32", "u64", "int", "uint", "bigint",
            "f32", "f64", "float", "decimal", "complex64", "complex128",
            "complex", "string", "String");
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Set<String> ambiguous = new HashSet<>();
    private final Set<String> shadowedTypeNames = new HashSet<>();
    private final Set<String> trustedIntrinsicNames;

    /**
     * Default guest-facing mode: unlowered synchronization and region constructors
     * are NOT admitted merely because their source spelling resembles Rust.
     */
    public SendSyncTraits(Ast.Program program) {
        this(program, Set.of());
    }

    /**
     * Trusted compiler entry point only. Callers must independently prove each
     * enabled constructor resolves to an implemented, safe runtime intrinsic.
     * Never populate this set from source annotations or type spelling alone.
     */
    public SendSyncTraits(Ast.Program program, Set<String> trustedIntrinsicNames) {
        this.trustedIntrinsicNames = Set.copyOf(
                Objects.requireNonNull(trustedIntrinsicNames, "trustedIntrinsicNames"));
        if (!Set.of("Arc", "Weak", "Shared", "Mutex", "RwLock",
                    "Array", "List", "InvertedArray", "Option", "Result").containsAll(this.trustedIntrinsicNames)) {
            throw new IllegalArgumentException("unrecognized trusted intrinsic");
        }
        Objects.requireNonNull(program, "program");
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass) {
                    shadowedTypeNames.add(klass.name());
                    if (classes.putIfAbsent(klass.name(), klass) != null)
                        ambiguous.add(klass.name());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    shadowedTypeNames.add(alias.name());
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    shadowedTypeNames.add(iface.name());
                }
            }
        }
    }
    public Traits derivePreflight(Ast.TypeRef type) {
        return derive(type, Map.of(), new HashSet<>());
    }

    /** Positive means only "not rejected before resolution", never permission. */
    public boolean mayBeSendAfterResolution(Ast.TypeRef type) {
        return derivePreflight(type).send();
    }

    /** Positive means only "not rejected before resolution", never permission. */
    public boolean mayBeSyncAfterResolution(Ast.TypeRef type) {
        return derivePreflight(type).sync();
    }

    private Traits derive(Ast.TypeRef type, Map<String, Ast.TypeRef> substitutions, Set<Key> active) {
        if (type == null || active.size() >= MAX_TYPE_DERIVATION_DEPTH
                || !boundedTypeShape(type)
                || type.inferArguments() || type.isComplement()
                || type.name().equals("$infer$")) return Traits.NONE;
        Ast.TypeRef replacement = substitutions.get(type.name());
        if (replacement != null && type.arguments().isEmpty()) {
            return derive(replacement, Map.of(), active);
        }
        Key key = new Key(type, substitutions);
        // Unknown cyclic layouts fail closed pending independently verified indirection.
        if (!active.add(key)) return Traits.NONE;
        try {
            if (type.isBorrow()) {
                Traits target = derive(type.borrowedTarget(), substitutions, active);
                // &T: Send iff T: Sync; &mut T: Send iff T: Send.
                return new Traits(type.mutableBorrow() ? target.send() : target.sync(), target.sync());
            }
            if (type.isUnion() || type.isTupleType()) {
                return all(type.arguments(), substitutions, active);
            }
            if (type.arguments().isEmpty()
                    && (type.isStringLiteral() || type.isIntLiteral() || type.isBoolLiteral()
                        || (SCALARS.contains(type.name())
                            && !shadowedTypeNames.contains(type.name())))) {
                return Traits.BOTH;
            }

            List<Ast.TypeRef> args = type.arguments();
            // Source-declared classes, aliases, and interfaces cannot impersonate
            // trusted compiler-intrinsic constructors.
            boolean trusted = trustedIntrinsicNames.contains(type.name())
                    && !shadowedTypeNames.contains(type.name());
            switch (type.name()) {
                case "Option", "Array", "List", "InvertedArray" -> {
                    if (args.size() != 1 || !trusted)
                        return Traits.NONE;
                    Traits element = derive(args.getFirst(), substitutions, active);
                    if (type.name().equals("Option")) return element;
                    // Arrays, Lists and inverted storage can cross a Task boundary
                    // only after their own runtime ownership/move contract is proven.
                    return trusted ? new Traits(element.send(), false) : Traits.NONE;
                }
                case "Result" -> {
                    return args.size() == 2 && trusted
                            ? all(args, substitutions, active) : Traits.NONE;
                }
                case "Arc", "Weak", "Shared" -> {
                    if (!trusted || args.size() != 1) return Traits.NONE;
                    Traits inner = derive(args.getFirst(), substitutions, active);
                    return inner.send() && inner.sync() ? Traits.BOTH : Traits.NONE;
                }
                case "Mutex" -> {
                    if (!trusted || args.size() != 1) return Traits.NONE;
                    return derive(args.getFirst(), substitutions, active).send()
                            ? Traits.BOTH : Traits.NONE;
                }
                case "RwLock" -> {
                    if (!trusted || args.size() != 1) return Traits.NONE;
                    Traits inner = derive(args.getFirst(), substitutions, active);
                    return inner.send() && inner.sync() ? Traits.BOTH : Traits.NONE;
                }
                // Do not derive Task Send/Sync from an unverified runtime
                // authority, including a user class spoofing its type name.
                // A local Atomic<int> is thread-safe, but its guest shared-domain
                // authority/transport and lifetime have not been lowered yet.
                // Refuse implicit cross-actor transfer until that proof exists.
                case "Atomic", "AtomicCompareExchange", "Rc", "MutexGuard", "RwLockGuard", "ActorState", "RawPointer",
                        "Pointer", "OresPointer", "ThreadLocal", "ThreadBound",
                        "NativeHandle", "FfiHandle", "ExecutionPermit",
                        "ActorRef", "ActorMail", "ActorOutput", "ActorOutputStream",
                        "ActorGroup", "Supervisor", "ActorContext",
                        "Task", "Future", "Channel",
                        "ReadLoan", "WriteLoan", "PartitionLoan", "BorrowGuard",
                        "SelectCase", "SelectSet", "SelectResult",
                        "Iterator", "AsyncIterator", "TcpConnection",
                        "HttpExchange", "HttpServer", "HttpHandler" -> {
                    return Traits.NONE;
                }
                case "Cell", "RefCell" -> {
                    // No verified source-level move/alias contract yet.
                    return Traits.NONE;
                }
                default -> { }
            }

            Ast.ClassDecl klass = classes.get(type.name());
            if (klass == null || ambiguous.contains(type.name())
                    || klass.isAbstract()
                    || klass.actorKind() != Ast.ActorKind.NONE
                    || klass.genericParameters().size() != args.size()
                    || !klass.parents().isEmpty() || !klass.interfaces().isEmpty())
                return Traits.NONE;
            Map<String, Ast.TypeRef> bindings = new HashMap<>();
            for (int i = 0; i < args.size(); i++) {
                bindings.put(klass.genericParameters().get(i),
                        substitute(args.get(i), substitutions));
            }
            // A generic argument cannot smuggle unsupported capabilities through
            // a class specialization, even when its fields are currently empty.
            Traits parameters = args.isEmpty() ? Traits.BOTH
                    : all(args, substitutions, active);
            boolean send = parameters.send(), sync = parameters.sync();
            for (Ast.FieldDecl field : klass.fields()) {
                Traits child = derive(field.type(), bindings, active);
                send &= child.send();
                // Conservatively reject hidden mutable referents from Sync.
                sync &= child.sync() && !field.mutableReferent();
            }
            return new Traits(send, sync);
        } finally {
            active.remove(key);
        }
    }

    /**
     * Validate external/linked type graphs without calling recursive TypeRef
     * hashCode/equals first. An excessively deep AST must not crash javac's
     * compiler host through recursive record hashing.
     */
    private static boolean boundedTypeShape(Ast.TypeRef root) {
        java.util.ArrayDeque<PendingType> stack = new java.util.ArrayDeque<>();
        stack.push(new PendingType(root, 0));
        int visited = 0;
        while (!stack.isEmpty()) {
            PendingType item = stack.pop();
            if (item.type() == null || item.depth() >= MAX_TYPE_DERIVATION_DEPTH
                    || ++visited > MAX_TYPE_NODES) return false;
            for (Ast.TypeRef child : item.type().arguments()) {
                if (child == null) return false;
                stack.push(new PendingType(child, item.depth() + 1));
            }
        }
        return true;
    }

    private static Ast.TypeRef substitute(Ast.TypeRef type,
                                          Map<String, Ast.TypeRef> substitutions) {
        return substitute(type, substitutions, 0);
    }

    private static Ast.TypeRef substitute(Ast.TypeRef type,
                                          Map<String, Ast.TypeRef> substitutions, int depth) {
        if (type == null || depth >= MAX_TYPE_DERIVATION_DEPTH)
            return Ast.TypeRef.inferred();
        if (substitutions.containsKey(type.name()) && type.arguments().isEmpty()) {
            return substitutions.get(type.name());
        }
        if (type.arguments().isEmpty()) return type;
        List<Ast.TypeRef> args = new ArrayList<>(type.arguments().size());
        for (Ast.TypeRef arg : type.arguments())
            args.add(substitute(arg, substitutions, depth + 1));
        return new Ast.TypeRef(type.name(), args, type.inferArguments());
    }
    private Traits all(List<Ast.TypeRef> types,
                       Map<String, Ast.TypeRef> substitutions, Set<Key> active) {
        if (types.isEmpty()) return Traits.NONE;
        boolean send = true, sync = true;
        for (Ast.TypeRef type : types) {
            Traits child = derive(type, substitutions, active);
            send &= child.send();
            sync &= child.sync();
        }
        return new Traits(send, sync);
    }
}
