package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves exact return identity, not merely nominal/structural assignability.
 *
 * All recognized proofs originate from a callable's original parameter (or
 * instance receiver). Locally immutable aliases and calls to independently
 * verified identity-returning functions can transport that proof. Unsupported
 * forms fail closed rather than relying on runtime equality or Java identity.
 */
final class IdentityReturnChecker {
    private final Map<String, List<Callable>> functions = new HashMap<>();

    private record Callable(String module, Ast.FunctionDecl function) { }

    private static final class Scope {
        final Scope parent;
        final Map<String, Boolean> aliases = new HashMap<>();
        Scope(Scope parent) { this.parent = parent; }
        void put(String name, boolean aliasesOrigin) { aliases.put(name, aliasesOrigin); }
        Boolean get(String name) {
            if (aliases.containsKey(name)) return aliases.get(name);
            return parent == null ? null : parent.get(name);
        }
        boolean contains(String name) {
            if (aliases.containsKey(name)) return true;
            return parent != null && parent.contains(name);
        }
    }

    static void check(Ast.Program program) {
        IdentityReturnChecker checker = new IdentityReturnChecker();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl function) {
                    checker.functions.computeIfAbsent(function.name(), unused -> new ArrayList<>())
                            .add(new Callable(module.name(), function));
                }
            }
        }
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl function && function.returnType().isIdentityReturn()) {
                    checker.verify(module.name(), function);
                } else if (decl instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (method.returnType().isIdentityReturn()) checker.verify(klass, method);
                    }
                }
            }
        }
    }

    private static String checkedParameter(Ast.TypeRef returnType, List<Ast.Param> parameters,
                                            boolean receiver, String label) {
        String name = returnType.identityParameter();
        if (name.equals("this")) {
            if (!receiver) throw new IllegalArgumentException(
                    "identity return ': this' requires an instance receiver in " + label);
            return "self";
        }
        for (Ast.Param param : parameters) {
            if (param.name().equals(name)) {
                if (param.mutable()) throw new IllegalArgumentException(
                        "identity return ': " + name + "' cannot bind a reassignable parameter in " + label);
                return name;
            }
        }
        throw new IllegalArgumentException(
                "identity return ': " + name + "' must name an original parameter in " + label);
    }

    private void verify(String module, Ast.FunctionDecl function) {
        String label = "fnc " + module + "." + function.name();
        if (function.async() || function.generator() || function.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException("identity returns across async/generator/actor boundaries are not yet supported in " + label);
        }
        String origin = checkedParameter(function.returnType(), function.parameters(), false, label);
        Scope scope = new Scope(null);
        for (Ast.Param param : function.parameters()) scope.put(param.name(), param.name().equals(origin));
        checkBlock(function.body(), scope, origin, module, label);
    }

    private void verify(Ast.ClassDecl owner, Ast.MethodDecl method) {
        String label = "method " + owner.name() + "." + method.name();
        if (method.async() || method.isAbstract() || owner.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException("identity returns across async/abstract/actor boundaries are not yet supported in " + label);
        }
        String origin = checkedParameter(method.returnType(), method.parameters(), !method.isStatic(), label);
        Scope scope = new Scope(null);
        if (!method.isStatic()) scope.put("self", origin.equals("self"));
        for (Ast.Param param : method.parameters()) scope.put(param.name(), param.name().equals(origin));
        // A method may prove its original receiver or parameter by returning it;
        // dynamic dispatch identity forwarding is intentionally not assumed.
        checkBlock(method.body(), scope, origin, null, label);
    }

    private void checkBlock(List<Ast.Stmt> body, Scope parent, String origin, String module, String label) {
        Scope scope = new Scope(parent);
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt returned) {
                if (!proves(returned.value(), scope, module)) {
                    throw new IllegalArgumentException(
                            "identity return mismatch in " + label + ": ': "
                                    + (origin.equals("self") ? "this" : origin)
                                    + "' requires the original instance, not a replacement or unproven alias");
                }
            } else if (stmt instanceof Ast.BindingStmt binding) {
                boolean alias = binding.kind() != Ast.BindingKind.LET
                        && proves(binding.initializer(), scope, module);
                scope.put(binding.name(), alias);
            } else if (stmt instanceof Ast.DestructureStmt destructured) {
                // Destructuring creates new identities/views, never a proof of
                // the identity of the entire incoming parameter.
                for (Ast.DestructureBinding binding : destructured.bindings()) {
                    if (!binding.isDiscard()) scope.put(binding.name(), false);
                }
            } else if (stmt instanceof Ast.BlockStmt block) {
                checkBlock(block.body(), scope, origin, module, label);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    checkBlock(branch.body(), scope, origin, module, label);
                }
                checkBlock(conditional.elseBody(), scope, origin, module, label);
            } else if (stmt instanceof Ast.MatchStmt matched) {
                for (Ast.MatchArm arm : matched.arms()) {
                    Scope armScope = new Scope(scope);
                    shadowPattern(arm.pattern(), armScope);
                    checkBlock(arm.body(), armScope, origin, module, label);
                }
            } else if (stmt instanceof Ast.SwitchStmt switched) {
                for (Ast.SwitchCase arm : switched.cases()) {
                    checkBlock(arm.body(), scope, origin, module, label);
                }
                checkBlock(switched.defaultBody(), scope, origin, module, label);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                checkBlock(attempted.body(), scope, origin, module, label);
                Scope caught = new Scope(scope);
                if (attempted.errorName() != null) caught.put(attempted.errorName(), false);
                checkBlock(attempted.catchBody(), caught, origin, module, label);
                checkBlock(attempted.finallyBody(), scope, origin, module, label);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                Scope inner = new Scope(scope);
                inner.put(loop.bindingName(), false);
                checkBlock(loop.body(), inner, origin, module, label);
            } else if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                Scope inner = new Scope(scope);
                for (Ast.DestructureBinding binding : loop.bindings()) {
                    if (!binding.isDiscard()) inner.put(binding.name(), false);
                }
                checkBlock(loop.body(), inner, origin, module, label);
            } else if (stmt instanceof Ast.ForStmt loop) {
                Scope inner = new Scope(scope);
                if (loop.initializer() instanceof Ast.BindingStmt binding) inner.put(binding.name(), false);
                checkBlock(loop.body(), inner, origin, module, label);
            } else if (stmt instanceof Ast.LoopStmt loop) {
                checkBlock(loop.body(), scope, origin, module, label);
            } else if (stmt instanceof Ast.SelectStmt selected) {
                // 'do select' intentionally discards arm-local returns.
                if (selected.explicitDo()) continue;
                for (Ast.SelectArm arm : selected.arms()) {
                    Scope inner = new Scope(scope);
                    if (arm.bindingName() != null) inner.put(arm.bindingName(), false);
                    checkBlock(arm.body(), inner, origin, module, label);
                }
            }
        }
    }

    private static void shadowPattern(Ast.Pattern pattern, Scope scope) {
        if (pattern instanceof Ast.BindingPattern binding) scope.put(binding.name(), false);
        else if (pattern instanceof Ast.TypePattern typed && typed.binding() != null) scope.put(typed.binding(), false);
        else if (pattern instanceof Ast.StructuralPattern typed && typed.binding() != null) scope.put(typed.binding(), false);
        else if (pattern instanceof Ast.TuplePattern tuple) {
            for (Ast.Pattern element : tuple.elements()) shadowPattern(element, scope);
        } else if (pattern instanceof Ast.ConstructorPattern constructor) {
            for (Ast.Pattern element : constructor.arguments()) shadowPattern(element, scope);
        }
    }

    private boolean proves(Ast.Expr expression, Scope scope, String module) {
        if (expression instanceof Ast.NameExpr name) {
            return Boolean.TRUE.equals(scope.get(name.name()));
        }
        if (expression instanceof Ast.RuntimeCallExpr runtime) {
            return (runtime.operation().equals("take") || runtime.operation().equals("borrow"))
                    && runtime.arguments().size() == 1
                    && proves(runtime.arguments().getFirst(), scope, module);
        }
        if (!(expression instanceof Ast.CallExpr call) || module == null) return false;
        Ast.FunctionDecl callee = null;
        if (call.callee() instanceof Ast.NameExpr name && !scope.contains(name.name())) {
            List<Callable> matches = functions.getOrDefault(name.name(), List.of());
            List<Callable> sameModule = matches.stream().filter(
                    c -> c.module().equals(module) && c.function().parameters().size() == call.arguments().size()).toList();
            if (sameModule.size() == 1) callee = sameModule.getFirst().function();
            else if (sameModule.isEmpty()) {
                List<Callable> global = matches.stream().filter(
                        c -> c.function().parameters().size() == call.arguments().size()).toList();
                if (global.size() == 1) callee = global.getFirst().function();
            }
        } else if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace
                && !scope.contains(namespace.name())) {
            List<Callable> matches = functions.getOrDefault(member.member(), List.of());
            List<Callable> exact = matches.stream().filter(
                    c -> c.module().equals(namespace.name())
                            && c.function().parameters().size() == call.arguments().size()).toList();
            if (exact.size() == 1) callee = exact.getFirst().function();
        }
        if (callee == null || !callee.returnType().isIdentityReturn()) return false;
        if (callee.async() || callee.generator() || callee.actorKind() != Ast.ActorKind.NONE) return false;
        String source = callee.returnType().identityParameter();
        for (int i = 0; i < callee.parameters().size(); i++) {
            if (callee.parameters().get(i).name().equals(source)) {
                return i < call.arguments().size() && proves(call.arguments().get(i), scope, module);
            }
        }
        return false;
    }
}
