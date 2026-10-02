package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time allocation/copy effect analysis.
 *
 * <p>{@code @NoAlloc} and {@code @NoCopy} are hard semantic contracts, not
 * optimizer hints. The checker is deliberately conservative: when a constrained
 * callable reaches a call whose implementation/effects cannot be proven, that
 * call is treated as potentially allocating and copying.</p>
 *
 * <p>The pass runs after type/ownership checking and operates on the composed
 * AST so trait-composed methods are checked in their concrete aggregate.</p>
 */
public final class EffectChecker {
    private enum Effect { ALLOC, COPY }

    private static final String NO_ALLOC = "NoAlloc";
    private static final String NO_COPY = "NoCopy";

    private final Map<String, Callable> functions = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final List<Callable> callables = new ArrayList<>();
    private final IdentityHashMap<Callable, EnumSet<Effect>> directEffects = new IdentityHashMap<>();
    private final IdentityHashMap<Callable, EnumMap<Effect, String>> directReasons = new IdentityHashMap<>();
    private final IdentityHashMap<Callable, Set<Callable>> callees = new IdentityHashMap<>();
    private final IdentityHashMap<Callable, EnumSet<Effect>> effects = new IdentityHashMap<>();

    private EffectChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        EffectChecker checker = new EffectChecker(program);
        checker.analyze();
        checker.validateContracts();
        return program;
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    Callable callable = Callable.function(module.name(), fn);
                    callables.add(callable);
                    index(functions, ambiguousFunctions, module.name(), fn.name(), callable);
                } else if (decl instanceof Ast.ClassDecl klass) {
                    index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                    for (Ast.MethodDecl method : klass.methods()) {
                        callables.add(Callable.method(module.name(), klass, method));
                    }
                }
            }
        }
    }

    private static <T> void index(
            Map<String, T> map,
            Set<String> ambiguous,
            String module,
            String name,
            T value) {
        map.put(module + "." + name, value);
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            map.remove(name);
            ambiguous.add(name);
        }
    }

    private void analyze() {
        for (Callable callable : callables) {
            validateContractAnnotations(callable);
            directEffects.put(callable, EnumSet.noneOf(Effect.class));
            directReasons.put(callable, new EnumMap<>(Effect.class));
            callees.put(callable, new LinkedHashSet<>());
            scanBlock(callable.body(), callable);
            effects.put(callable, EnumSet.copyOf(directEffects.get(callable)));
        }

        boolean changed;
        do {
            changed = false;
            for (Callable caller : callables) {
                EnumSet<Effect> accumulated = effects.get(caller);
                int before = accumulated.size();
                for (Callable callee : callees.get(caller)) {
                    accumulated.addAll(effects.get(callee));
                }
                if (accumulated.size() != before) changed = true;
            }
        } while (changed);
    }

    private void validateContracts() {
        for (Callable callable : callables) {
            if (hasAnnotation(callable.annotations(), NO_ALLOC)
                    && effects.get(callable).contains(Effect.ALLOC)) {
                throw violation(callable, Effect.ALLOC, NO_ALLOC);
            }
            if (hasAnnotation(callable.annotations(), NO_COPY)
                    && effects.get(callable).contains(Effect.COPY)) {
                throw violation(callable, Effect.COPY, NO_COPY);
            }
        }
    }

    private IllegalArgumentException violation(Callable callable, Effect effect, String annotation) {
        String path = violationPath(callable, effect, new LinkedHashSet<>());
        String noun = effect == Effect.ALLOC ? "allocation" : "copy";
        return new IllegalArgumentException("@" + annotation + " contract violated by "
                + callable.displayName() + ": " + noun + " effect"
                + (path == null ? "" : " via " + path));
    }

    private String violationPath(Callable callable, Effect effect, Set<Callable> seen) {
        if (!seen.add(callable)) return callable.displayName() + " (recursive cycle)";
        String direct = directReasons.get(callable).get(effect);
        if (direct != null) return direct;
        for (Callable callee : callees.get(callable)) {
            if (!effects.get(callee).contains(effect)) continue;
            String nested = violationPath(callee, effect, seen);
            return "call to " + callee.displayName() + (nested == null ? "" : " -> " + nested);
        }
        return null;
    }

    private void validateContractAnnotations(Callable callable) {
        Set<String> seen = new HashSet<>();
        for (Ast.Annotation annotation : callable.annotations()) {
            if (!annotation.name().equals(NO_ALLOC) && !annotation.name().equals(NO_COPY)) continue;
            if (!annotation.arguments().isEmpty()) {
                throw new IllegalArgumentException("@" + annotation.name()
                        + " does not accept arguments on " + callable.displayName());
            }
            if (!seen.add(annotation.name())) {
                throw new IllegalArgumentException("duplicate @" + annotation.name()
                        + " on " + callable.displayName());
            }
        }
    }

    private static boolean hasAnnotation(List<Ast.Annotation> annotations, String name) {
        return annotations.stream().anyMatch(annotation -> annotation.name().equals(name));
    }

    private void addEffect(Callable callable, Effect effect, String reason) {
        directEffects.get(callable).add(effect);
        directReasons.get(callable).putIfAbsent(effect, reason);
    }

    private void addPotentiallyOpaqueEffects(Callable callable, String description) {
        addEffect(callable, Effect.ALLOC, "unverified call " + description);
        addEffect(callable, Effect.COPY, "unverified call " + description);
    }

    private void scanBlock(List<Ast.Stmt> body, Callable current) {
        for (Ast.Stmt stmt : body) scanStmt(stmt, current);
    }

    private void scanStmt(Ast.Stmt stmt, Callable current) {
        if (stmt instanceof Ast.TypeDeclStmt) {
            return;
        }
        if (stmt instanceof Ast.BindingStmt binding) {
            scanExpr(binding.initializer(), current);
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            scanExpr(destructure.initializer(), current);
            return;
        }
        if (stmt instanceof Ast.ReturnStmt returned) {
            if (returned.value() != null) scanExpr(returned.value(), current);
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            scanExpr(expression.expression(), current);
            return;
        }
        if (stmt instanceof Ast.DeferStmt deferred) {
            scanExpr(deferred.expression(), current);
            return;
        }
        if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                scanExpr(branch.condition(), current);
                scanBlock(branch.body(), current);
            }
            scanBlock(conditional.elseBody(), current);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            scanBlock(attempted.body(), current);
            scanBlock(attempted.catchBody(), current);
            scanBlock(attempted.finallyBody(), current);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            scanExpr(loop.iterable(), current);
            scanBlock(loop.body(), current);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            if (loop.initializer() != null) scanStmt(loop.initializer(), current);
            if (loop.condition() != null) scanExpr(loop.condition(), current);
            if (loop.update() != null) scanExpr(loop.update(), current);
            scanBlock(loop.body(), current);
        }
    }

    private void scanExpr(Ast.Expr expr, Callable current) {
        if (expr == null) return;

        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return;

        if (expr instanceof Ast.BinaryExpr binary) {
            scanExpr(binary.left(), current);
            scanExpr(binary.right(), current);
            if (binary.operator().equals("+")
                    && !(isDefinitelyNumeric(binary.left(), current)
                    && isDefinitelyNumeric(binary.right(), current))) {
                addEffect(current, Effect.ALLOC,
                        "potential string concatenation/materialization with '+'");
            }
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            scanExpr(unary.operand(), current);
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            scanExpr(assignment.target(), current);
            scanExpr(assignment.value(), current);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            scanExpr(conditional.condition(), current);
            scanExpr(conditional.whenTrue(), current);
            scanExpr(conditional.whenFalse(), current);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            scanCall(call, current);
            return;
        }
        if (expr instanceof Ast.MemberExpr member) {
            scanExpr(member.receiver(), current);
            return;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            scanExpr(indexed.receiver(), current);
            scanExpr(indexed.index(), current);
            return;
        }
        if (expr instanceof Ast.NewExpr created) {
            addEffect(current, Effect.ALLOC, "new " + created.type().name() + "(...)");
            for (Ast.Expr argument : created.arguments()) scanExpr(argument, current);
            return;
        }
        if (expr instanceof Ast.StructInitExpr struct) {
            for (Ast.ObjectField field : struct.fields()) scanExpr(field.value(), current);
            return;
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            scanExpr(awaited.expression(), current);
            return;
        }
        if (expr instanceof Ast.ListExpr list) {
            addEffect(current, Effect.ALLOC, "array/list literal");
            for (Ast.Expr item : list.elements()) scanExpr(item, current);
            return;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            addEffect(current, Effect.ALLOC, "tuple materialization");
            for (Ast.Expr item : tuple.elements()) scanExpr(item, current);
            return;
        }
        if (expr instanceof Ast.ObjectExpr object) {
            addEffect(current, Effect.ALLOC, "object/map literal");
            for (Ast.ObjectField field : object.fields()) scanExpr(field.value(), current);
            return;
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            addEffect(current, Effect.ALLOC, "closure/lambda creation");
            if (lambda.expressionBody() != null) scanExpr(lambda.expressionBody(), current);
            if (lambda.blockBody() != null) scanBlock(lambda.blockBody(), current);
        }
    }

    private void scanCall(Ast.CallExpr call, Callable current) {
        for (Ast.Expr argument : call.arguments()) scanExpr(argument, current);

        if (call.callee() instanceof Ast.NameExpr name) {
            switch (name.name()) {
                case "borrow", "take" -> {
                    requireArity(call, name.name(), 1);
                    return;
                }
                case "copy" -> {
                    requireArity(call, "copy", 1);
                    addEffect(current, Effect.COPY, "copy(...) ownership intrinsic");
                    addEffect(current, Effect.ALLOC, "copy(...) may materialize owned storage");
                    return;
                }
                case "share" -> {
                    requireArity(call, "share", 1);
                    addEffect(current, Effect.COPY, "share(...) creates a snapshot copy");
                    addEffect(current, Effect.ALLOC, "share(...) materializes frozen storage");
                    return;
                }
                case "Some" -> {
                    requireArity(call, "Some", 1);
                    addEffect(current, Effect.ALLOC, "Some(...) value construction");
                    return;
                }
                default -> {
                    Callable target = resolveFunction(name.name(), current.module());
                    if (target != null) {
                        callees.get(current).add(target);
                        return;
                    }
                    addPotentiallyOpaqueEffects(current, "'" + name.name() + "(...)'");
                    return;
                }
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            scanExpr(member.receiver(), current);

            if (member.receiver() instanceof Ast.NameExpr receiver) {
                if ((receiver.name().equals("Mutex") || receiver.name().equals("SharedMutex"))
                        && member.member().equals("new")) {
                    addEffect(current, Effect.ALLOC,
                            receiver.name() + ".new(...) runtime state construction");
                    return;
                }

                if (receiver.name().equals("self") && current.ownerClass() != null) {
                    Callable target = resolveMethod(
                            current.module(), current.ownerClass(), member.member(), call.arguments().size());
                    if (target != null) {
                        callees.get(current).add(target);
                        return;
                    }
                }

                Callable moduleTarget = functions.get(receiver.name() + "." + member.member());
                if (moduleTarget != null) {
                    callees.get(current).add(moduleTarget);
                    return;
                }

                Ast.ClassDecl klass = resolveClass(receiver.name(), current.module());
                if (klass != null) {
                    Callable target = resolveMethod(
                            current.module(), klass, member.member(), call.arguments().size());
                    if (target != null) {
                        callees.get(current).add(target);
                        return;
                    }
                }
            }

            addPotentiallyOpaqueEffects(current, "'" + renderCallee(call.callee()) + "(...)'");
            return;
        }

        scanExpr(call.callee(), current);
        addPotentiallyOpaqueEffects(current, "dynamic callable");
    }

    private static void requireArity(Ast.CallExpr call, String name, int expected) {
        if (call.arguments().size() != expected) {
            throw new IllegalArgumentException(name + " expects exactly " + expected + " value"
                    + (expected == 1 ? "" : "s"));
        }
    }

    private Callable resolveFunction(String name, String module) {
        Callable local = functions.get(module + "." + name);
        if (local != null) return local;
        if (ambiguousFunctions.contains(name)) return null;
        return functions.get(name);
    }

    private Ast.ClassDecl resolveClass(String name, String module) {
        Ast.ClassDecl local = classes.get(module + "." + name);
        if (local != null) return local;
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private Callable resolveMethod(String module, Ast.ClassDecl klass, String name, int arity) {
        Callable found = null;
        for (Callable callable : callables) {
            if (callable.ownerClass() != klass) continue;
            if (!callable.name().equals(name) || callable.arity() != arity) continue;
            if (found != null) return null;
            found = callable;
        }
        return found;
    }

    private boolean isDefinitelyNumeric(Ast.Expr expr, Callable current) {
        if (expr instanceof Ast.LiteralExpr literal) {
            return literal.value() instanceof Number || literal.value() instanceof Ast.Imaginary;
        }
        if (expr instanceof Ast.NameExpr name) {
            for (Ast.Param parameter : current.parameters()) {
                if (parameter.name().equals(name.name())) return isNumericType(parameter.type());
            }
            return false;
        }
        if (expr instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("+") || unary.operator().equals("-"))) {
            return isDefinitelyNumeric(unary.operand(), current);
        }
        if (expr instanceof Ast.BinaryExpr binary
                && (binary.operator().equals("+")
                || binary.operator().equals("-")
                || binary.operator().equals("*")
                || binary.operator().equals("/")
                || binary.operator().equals("%"))) {
            return isDefinitelyNumeric(binary.left(), current)
                    && isDefinitelyNumeric(binary.right(), current);
        }
        if (expr instanceof Ast.CallExpr call && call.callee() instanceof Ast.NameExpr name) {
            Callable target = resolveFunction(name.name(), current.module());
            return target != null && isNumericType(target.returnType());
        }
        return false;
    }

    private static boolean isNumericType(Ast.TypeRef type) {
        if (type == null) return false;
        Ast.TypeRef target = type.isBorrow() ? type.borrowedTarget() : type;
        return switch (target.name()) {
            case "int", "float", "decimal", "complex" -> true;
            default -> false;
        };
    }

    private static String renderCallee(Ast.Expr callee) {
        if (callee instanceof Ast.NameExpr name) return name.name();
        if (callee instanceof Ast.MemberExpr member) {
            return renderCallee(member.receiver()) + "." + member.member();
        }
        return "<dynamic>";
    }

    private record Callable(
            String module,
            Ast.ClassDecl ownerClass,
            String name,
            int arity,
            List<Ast.Param> parameters,
            Ast.TypeRef returnType,
            List<Ast.Annotation> annotations,
            List<Ast.Stmt> body,
            String displayName) {

        private static Callable function(String module, Ast.FunctionDecl fn) {
            return new Callable(
                    module,
                    null,
                    fn.name(),
                    fn.parameters().size(),
                    fn.parameters(),
                    fn.returnType(),
                    fn.annotations(),
                    fn.body(),
                    (fn.kind() == Ast.CallableKind.ROUTINE ? "routine '" : "function '")
                            + module + "." + fn.name() + "'");
        }

        private static Callable method(String module, Ast.ClassDecl owner, Ast.MethodDecl method) {
            return new Callable(
                    module,
                    owner,
                    method.name(),
                    method.parameters().size(),
                    method.parameters(),
                    method.returnType(),
                    method.annotations(),
                    method.body(),
                    "method '" + module + "." + owner.name() + "." + method.name()
                            + "/" + method.parameters().size() + "'");
        }
    }
}
