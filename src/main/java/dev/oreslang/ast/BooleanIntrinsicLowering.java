package dev.oreslang.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lowers type-checked scalar Boolean intrinsics into ordinary logical AST.
 *
 * <p>The type checker supplies the exact CallExpr identities that resolved to
 * builtins. This pass therefore never repeats name-resolution or shadowing
 * decisions: user-defined And/Or/Xor functions and real BooleanOps modules are
 * left untouched.</p>
 *
 * <p>Aggregate one-argument overloads are intentionally retained as calls until
 * packaged core-library source can own their reducer loops on every backend.</p>
 */
public final class BooleanIntrinsicLowering {
    private BooleanIntrinsicLowering() { }

    public static Ast.Program lower(
            Ast.Program program,
            Map<Ast.CallExpr, String> resolvedBuiltins) {
        if (resolvedBuiltins == null || resolvedBuiltins.isEmpty()) return program;
        return new Rewriter(resolvedBuiltins).program(program);
    }

    private static final class Rewriter {
        private final Map<Ast.CallExpr, String> resolvedBuiltins;

        private Rewriter(Map<Ast.CallExpr, String> resolvedBuiltins) {
            this.resolvedBuiltins = resolvedBuiltins;
        }

        private Ast.Program program(Ast.Program input) {
            List<Ast.ModuleDecl> modules = new ArrayList<>(input.modules().size());
            for (Ast.ModuleDecl module : input.modules()) {
                List<Ast.Decl> declarations = new ArrayList<>(module.declarations().size());
                for (Ast.Decl declaration : module.declarations()) {
                    declarations.add(declaration(declaration));
                }
                modules.add(new Ast.ModuleDecl(
                        module.name(), module.annotations(), module.contracts(), declarations));
            }
            return new Ast.Program(input.namespace(), input.imports(), modules);
        }

        private Ast.Decl declaration(Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl function) {
                return new Ast.FunctionDecl(
                        function.name(),
                        function.kind(),
                        function.visibility(),
                        function.async(),
                        function.suspends(),
                        function.generator(),
                        function.structural(),
                        function.nonLexical(),
                        function.trapped(),
                        function.actorKind(),
                        function.genericParameters(),
                        function.parameters(),
                        function.returnType(),
                        function.annotations(),
                        statements(function.body()),
                        function.genericSpecs());
            }
            if (declaration instanceof Ast.FieldDecl field) {
                return new Ast.FieldDecl(
                        field.name(),
                        field.visibility(),
                        field.bindingKind(),
                        field.type(),
                        field.annotations(),
                        expression(field.initializer()),
                        field.mutableReferent());
            }
            if (declaration instanceof Ast.ClassDecl klass) {
                List<Ast.FieldDecl> fields = new ArrayList<>(klass.fields().size());
                for (Ast.FieldDecl field : klass.fields()) {
                    fields.add((Ast.FieldDecl) declaration(field));
                }

                Ast.ConstructorDecl constructor = klass.constructor();
                if (constructor != null) {
                    constructor = new Ast.ConstructorDecl(
                            constructor.visibility(),
                            constructor.parameters(),
                            constructor.annotations(),
                            statements(constructor.body()));
                }

                List<Ast.MethodDecl> methods = new ArrayList<>(klass.methods().size());
                for (Ast.MethodDecl method : klass.methods()) {
                    methods.add(new Ast.MethodDecl(
                            method.name(),
                            method.visibility(),
                            method.isStatic(),
                            method.isAbstract(),
                            method.async(),
                            method.suspends(),
                            method.structural(),
                            method.explicitReceiverType(),
                            method.genericParameters(),
                            method.parameters(),
                            method.returnType(),
                            method.annotations(),
                            statements(method.body())));
                }

                return new Ast.ClassDecl(
                        klass.name(),
                        klass.visibility(),
                        klass.isAbstract(),
                        klass.actorKind(),
                        klass.genericParameters(),
                        klass.parents(),
                        klass.interfaces(),
                        fields,
                        constructor,
                        methods);
            }
            // Interface/type declarations contain no executable expressions.
            return declaration;
        }

        private List<Ast.Stmt> statements(List<Ast.Stmt> input) {
            List<Ast.Stmt> output = new ArrayList<>(input.size());
            for (Ast.Stmt statement : input) output.add(statement(statement));
            return List.copyOf(output);
        }

        private Ast.Stmt statement(Ast.Stmt statement) {
            if (statement instanceof Ast.BindingStmt binding) {
                return new Ast.BindingStmt(
                        binding.kind(),
                        binding.declaredType(),
                        binding.name(),
                        expression(binding.initializer()),
                        binding.mutableReferent(),
                        binding.inferredTypeName());
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                return new Ast.DestructureStmt(
                        destructure.kind(),
                        destructure.bindings(),
                        expression(destructure.initializer()));
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                return new Ast.ReturnStmt(expression(returned.value()));
            }
            if (statement instanceof Ast.YieldStmt yielded) {
                return new Ast.YieldStmt(expression(yielded.value()), yielded.delegated());
            }
            if (statement instanceof Ast.ExprStmt expr) {
                return new Ast.ExprStmt(expression(expr.expression()));
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                return new Ast.DeferStmt(expression(deferred.expression()));
            }
            if (statement instanceof Ast.BlockStmt block) {
                return new Ast.BlockStmt(statements(block.body()));
            }
            if (statement instanceof Ast.BreakStmt || statement instanceof Ast.ContinueStmt) {
                return statement;
            }
            if (statement instanceof Ast.IfStmt conditional) {
                List<Ast.IfBranch> branches = new ArrayList<>(conditional.branches().size());
                for (Ast.IfBranch branch : conditional.branches()) {
                    branches.add(new Ast.IfBranch(
                            expression(branch.condition()),
                            statements(branch.body())));
                }
                return new Ast.IfStmt(branches, statements(conditional.elseBody()));
            }
            if (statement instanceof Ast.MatchStmt matched) {
                List<Ast.MatchArm> arms = new ArrayList<>(matched.arms().size());
                for (Ast.MatchArm arm : matched.arms()) {
                    arms.add(new Ast.MatchArm(
                            arm.pattern(),
                            expression(arm.guard()),
                            statements(arm.body())));
                }
                return new Ast.MatchStmt(
                        expression(matched.subject()),
                        matched.ordered(),
                        arms,
                        matched.procedural());
            }
            if (statement instanceof Ast.SwitchStmt switched) {
                List<Ast.SwitchCase> cases = new ArrayList<>(switched.cases().size());
                for (Ast.SwitchCase arm : switched.cases()) {
                    List<Ast.Expr> constants = new ArrayList<>(arm.constants().size());
                    for (Ast.Expr constant : arm.constants()) constants.add(expression(constant));
                    cases.add(new Ast.SwitchCase(constants, statements(arm.body())));
                }
                return new Ast.SwitchStmt(
                        expression(switched.subject()),
                        cases,
                        statements(switched.defaultBody()));
            }
            if (statement instanceof Ast.TryStmt tried) {
                return new Ast.TryStmt(
                        statements(tried.body()),
                        tried.errorName(),
                        statements(tried.catchBody()),
                        statements(tried.finallyBody()));
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                return new Ast.ForOfStmt(
                        loop.bindingKind(),
                        loop.bindingName(),
                        expression(loop.iterable()),
                        loop.asyncIteration(),
                        statements(loop.body()),
                        loop.mutableReferent());
            }
            if (statement instanceof Ast.ForOfDestructureStmt loop) {
                return new Ast.ForOfDestructureStmt(
                        loop.bindings(),
                        expression(loop.iterable()),
                        loop.asyncIteration(),
                        statements(loop.body()));
            }
            if (statement instanceof Ast.ForStmt loop) {
                return new Ast.ForStmt(
                        loop.initializer() == null ? null : statement(loop.initializer()),
                        expression(loop.condition()),
                        expression(loop.update()),
                        statements(loop.body()));
            }
            if (statement instanceof Ast.LoopStmt loop) {
                return new Ast.LoopStmt(statements(loop.body()));
            }
            if (statement instanceof Ast.SelectStmt selected) {
                List<Ast.SelectArm> arms = new ArrayList<>(selected.arms().size());
                for (Ast.SelectArm arm : selected.arms()) {
                    arms.add(new Ast.SelectArm(
                            arm.operation(),
                            expression(arm.channel()),
                            expression(arm.value()),
                            arm.bindingKind(),
                            arm.bindingName(),
                            statements(arm.body()),
                            arm.mutableReferent(),
                            arm.bindingType()));
                }
                return new Ast.SelectStmt(
                        selected.mode(),
                        selected.policy(),
                        arms,
                        selected.explicitDo());
            }
            throw new IllegalStateException(
                    "unhandled statement during Boolean intrinsic lowering: "
                            + statement.getClass().getSimpleName());
        }

        private Ast.Expr expression(Ast.Expr expression) {
            if (expression == null
                    || expression instanceof Ast.LiteralExpr
                    || expression instanceof Ast.NameExpr) {
                return expression;
            }
            if (expression instanceof Ast.MatchExpr matched) {
                List<Ast.MatchValueArm> arms = new ArrayList<>(matched.arms().size());
                for (Ast.MatchValueArm arm : matched.arms()) {
                    arms.add(new Ast.MatchValueArm(
                            arm.pattern(),
                            expression(arm.guard()),
                            expression(arm.value())));
                }
                return new Ast.MatchExpr(
                        expression(matched.subject()),
                        arms,
                        matched.resultType());
            }
            if (expression instanceof Ast.TypeTestExpr test) {
                return new Ast.TypeTestExpr(
                        expression(test.value()),
                        test.targetType(),
                        test.binding());
            }
            if (expression instanceof Ast.PatternTestExpr test) {
                return new Ast.PatternTestExpr(
                        expression(test.value()),
                        test.pattern());
            }
            if (expression instanceof Ast.CastExpr cast) {
                return new Ast.CastExpr(
                        expression(cast.value()),
                        cast.targetType(),
                        cast.mode());
            }
            if (expression instanceof Ast.SpreadExpr spread) {
                return new Ast.SpreadExpr(expression(spread.expression()));
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                return new Ast.BinaryExpr(
                        binary.operator(),
                        expression(binary.left()),
                        expression(binary.right()));
            }
            if (expression instanceof Ast.TrapExpr trapped) {
                return new Ast.TrapExpr(expression(trapped.expression()));
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                return new Ast.UnaryExpr(
                        unary.operator(),
                        expression(unary.operand()));
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                return new Ast.AssignExpr(
                        expression(assignment.target()),
                        expression(assignment.value()));
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                return new Ast.ConditionalExpr(
                        expression(conditional.condition()),
                        expression(conditional.whenTrue()),
                        expression(conditional.whenFalse()));
            }
            if (expression instanceof Ast.RuntimeCallExpr runtime) {
                List<Ast.Expr> arguments = new ArrayList<>(runtime.arguments().size());
                for (Ast.Expr argument : runtime.arguments()) arguments.add(expression(argument));
                return new Ast.RuntimeCallExpr(runtime.operation(), arguments);
            }
            if (expression instanceof Ast.CallExpr call) {
                Ast.Expr callee = expression(call.callee());
                List<Ast.Expr> arguments = new ArrayList<>(call.arguments().size());
                for (Ast.Expr argument : call.arguments()) arguments.add(expression(argument));

                String builtin = resolvedBuiltins.get(call);
                if (builtin != null && arguments.size() >= 2) {
                    return lowerScalar(builtin, arguments);
                }

                return new Ast.CallExpr(
                        callee,
                        call.typeArguments(),
                        call.typeArgumentsPresent(),
                        arguments);
            }
            if (expression instanceof Ast.MemberExpr member) {
                return new Ast.MemberExpr(
                        expression(member.receiver()),
                        member.member());
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                return new Ast.IndexExpr(
                        expression(indexed.receiver()),
                        expression(indexed.index()));
            }
            if (expression instanceof Ast.NewExpr created) {
                List<Ast.Expr> arguments = new ArrayList<>(created.arguments().size());
                for (Ast.Expr argument : created.arguments()) arguments.add(expression(argument));
                return new Ast.NewExpr(created.type(), arguments);
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                return new Ast.AwaitExpr(expression(awaited.expression()));
            }
            if (expression instanceof Ast.ChannelOpExpr operation) {
                return new Ast.ChannelOpExpr(
                        operation.operation(),
                        operation.mode(),
                        expression(operation.channel()),
                        expression(operation.value()),
                        operation.callback(),
                        statements(operation.callbackBody()));
            }
            if (expression instanceof Ast.DynamicSelectExpr selected) {
                return new Ast.DynamicSelectExpr(
                        selected.mode(),
                        selected.policy(),
                        expression(selected.cases()));
            }
            if (expression instanceof Ast.ListExpr list) {
                List<Ast.Expr> elements = new ArrayList<>(list.elements().size());
                for (Ast.Expr element : list.elements()) elements.add(expression(element));
                return new Ast.ListExpr(elements);
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                List<Ast.Expr> elements = new ArrayList<>(tuple.elements().size());
                for (Ast.Expr element : tuple.elements()) elements.add(expression(element));
                return new Ast.TupleExpr(elements);
            }
            if (expression instanceof Ast.ObjectExpr object) {
                List<Ast.ObjectField> fields = new ArrayList<>(object.fields().size());
                for (Ast.ObjectField field : object.fields()) {
                    fields.add(Ast.ObjectField.named(
                            field.name(),
                            expression(field.value())));
                }
                return new Ast.ObjectExpr(
                        fields,
                        object.kind(),
                        object.declaredFields());
            }
            if (expression instanceof Ast.LambdaExpr lambda) {
                return new Ast.LambdaExpr(
                        lambda.parameters(),
                        expression(lambda.expressionBody()),
                        lambda.blockBody() == null ? null : statements(lambda.blockBody()),
                        lambda.nonLexical());
            }
            throw new IllegalStateException(
                    "unhandled expression during Boolean intrinsic lowering: "
                            + expression.getClass().getSimpleName());
        }

        private Ast.Expr lowerScalar(String builtin, List<Ast.Expr> arguments) {
            final String operator = switch (builtin) {
                case "And" -> "&&";
                case "Or" -> "||";
                case "Xor" -> "^^";
                default -> throw new IllegalStateException(
                        "unknown resolved Boolean intrinsic " + builtin);
            };

            if (builtin.equals("Xor") && arguments.size() != 2) {
                throw new IllegalStateException(
                        "type checker admitted non-binary scalar Xor");
            }

            Ast.Expr lowered = arguments.getFirst();
            for (int i = 1; i < arguments.size(); i++) {
                lowered = new Ast.BinaryExpr(operator, lowered, arguments.get(i));
            }
            return lowered;
        }
    }
}
