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
    private final Map<String, Ast.ClassDecl> qualifiedClasses = new HashMap<>();

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
                } else if (decl instanceof Ast.ClassDecl klass) {
                    checker.qualifiedClasses.put(module.name() + "." + klass.name(), klass);
                }
            }
        }
        checker.verifyOverrideContracts(program);
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

    /**
     * Dynamic dispatch must not replace a guaranteed identity-preserving
     * inherited method with an override that only preserves its nominal
     * or receiver-polymorphic type. Examine the complete ancestor chain.
     */
    private void verifyOverrideContracts(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ClassDecl klass)) continue;
                java.util.Set<Ast.ClassDecl> ancestors = java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
                collectAncestors(module.name(), klass, ancestors);
                for (Ast.MethodDecl override : klass.methods()) {
                    if (override.isStatic()) continue;
                    for (Ast.ClassDecl ancestor : ancestors) {
                        for (Ast.MethodDecl inherited : ancestor.methods()) {
                            if (inherited.isStatic()
                                    || !inherited.name().equals(override.name())
                                    || inherited.parameters().size() != override.parameters().size()
                                    || !inherited.returnType().isIdentityReturn()) continue;
                            int required = identitySlot(inherited);
                            if (required != identitySlot(override)) {
                                throw new IllegalArgumentException(
                                        "cannot weaken inherited identity return of "
                                                + ancestor.name() + "." + inherited.name()
                                                + " in override " + klass.name() + "." + override.name());
                            }
                        }
                    }
                }
            }
        }
    }

    private void collectAncestors(String module, Ast.ClassDecl klass, java.util.Set<Ast.ClassDecl> seen) {
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl ancestor = qualifiedClasses.get(parent.name());
            if (ancestor == null) ancestor = qualifiedClasses.get(module + "." + parent.name());
            if (ancestor == null) {
                java.util.List<Ast.ClassDecl> candidates = qualifiedClasses.entrySet().stream()
                        .filter(e -> e.getKey().endsWith("." + parent.name()))
                        .map(Map.Entry::getValue)
                        .distinct()
                        .toList();
                if (candidates.size() == 1) ancestor = candidates.getFirst();
            }
            if (ancestor != null && seen.add(ancestor)) {
                String ownerModule = module;
                for (Map.Entry<String, Ast.ClassDecl> entry : qualifiedClasses.entrySet()) {
                    if (entry.getValue() == ancestor) {
                        ownerModule = entry.getKey().substring(0, entry.getKey().lastIndexOf('.'));
                        break;
                    }
                }
                collectAncestors(ownerModule, ancestor, seen);
            }
        }
    }

    private static int identitySlot(Ast.MethodDecl method) {
        if (!method.returnType().isIdentityReturn()) return Integer.MIN_VALUE;
        String name = method.returnType().identityParameter();
        if (name.equals("this")) return -1;
        for (int i = 0; i < method.parameters().size(); i++) {
            if (method.parameters().get(i).name().equals(name)) return i;
        }
        return Integer.MIN_VALUE;
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
                String typeName = param.type().name();
                if (List.of("int", "float", "decimal", "bool", "boolean", "string", "void", "null").contains(typeName)) {
                    throw new IllegalArgumentException(
                            "identity return ': " + name + "' requires an identity-bearing object, not value type " + typeName);
                }
                // A 'mut' parameter is permitted to mutate the object; it must
                // not rebind the original parameter to a replacement instance.
                // All assignment expressions are scanned separately below.
                return name;
            }
        }
        throw new IllegalArgumentException(
                "identity return ': " + name + "' must name an original parameter in " + label);
    }

    private void verify(String module, Ast.FunctionDecl function) {
        String label = "fnc " + module + "." + function.name();
        if (function.async() || function.generator() || function.trapped() || function.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException("identity returns across async/generator/trap/actor boundaries are not yet supported in " + label);
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
            verifyRebindingEffects(stmt, scope, label);
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
                    Scope branchScope = new Scope(scope);
                    shadowCondition(branch.condition(), branchScope);
                    checkBlock(branch.body(), branchScope, origin, module, label);
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

    /**
     * Mutability of the object and reassignability of the parameter are not
     * the same permission. A field update through the original object is fine;
     * rebinding an origin-bearing name is not, even inside a nested expression.
     * This is deliberately conservative across lambdas and channel callbacks.
     */
    private void verifyRebindingEffects(Ast.Stmt stmt, Scope scope, String label) {
        if (stmt instanceof Ast.ExprStmt expression) {
            verifyExpressionEffects(expression.expression(), scope, label);
        } else if (stmt instanceof Ast.BindingStmt binding) {
            verifyExpressionEffects(binding.initializer(), scope, label);
        } else if (stmt instanceof Ast.DestructureStmt destructured) {
            verifyExpressionEffects(destructured.initializer(), scope, label);
        } else if (stmt instanceof Ast.ReturnStmt returned) {
            verifyExpressionEffects(returned.value(), scope, label);
        } else if (stmt instanceof Ast.YieldStmt yielded) {
            verifyExpressionEffects(yielded.value(), scope, label);
        } else if (stmt instanceof Ast.DeferStmt deferred) {
            verifyExpressionEffects(deferred.expression(), scope, label);
        } else if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                verifyExpressionEffects(branch.condition(), scope, label);
            }
        } else if (stmt instanceof Ast.MatchStmt matched) {
            verifyExpressionEffects(matched.subject(), scope, label);
            for (Ast.MatchArm arm : matched.arms()) verifyExpressionEffects(arm.guard(), scope, label);
        } else if (stmt instanceof Ast.SwitchStmt switched) {
            verifyExpressionEffects(switched.subject(), scope, label);
            for (Ast.SwitchCase arm : switched.cases()) {
                for (Ast.Expr choice : arm.constants()) verifyExpressionEffects(choice, scope, label);
            }
        } else if (stmt instanceof Ast.ForOfStmt loop) {
            verifyExpressionEffects(loop.iterable(), scope, label);
        } else if (stmt instanceof Ast.ForOfDestructureStmt loop) {
            verifyExpressionEffects(loop.iterable(), scope, label);
        } else if (stmt instanceof Ast.ForStmt loop) {
            if (loop.initializer() != null) verifyRebindingEffects(loop.initializer(), scope, label);
            verifyExpressionEffects(loop.condition(), scope, label);
            verifyExpressionEffects(loop.update(), scope, label);
        } else if (stmt instanceof Ast.SelectStmt select) {
            for (Ast.SelectArm arm : select.arms()) {
                verifyExpressionEffects(arm.channel(), scope, label);
                verifyExpressionEffects(arm.value(), scope, label);
            }
        }
    }

    private void verifyExpressionEffects(Ast.Expr expr, Scope scope, String label) {
        if (expr == null) return;
        if (expr instanceof Ast.AssignExpr assign) {
            if (assign.target() instanceof Ast.NameExpr target
                    && Boolean.TRUE.equals(scope.get(target.name()))) {
                throw new IllegalArgumentException(
                        "identity return rebind in " + label
                                + ": cannot replace original identity through '" + target.name() + "'");
            }
            verifyExpressionEffects(assign.target(), scope, label);
            verifyExpressionEffects(assign.value(), scope, label);
        } else if (expr instanceof Ast.BinaryExpr binary) {
            verifyExpressionEffects(binary.left(), scope, label);
            verifyExpressionEffects(binary.right(), scope, label);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            verifyExpressionEffects(unary.operand(), scope, label);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            verifyExpressionEffects(conditional.condition(), scope, label);
            verifyExpressionEffects(conditional.whenTrue(), scope, label);
            verifyExpressionEffects(conditional.whenFalse(), scope, label);
        } else if (expr instanceof Ast.TypeTestExpr tested) {
            verifyExpressionEffects(tested.value(), scope, label);
        } else if (expr instanceof Ast.PatternTestExpr tested) {
            verifyExpressionEffects(tested.value(), scope, label);
        } else if (expr instanceof Ast.CastExpr cast) {
            verifyExpressionEffects(cast.value(), scope, label);
        } else if (expr instanceof Ast.SpreadExpr spread) {
            verifyExpressionEffects(spread.expression(), scope, label);
        } else if (expr instanceof Ast.CallExpr call) {
            verifyExpressionEffects(call.callee(), scope, label);
            for (Ast.Expr arg : call.arguments()) verifyExpressionEffects(arg, scope, label);
        } else if (expr instanceof Ast.RuntimeCallExpr runtime) {
            for (Ast.Expr arg : runtime.arguments()) verifyExpressionEffects(arg, scope, label);
        } else if (expr instanceof Ast.MemberExpr member) {
            verifyExpressionEffects(member.receiver(), scope, label);
        } else if (expr instanceof Ast.IndexExpr indexed) {
            verifyExpressionEffects(indexed.receiver(), scope, label);
            verifyExpressionEffects(indexed.index(), scope, label);
        } else if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr arg : created.arguments()) verifyExpressionEffects(arg, scope, label);
        } else if (expr instanceof Ast.AwaitExpr awaited) {
            verifyExpressionEffects(awaited.expression(), scope, label);
        } else if (expr instanceof Ast.ChannelOpExpr channel) {
            verifyExpressionEffects(channel.channel(), scope, label);
            verifyExpressionEffects(channel.value(), scope, label);
            if (!channel.callbackBody().isEmpty()) {
                throw new IllegalArgumentException(
                        "identity-returning callable cannot contain a channel callback with unverified captured effects in " + label);
            }
        } else if (expr instanceof Ast.DynamicSelectExpr select) {
            verifyExpressionEffects(select.cases(), scope, label);
        } else if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr element : list.elements()) verifyExpressionEffects(element, scope, label);
        } else if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr element : tuple.elements()) verifyExpressionEffects(element, scope, label);
        } else if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) verifyExpressionEffects(field.value(), scope, label);
        } else if (expr instanceof Ast.LambdaExpr) {
            throw new IllegalArgumentException(
                    "identity-returning callable cannot capture unverified lambda effects in " + label);
        }
    }

    /** Type- and pattern-test bindings may shadow the identity parameter in a branch. */
    private static void shadowCondition(Ast.Expr condition, Scope scope) {
        if (condition instanceof Ast.TypeTestExpr tested && tested.binding() != null) {
            scope.put(tested.binding(), false);
        } else if (condition instanceof Ast.PatternTestExpr tested) {
            shadowPattern(tested.pattern(), scope);
        } else if (condition instanceof Ast.BinaryExpr pair) {
            shadowCondition(pair.left(), scope);
            shadowCondition(pair.right(), scope);
        } else if (condition instanceof Ast.UnaryExpr unary) {
            shadowCondition(unary.operand(), scope);
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
            // Calls outside the current module require explicit qualification.
            // Never infer identity from a potentially different imported symbol.
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
