package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First executable, full-AOT macro evaluator slice.
 *
 * <p>This intentionally supports a small, hygienic Syntax&lt;Expr&gt; evaluator:
 * caller-supplied syntax passthrough and primitive Const&lt;T&gt; literal quotation.
 *
 * <pre>
 * define macro identity(Syntax&lt;Expr&gt; value) -> Syntax&lt;Expr&gt; {
 *     return value;
 * }
 * </pre>
 *
 * Returned syntax is caller-owned AST or a compiler-proven primitive literal;
 * neither form introduces an identifier or permits textual capture. All other macro
 * bodies/result positions fail closed. Expansion happens before ordinary
 * type/effect/ownership checks and leaves no macro declarations/invocations in
 * the returned Program.</p>
 */
public final class MacroExpander {
    private static final int MAX_EXPANSIONS = 1_024;
    private static final int MAX_EXPANSION_DEPTH = 64;

    private final Map<String, Ast.MacroDecl> macros = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private int expansions;

    private MacroExpander() { }

    public static Ast.Program expand(Ast.Program program) {
        MacroExpansionPlanner.Plan plan = MacroExpansionPlanner.plan(program);
        boolean hasMacroDeclarations = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .anyMatch(Ast.MacroDecl.class::isInstance);
        if (!plan.hasInvocations() && !hasMacroDeclarations) return program;

        MacroExpander expander = new MacroExpander();
        expander.index(program);
        // A compile-time declaration must be validated even when unused.
        // Supported macros are then erased from executable AST; unsupported
        // bodies fail closed instead of becoming inert runtime metadata.
        expander.requireSupportedDeclarations(program);

        List<Ast.ModuleDecl> modules = new ArrayList<>(program.modules().size());
        for (Ast.ModuleDecl module : program.modules()) {
            List<Ast.Decl> declarations = new ArrayList<>();
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.MacroDecl) continue;
                if (declaration instanceof Ast.MacroInvocationDecl invocation) {
                    throw expander.unsupported(
                            invocation.name(),
                            "declaration-producing macro expansion is not enabled yet");
                }
                declarations.add(expander.rewriteDeclaration(
                        module.name(), declaration, 0));
            }
            modules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), declarations));
        }
        return new Ast.Program(program.namespace(), program.imports(), modules);
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.MacroDecl macro)) continue;
                macros.put(module.name() + "." + macro.name(), macro);
            }
        }
    }

    private void requireSupportedDeclarations(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.MacroDecl macro)) continue;
                if (!isSyntaxExprPassthrough(macro) && !isConstLiteralQuote(macro)) {
                    throw unsupported(
                            macro.name(),
                            "the first evaluator slice only admits Syntax<Expr> identity "
                                    + "or Const<T> literal quotation ('return quote(value);')");
                }
            }
        }
    }

    private boolean isSyntaxExprPassthrough(Ast.MacroDecl macro) {
        if (macro.resultKind().list()
                || macro.resultKind().syntaxKind() != Ast.MacroSyntaxKind.EXPR
                || macro.parameters().size() != 1
                || macro.body().size() != 1) {
            return false;
        }
        Ast.MacroParam parameter = macro.parameters().getFirst();
        if (parameter.type().kind() != Ast.MacroParamKind.SYNTAX
                || parameter.type().syntaxKind() != Ast.MacroSyntaxKind.EXPR) {
            return false;
        }
        return macro.body().getFirst() instanceof Ast.ReturnStmt returned
                && returned.value() instanceof Ast.NameExpr name
                && name.name().equals(parameter.name());
    }

    /**
     * Compiler-only literal quotation: a macro accepting Const<T> can emit a
     * *proven literal* as executable syntax without a runtime macro engine.
     *
     * Other closed expressions intentionally remain unavailable until a
     * bounded, value-preserving compile-time evaluator is implemented.
     */
    private boolean isConstLiteralQuote(Ast.MacroDecl macro) {
        if (macro.resultKind().list()
                || macro.resultKind().syntaxKind() != Ast.MacroSyntaxKind.EXPR
                || macro.parameters().size() != 1
                || macro.body().size() != 1) {
            return false;
        }
        Ast.MacroParam parameter = macro.parameters().getFirst();
        if (parameter.type().kind() != Ast.MacroParamKind.CONST) return false;
        if (!(macro.body().getFirst() instanceof Ast.ReturnStmt returned)
                || !(returned.value() instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.NameExpr callee)
                || !callee.name().equals("quote")
                || call.typeArgumentsPresent()
                || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.NameExpr input)) {
            return false;
        }
        return input.name().equals(parameter.name());
    }

    private IllegalArgumentException unsupported(String name, String detail) {
        return new IllegalArgumentException(
                "define macro '" + name + "' is compile-time-only, but " + detail
                        + "; runtime fallback is forbidden; JIT/source-string fallback is forbidden");
    }

    private Ast.MacroDecl resolve(String module, String name) {
        String owner = module;
        Ast.MacroDecl macro;
        if (name.indexOf('.') >= 0) {
            int separator = name.lastIndexOf('.');
            owner = name.substring(0, separator);
            macro = macros.get(name);
        } else {
            macro = macros.get(module + "." + name);
        }
        if (macro == null) {
            throw new IllegalArgumentException(
                    "unresolved compile-time macro '" + name
                            + "'; runtime call fallback is forbidden");
        }
        if (!owner.equals(module) && macro.visibility() != Ast.Visibility.PUBLIC) {
            throw new IllegalArgumentException(
                    "private compile-time macro '" + name
                            + "' is not visible from module '" + module
                            + "'; expansion cannot bypass compile-time visibility");
        }
        return macro;
    }

    private Ast.Expr expandExpressionInvocation(
            String module,
            Ast.MacroInvocationExpr invocation,
            int depth) {
        if (depth >= MAX_EXPANSION_DEPTH) {
            throw new IllegalArgumentException(
                    "macro expansion depth exceeds " + MAX_EXPANSION_DEPTH
                            + "; runtime fallback is forbidden");
        }
        if (++expansions > MAX_EXPANSIONS) {
            throw new IllegalArgumentException(
                    "macro expansion count exceeds " + MAX_EXPANSIONS
                            + "; runtime fallback is forbidden");
        }

        Ast.MacroDecl macro = resolve(module, invocation.name());
        boolean syntaxIdentity = isSyntaxExprPassthrough(macro);
        boolean constQuote = isConstLiteralQuote(macro);
        if (!syntaxIdentity && !constQuote) {
            throw unsupported(
                    invocation.name(),
                    "this invocation requires an evaluator feature not enabled "
                            + "by the first Syntax<Expr> evaluator slice");
        }
        if (invocation.arguments().size() != 1) {
            throw new IllegalArgumentException(
                    "macro '" + invocation.name() + "' requires exactly one argument");
        }
        Ast.Expr emitted;
        if (syntaxIdentity) {
            if (!(invocation.arguments().getFirst()
                    instanceof Ast.MacroSyntaxExprArgument quoted)) {
                throw new IllegalArgumentException(
                        "macro '" + invocation.name() + "' requires Syntax<Expr>");
            }
            emitted = quoted.expression();
        } else {
            if (!(invocation.arguments().getFirst()
                    instanceof Ast.MacroConstArgument constant)
                    || !(constant.expression() instanceof Ast.LiteralExpr literal)) {
                throw unsupported(
                        invocation.name(),
                        "Const<T> quote currently requires a proven primitive literal; "
                                + "closed expressions need a bounded comptime evaluator");
            }
            emitted = literal;
        }

        String activeKey = module + "\0" + invocation.name()
                + "\0" + invocation.site().line()
                + ":" + invocation.site().column();
        if (!active.add(activeKey)) {
            throw new IllegalArgumentException(
                    "macro expansion cycle at '" + invocation.name()
                            + "'; runtime fallback is forbidden");
        }
        try {
            // Emits verified literal or caller-owned syntax: no generated source
            // strings, newly introduced identifiers, or runtime evaluation.
            return rewriteExpression(module, emitted, depth + 1);
        } finally {
            active.remove(activeKey);
        }
    }

    private Ast.Decl rewriteDeclaration(
            String module, Ast.Decl declaration, int depth) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            return new Ast.FunctionDecl(
                    fn.name(), fn.kind(), fn.visibility(), fn.async(), fn.suspends(),
                    fn.generator(), fn.structural(), fn.nonLexical(), fn.trapped(),
                    fn.actorKind(), fn.genericParameters(), fn.parameters(),
                    fn.returnType(), fn.annotations(),
                    rewriteStatements(module, fn.body(), depth),
                    fn.genericSpecs(), fn.effects());
        }
        if (declaration instanceof Ast.FieldDecl field) {
            return new Ast.FieldDecl(
                    field.name(), field.visibility(), field.bindingKind(), field.type(),
                    field.annotations(),
                    rewriteExpression(module, field.initializer(), depth),
                    field.mutableReferent());
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            List<Ast.FieldDecl> fields = new ArrayList<>();
            for (Ast.FieldDecl field : klass.fields()) {
                fields.add((Ast.FieldDecl) rewriteDeclaration(module, field, depth));
            }

            Ast.ConstructorDecl constructor = klass.constructor();
            if (constructor != null) {
                constructor = new Ast.ConstructorDecl(
                        constructor.visibility(), constructor.parameters(),
                        constructor.annotations(),
                        rewriteStatements(module, constructor.body(), depth));
            }

            List<Ast.MethodDecl> methods = new ArrayList<>();
            for (Ast.MethodDecl method : klass.methods()) {
                methods.add(new Ast.MethodDecl(
                        method.name(), method.visibility(), method.isStatic(),
                        method.isAbstract(), method.async(), method.suspends(),
                        method.structural(), method.explicitReceiverType(),
                        method.genericParameters(), method.parameters(),
                        method.returnType(), method.annotations(),
                        rewriteStatements(module, method.body(), depth),
                        method.effects()));
            }

            return new Ast.ClassDecl(
                    klass.name(), klass.visibility(), klass.isAbstract(),
                    klass.actorKind(), klass.genericParameters(), klass.parents(),
                    klass.interfaces(), fields, constructor, methods,
                    klass.actorConcurrency());
        }
        return declaration;
    }

    private List<Ast.Stmt> rewriteStatements(
            String module, List<Ast.Stmt> statements, int depth) {
        List<Ast.Stmt> result = new ArrayList<>(statements.size());
        for (Ast.Stmt statement : statements) {
            result.add(rewriteStatement(module, statement, depth));
        }
        return List.copyOf(result);
    }

    private Ast.Stmt rewriteStatement(
            String module, Ast.Stmt statement, int depth) {
        if (statement instanceof Ast.BindingStmt s) {
            return new Ast.BindingStmt(
                    s.kind(), s.declaredType(), s.name(),
                    rewriteExpression(module, s.initializer(), depth),
                    s.mutableReferent(), s.inferredTypeName());
        }
        if (statement instanceof Ast.DestructureStmt s) {
            return new Ast.DestructureStmt(
                    s.kind(), s.bindings(),
                    rewriteExpression(module, s.initializer(), depth));
        }
        if (statement instanceof Ast.ReturnStmt s) {
            return new Ast.ReturnStmt(
                    rewriteExpression(module, s.value(), depth));
        }
        if (statement instanceof Ast.YieldStmt s) {
            return new Ast.YieldStmt(
                    rewriteExpression(module, s.value(), depth),
                    s.delegated());
        }
        if (statement instanceof Ast.ExprStmt s) {
            return new Ast.ExprStmt(
                    rewriteExpression(module, s.expression(), depth));
        }
        if (statement instanceof Ast.DeferStmt s) {
            return new Ast.DeferStmt(
                    rewriteExpression(module, s.expression(), depth));
        }
        if (statement instanceof Ast.BlockStmt s) {
            return new Ast.BlockStmt(
                    rewriteStatements(module, s.body(), depth));
        }
        if (statement instanceof Ast.TaskScopeStmt s) {
            return new Ast.TaskScopeStmt(
                    rewriteStatements(module, s.body(), depth));
        }
        if (statement instanceof Ast.UsingBorrowStmt s) {
            return new Ast.UsingBorrowStmt(
                    rewriteExpression(module, s.resource(), depth),
                    s.binding(), rewriteStatements(module, s.body(), depth));
        }
        if (statement instanceof Ast.BreakStmt
                || statement instanceof Ast.ContinueStmt) {
            return statement;
        }
        if (statement instanceof Ast.IfStmt s) {
            List<Ast.IfBranch> branches = new ArrayList<>();
            for (Ast.IfBranch branch : s.branches()) {
                branches.add(new Ast.IfBranch(
                        rewriteExpression(module, branch.condition(), depth),
                        rewriteStatements(module, branch.body(), depth)));
            }
            return new Ast.IfStmt(
                    branches, rewriteStatements(module, s.elseBody(), depth));
        }
        if (statement instanceof Ast.MatchStmt s) {
            List<Ast.MatchArm> arms = new ArrayList<>();
            for (Ast.MatchArm arm : s.arms()) {
                arms.add(new Ast.MatchArm(
                        arm.pattern(),
                        rewriteExpression(module, arm.guard(), depth),
                        rewriteStatements(module, arm.body(), depth)));
            }
            return new Ast.MatchStmt(
                    rewriteExpression(module, s.subject(), depth),
                    s.ordered(), arms, s.procedural());
        }
        if (statement instanceof Ast.SwitchStmt s) {
            List<Ast.SwitchCase> cases = new ArrayList<>();
            for (Ast.SwitchCase arm : s.cases()) {
                List<Ast.Expr> constants = new ArrayList<>();
                for (Ast.Expr constant : arm.constants()) {
                    constants.add(rewriteExpression(module, constant, depth));
                }
                cases.add(new Ast.SwitchCase(
                        constants,
                        rewriteStatements(module, arm.body(), depth)));
            }
            return new Ast.SwitchStmt(
                    rewriteExpression(module, s.subject(), depth),
                    cases, rewriteStatements(module, s.defaultBody(), depth));
        }
        if (statement instanceof Ast.TryStmt s) {
            return new Ast.TryStmt(
                    rewriteStatements(module, s.body(), depth),
                    s.errorName(),
                    rewriteStatements(module, s.catchBody(), depth),
                    rewriteStatements(module, s.finallyBody(), depth));
        }
        if (statement instanceof Ast.ForOfStmt s) {
            return new Ast.ForOfStmt(
                    s.bindingKind(), s.bindingName(),
                    rewriteExpression(module, s.iterable(), depth),
                    s.asyncIteration(),
                    rewriteStatements(module, s.body(), depth),
                    s.mutableReferent());
        }
        if (statement instanceof Ast.ForOfDestructureStmt s) {
            return new Ast.ForOfDestructureStmt(
                    s.bindings(),
                    rewriteExpression(module, s.iterable(), depth),
                    s.asyncIteration(),
                    rewriteStatements(module, s.body(), depth));
        }
        if (statement instanceof Ast.ForStmt s) {
            return new Ast.ForStmt(
                    s.initializer() == null
                            ? null
                            : rewriteStatement(module, s.initializer(), depth),
                    rewriteExpression(module, s.condition(), depth),
                    rewriteExpression(module, s.update(), depth),
                    rewriteStatements(module, s.body(), depth));
        }
        if (statement instanceof Ast.DoWhileStmt s) {
            return new Ast.DoWhileStmt(
                    rewriteStatements(module, s.body(), depth),
                    rewriteExpression(module, s.condition(), depth));
        }
        if (statement instanceof Ast.LoopStmt s) {
            return new Ast.LoopStmt(
                    rewriteStatements(module, s.body(), depth));
        }
        if (statement instanceof Ast.SelectStmt s) {
            List<Ast.SelectArm> arms = new ArrayList<>();
            for (Ast.SelectArm arm : s.arms()) {
                arms.add(new Ast.SelectArm(
                        arm.operation(),
                        rewriteExpression(module, arm.channel(), depth),
                        rewriteExpression(module, arm.value(), depth),
                        arm.bindingKind(), arm.bindingName(),
                        rewriteStatements(module, arm.body(), depth),
                        arm.mutableReferent(), arm.bindingType()));
            }
            return new Ast.SelectStmt(
                    s.mode(), s.policy(), arms, s.explicitDo());
        }
        throw new IllegalStateException(
                "unhandled macro-expansion statement "
                        + statement.getClass().getSimpleName());
    }

    private Ast.Expr rewriteExpression(
            String module, Ast.Expr expression, int depth) {
        if (expression == null) return null;

        if (expression instanceof Ast.MacroInvocationExpr invocation) {
            return expandExpressionInvocation(module, invocation, depth);
        }
        if (expression instanceof Ast.LiteralExpr
                || expression instanceof Ast.NameExpr) {
            return expression;
        }
        if (expression instanceof Ast.BinaryExpr e) {
            return new Ast.BinaryExpr(
                    e.operator(),
                    rewriteExpression(module, e.left(), depth),
                    rewriteExpression(module, e.right(), depth));
        }
        if (expression instanceof Ast.UnaryExpr e) {
            return new Ast.UnaryExpr(
                    e.operator(),
                    rewriteExpression(module, e.operand(), depth));
        }
        if (expression instanceof Ast.AssignExpr e) {
            return new Ast.AssignExpr(
                    rewriteExpression(module, e.target(), depth),
                    rewriteExpression(module, e.value(), depth));
        }
        if (expression instanceof Ast.ConditionalExpr e) {
            return new Ast.ConditionalExpr(
                    rewriteExpression(module, e.condition(), depth),
                    rewriteExpression(module, e.whenTrue(), depth),
                    rewriteExpression(module, e.whenFalse(), depth));
        }
        if (expression instanceof Ast.MatchExpr e) {
            List<Ast.MatchValueArm> arms = new ArrayList<>();
            for (Ast.MatchValueArm arm : e.arms()) {
                arms.add(new Ast.MatchValueArm(
                        arm.pattern(),
                        rewriteExpression(module, arm.guard(), depth),
                        rewriteExpression(module, arm.value(), depth)));
            }
            return new Ast.MatchExpr(
                    rewriteExpression(module, e.subject(), depth),
                    arms, e.resultType());
        }
        if (expression instanceof Ast.TypeTestExpr e) {
            return new Ast.TypeTestExpr(
                    rewriteExpression(module, e.value(), depth),
                    e.targetType(), e.binding());
        }
        if (expression instanceof Ast.PatternTestExpr e) {
            return new Ast.PatternTestExpr(
                    rewriteExpression(module, e.value(), depth),
                    e.pattern());
        }
        if (expression instanceof Ast.CastExpr e) {
            return new Ast.CastExpr(
                    rewriteExpression(module, e.value(), depth),
                    e.targetType(), e.mode());
        }
        if (expression instanceof Ast.SpreadExpr e) {
            return new Ast.SpreadExpr(
                    rewriteExpression(module, e.expression(), depth));
        }
        if (expression instanceof Ast.CallExpr e) {
            List<Ast.Expr> arguments = new ArrayList<>();
            for (Ast.Expr argument : e.arguments()) {
                arguments.add(rewriteExpression(module, argument, depth));
            }
            return new Ast.CallExpr(
                    rewriteExpression(module, e.callee(), depth),
                    e.typeArguments(), e.typeArgumentsPresent(),
                    arguments, e.origin());
        }
        if (expression instanceof Ast.RuntimeCallExpr e) {
            List<Ast.Expr> arguments = new ArrayList<>();
            for (Ast.Expr argument : e.arguments()) {
                arguments.add(rewriteExpression(module, argument, depth));
            }
            return new Ast.RuntimeCallExpr(e.operation(), arguments);
        }
        if (expression instanceof Ast.MemberExpr e) {
            return new Ast.MemberExpr(
                    rewriteExpression(module, e.receiver(), depth), e.member());
        }
        if (expression instanceof Ast.IndexExpr e) {
            return new Ast.IndexExpr(
                    rewriteExpression(module, e.receiver(), depth),
                    rewriteExpression(module, e.index(), depth));
        }
        if (expression instanceof Ast.NewExpr e) {
            List<Ast.Expr> arguments = new ArrayList<>();
            for (Ast.Expr argument : e.arguments()) {
                arguments.add(rewriteExpression(module, argument, depth));
            }
            return new Ast.NewExpr(e.type(), arguments);
        }
        if (expression instanceof Ast.AwaitExpr e) {
            return new Ast.AwaitExpr(
                    rewriteExpression(module, e.expression(), depth));
        }
        if (expression instanceof Ast.TaskExpr e) {
            return new Ast.TaskExpr(
                    e.domain(),
                    rewriteStatements(module, e.body(), depth),
                    e.legacyNb());
        }
        if (expression instanceof Ast.TrapExpr e) {
            return new Ast.TrapExpr(
                    rewriteExpression(module, e.expression(), depth));
        }
        if (expression instanceof Ast.ChannelOpExpr e) {
            return new Ast.ChannelOpExpr(
                    e.operation(), e.mode(),
                    rewriteExpression(module, e.channel(), depth),
                    rewriteExpression(module, e.value(), depth),
                    e.callback(),
                    rewriteStatements(module, e.callbackBody(), depth));
        }
        if (expression instanceof Ast.DynamicSelectExpr e) {
            return new Ast.DynamicSelectExpr(
                    e.mode(), e.policy(),
                    rewriteExpression(module, e.cases(), depth));
        }
        if (expression instanceof Ast.ListExpr e) {
            List<Ast.Expr> elements = new ArrayList<>();
            for (Ast.Expr element : e.elements()) {
                elements.add(rewriteExpression(module, element, depth));
            }
            return new Ast.ListExpr(elements);
        }
        if (expression instanceof Ast.TupleExpr e) {
            List<Ast.Expr> elements = new ArrayList<>();
            for (Ast.Expr element : e.elements()) {
                elements.add(rewriteExpression(module, element, depth));
            }
            return new Ast.TupleExpr(elements);
        }
        if (expression instanceof Ast.ObjectExpr e) {
            List<Ast.ObjectField> fields = new ArrayList<>();
            for (Ast.ObjectField field : e.fields()) {
                fields.add(Ast.ObjectField.named(
                        field.name(),
                        rewriteExpression(module, field.value(), depth)));
            }
            return new Ast.ObjectExpr(fields, e.kind(), e.declaredFields());
        }
        if (expression instanceof Ast.LambdaExpr e) {
            return new Ast.LambdaExpr(
                    e.parameters(),
                    rewriteExpression(module, e.expressionBody(), depth),
                    e.blockBody() == null
                            ? null
                            : rewriteStatements(module, e.blockBody(), depth),
                    e.nonLexical());
        }

        throw new IllegalStateException(
                "unhandled macro-expansion expression "
                        + expression.getClass().getSimpleName());
    }
}
