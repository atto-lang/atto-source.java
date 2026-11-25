package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Restricted, source-checked affine drop elaboration. This is compiler IR,
 * not a native destructor ABI or permission to run an unchecked artifact.
 * No calls, allocation, loans, loops, exceptions, suspension or reassignments
 * are admitted. Each branch has its own continuation, so a maybe-moved owner
 * is dropped only on paths that still own it, without a runtime drop flag.
 */
public final class AffineDropPlanV1 {
    private static final int MAX_STEPS = 4096;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_SLOTS = 256;
    public static final int RETURN_SLOT = 0;

    public record Parameter(int slot, String name, String elementType) { }
    public sealed interface Instruction permits Transfer, Drop, Branch, Finish { }
    public record Transfer(int from, int to, Instruction next) implements Instruction { }
    public record Drop(int slot, Instruction next) implements Instruction { }
    public record Branch(String booleanParameter, Instruction whenTrue,
                         Instruction whenFalse) implements Instruction { }
    public record Finish() implements Instruction { }
    public record Plan(List<Parameter> owners, List<String> booleanParameters,
                       boolean returnsOwner, Instruction entry) {
        public Plan {
            owners = List.copyOf(owners);
            booleanParameters = List.copyOf(booleanParameters);
        }
    }

    private record Slot(int id, String name, Ast.TypeRef type, int scope, boolean owned) {
        Slot moved() { return new Slot(id, name, type, scope, false); }
    }
    private interface Continuation {
        Instruction next(List<Slot> slots, int depth);
    }

    private final Set<String> conditions = new HashSet<>();
    private final Ast.TypeRef returnType;
    private final boolean returnsOwner;
    private int work;
    private int nextSlot = 1;

    private AffineDropPlanV1(Ast.TypeRef returnType) {
        this.returnType = returnType;
        returnsOwner = array(returnType);
        if (!returnsOwner && !simple(returnType, "void")) {
            throw denied("return type must be void or an admitted affine array");
        }
    }

    // Only the compiler facade supplies the checked program. Tests can
    // exercise this package-private elaborator in isolation.
    static Plan lowerChecked(Ast.Program program) {
        if (!program.imports().isEmpty() || program.modules().size() != 1
                || !program.exports().isEmpty()) throw denied("requires a single closed module");
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()
                || module.declarations().size() != 1
                || !(module.declarations().getFirst() instanceof Ast.FunctionDecl fn)) {
            throw denied("requires exactly one ordinary free function");
        }
        if (fn.kind() != Ast.CallableKind.FNC || fn.actorKind() != Ast.ActorKind.NONE
                || fn.async() || fn.suspends() || fn.generator() || fn.structural()
                || fn.nonLexical() || fn.trapped() || !fn.effects().isEmpty()
                || !fn.annotations().isEmpty() || !fn.genericParameters().isEmpty()) {
            throw denied("requires a synchronous effect-free nongeneric function");
        }

        var builder = new AffineDropPlanV1(fn.returnType());
        if (fn.parameters().size() > MAX_SLOTS) throw denied("parameter budget exceeded");
        var names = new HashSet<String>();
        var owners = new ArrayList<Parameter>();
        var booleans = new ArrayList<String>();
        var slots = new ArrayList<Slot>();
        for (Ast.Param param : fn.parameters()) {
            if (!names.add(param.name()) || param.structural() || param.mutable()) {
                throw denied("duplicate, structural or mutable parameter");
            }
            if (simple(param.type(), "bool")) {
                builder.conditions.add(param.name());
                booleans.add(param.name());
            } else if (array(param.type())) {
                int id = builder.allocate();
                owners.add(new Parameter(id, param.name(), param.type().arguments().getFirst().name()));
                slots.add(new Slot(id, param.name(), param.type(), 0, true));
            } else {
                throw denied("only bool and affine Array<int/i32> parameters are supported");
            }
        }
        Instruction entry = builder.sequence(fn.body(), 0, slots, 1, 0, (remaining, depth) -> {
            if (builder.returnsOwner) throw denied("owned return missing on a reachable path");
            return builder.dropAll(remaining, new Finish());
        });
        return new Plan(owners, booleans, builder.returnsOwner, entry);
    }

    private static boolean simple(Ast.TypeRef type, String name) {
        return type != null && !type.isBorrow() && !type.inferArguments()
                && type.name().equals(name) && type.arguments().isEmpty();
    }

    private static boolean array(Ast.TypeRef type) {
        return type != null && !type.isBorrow() && !type.inferArguments()
                && type.name().equals("Array") && type.arguments().size() == 1
                && (simple(type.arguments().getFirst(), "int")
                    || simple(type.arguments().getFirst(), "i32"));
    }

    private int allocate() {
        if (nextSlot > MAX_SLOTS) throw denied("slot budget exceeded");
        return nextSlot++;
    }

    private void charge(int depth) {
        if (++work > MAX_STEPS || depth > MAX_DEPTH) throw denied("control-flow budget exceeded");
    }

    private static Slot requireOwner(List<Slot> slots, String name) {
        for (Slot slot : slots) {
            if (slot.name().equals(name)) {
                if (!slot.owned()) throw denied("use of transferred owner " + name);
                return slot;
            }
        }
        throw denied("unknown affine owner " + name);
    }

    private static List<Slot> transferred(List<Slot> slots, Slot source) {
        var result = new ArrayList<>(slots);
        result.set(result.indexOf(source), source.moved());
        return result;
    }

    private static String transferName(Ast.Expr expr) {
        if (expr instanceof Ast.RuntimeCallExpr call && call.operation().equals("take")
                && call.arguments().size() == 1
                && call.arguments().getFirst() instanceof Ast.NameExpr name) return name.name();
        throw denied("ownership transfer requires explicit rt take of a named owner");
    }

    private Instruction sequence(List<Ast.Stmt> body, int index, List<Slot> slots,
                                 int scope, int depth, Continuation continuation) {
        charge(depth);
        if (index == body.size()) return continuation.next(slots, depth + 1);
        Ast.Stmt stmt = body.get(index);
        if (stmt instanceof Ast.BindingStmt bind) {
            if (bind.kind() != Ast.BindingKind.CONST || bind.mutableReferent()
                    || bind.inferredTypeName() != null || conditions.contains(bind.name())
                    || slots.stream().anyMatch(slot -> slot.name().equals(bind.name()))) {
                throw denied("only fresh, immutable transfer bindings are admitted");
            }
            Slot source = requireOwner(slots, transferName(bind.initializer()));
            if (bind.declaredType() != null && !bind.declaredType().equals(source.type())) {
                throw denied("transfer binding type differs from source");
            }
            int dest = allocate();
            List<Slot> after = transferred(slots, source);
            after.add(new Slot(dest, bind.name(), source.type(), scope, true));
            return new Transfer(source.id(), dest,
                    sequence(body, index + 1, after, scope, depth + 1, continuation));
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (index + 1 != body.size()) throw denied("unreachable statements after return");
            if (ret.value() == null) {
                if (returnsOwner) throw denied("owned return missing");
                return dropAll(slots, new Finish());
            }
            if (!returnsOwner) throw denied("void function cannot transfer a return value");
            Slot source = requireOwner(slots, transferName(ret.value()));
            if (!returnType.equals(source.type())) throw denied("owned return type mismatch");
            return new Transfer(source.id(), RETURN_SLOT,
                    dropAll(transferred(slots, source), new Finish()));
        }

        Continuation afterScope = (inside, nextDepth) -> {
            List<Slot> outer = inside.stream().filter(slot -> slot.scope() <= scope).toList();
            Instruction rest = sequence(body, index + 1, outer, scope, nextDepth, continuation);
            return dropScope(inside, scope + 1, rest);
        };
        if (stmt instanceof Ast.BlockStmt block) {
            return sequence(block.body(), 0, slots, scope + 1, depth + 1, afterScope);
        }
        if (stmt instanceof Ast.IfStmt conditional && conditional.branches().size() == 1) {
            Ast.IfBranch branch = conditional.branches().getFirst();
            if (!(branch.condition() instanceof Ast.NameExpr flag) || !conditions.contains(flag.name())) {
                throw denied("branch condition must be an admitted bool parameter");
            }
            return new Branch(flag.name(),
                    sequence(branch.body(), 0, slots, scope + 1, depth + 1, afterScope),
                    sequence(conditional.elseBody(), 0, slots, scope + 1, depth + 1, afterScope));
        }
        throw denied("unsupported statement " + stmt.getClass().getSimpleName());
    }

    private Instruction dropAll(List<Slot> slots, Instruction next) {
        return dropScope(slots, 0, next);
    }

    private Instruction dropScope(List<Slot> slots, int minimumScope, Instruction next) {
        // Wrap oldest first so execution destroys newest first.
        for (Slot slot : slots) {
            if (slot.owned() && slot.scope() >= minimumScope) {
                charge(0);
                next = new Drop(slot.id(), next);
            }
        }
        return next;
    }

    private static IllegalArgumentException denied(String reason) {
        return new IllegalArgumentException("AFFINE_DROP_PLAN_V1: " + reason);
    }
}
