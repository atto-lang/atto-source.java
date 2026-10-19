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
 * Conservative compiler-owned Send/Sync auto-trait inference for Oreslang
 * logical Tasks and threads. Actor Sendable and wire Serializable are separate.
 * A Send borrow still needs independently checked lifetime/physical quiescence.
 * No user-asserted unsafe trait implementation is consulted here.
 */
public final class SendSyncTraits {
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

    public SendSyncTraits(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass
                        && classes.putIfAbsent(klass.name(), klass) != null) {
                    ambiguous.add(klass.name());
                }
            }
        }
    }
    public Traits derive(Ast.TypeRef type) {
        return derive(type, Map.of(), new HashSet<>());
    }
    public boolean isSend(Ast.TypeRef type) { return derive(type).send(); }
    public boolean isSync(Ast.TypeRef type) { return derive(type).sync(); }
    public void requireSend(Ast.TypeRef type) {
        if (!isSend(type)) throw new IllegalArgumentException(
                "type is not Send across Task execution contexts: " + type);
    }
    public void requireSync(Ast.TypeRef type) {
        if (!isSync(type)) throw new IllegalArgumentException(
                "type is not Sync for shared Task borrows: " + type);
    }

    private Traits derive(Ast.TypeRef type, Map<String, Ast.TypeRef> substitutions, Set<Key> active) {
        if (type == null || type.inferArguments() || type.isComplement()
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
            if (type.isStringLiteral() || type.isIntLiteral() || type.isBoolLiteral()
                    || SCALARS.contains(type.name()) && type.arguments().isEmpty()) {
                return Traits.BOTH;
            }

            List<Ast.TypeRef> args = type.arguments();
            switch (type.name()) {
                case "Option", "Array", "List", "InvertedArray" -> {
                    if (args.size() != 1) return Traits.NONE;
                    Traits element = derive(args.getFirst(), substitutions, active);
                    // Collection shared aliases remain !Sync until checked borrows/regions.
                    return type.name().equals("Option") ? element
                            : new Traits(element.send(), false);
                }
                case "Result" -> {
                    return args.size() == 2 ? all(args, substitutions, active) : Traits.NONE;
                }
                case "Arc", "Weak", "Shared" -> {
                    if (args.size() != 1) return Traits.NONE;
                    Traits inner = derive(args.getFirst(), substitutions, active);
                    return inner.send() && inner.sync() ? Traits.BOTH : Traits.NONE;
                }
                case "Mutex" -> {
                    if (args.size() != 1) return Traits.NONE;
                    return derive(args.getFirst(), substitutions, active).send()
                            ? Traits.BOTH : Traits.NONE;
                }
                case "RwLock" -> {
                    if (args.size() != 1) return Traits.NONE;
                    Traits inner = derive(args.getFirst(), substitutions, active);
                    return inner.send() && inner.sync() ? Traits.BOTH : Traits.NONE;
                }
                case "Rc", "MutexGuard", "RwLockGuard", "ActorState", "RawPointer",
                        "ThreadLocal", "NativeHandle", "FfiHandle", "ExecutionPermit" -> {
                    return Traits.NONE;
                }
                case "Cell", "RefCell" -> {
                    if (args.size() != 1) return Traits.NONE;
                    return new Traits(derive(args.getFirst(), substitutions, active).send(), false);
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

    private static Ast.TypeRef substitute(Ast.TypeRef type,
                                          Map<String, Ast.TypeRef> substitutions) {
        if (substitutions.containsKey(type.name()) && type.arguments().isEmpty()) {
            return substitutions.get(type.name());
        }
        if (type.arguments().isEmpty()) return type;
        List<Ast.TypeRef> args = new ArrayList<>(type.arguments().size());
        for (Ast.TypeRef arg : type.arguments()) args.add(substitute(arg, substitutions));
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
