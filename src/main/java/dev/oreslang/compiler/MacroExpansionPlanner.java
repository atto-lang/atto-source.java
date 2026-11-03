package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, side-effect-free front-end planning for source macro invocations.
 *
 * <p>This pass does not execute macros. It binds invocation syntax to an already
 * parsed MacroDecl, validates result-position and compiler-only argument
 * categories, and assigns a deterministic hygiene scope. The evaluator/expander
 * must consume this plan and remove every macro node before ordinary executable
 * semantic analysis or backend code generation.</p>
 */
public final class MacroExpansionPlanner {
    public enum Context { DECLARATION, EXPRESSION }

    public record HygieneMark(
            String sourceUnitId,
            String callerModule,
            String macroOwnerModule,
            String macroName,
            int line,
            int column,
            int sourceOrdinal) {
        public HygieneMark {
            if (sourceUnitId == null || sourceUnitId.isBlank()
                    || callerModule == null || callerModule.isBlank()
                    || macroOwnerModule == null || macroOwnerModule.isBlank()
                    || macroName == null || macroName.isBlank()
                    || macroName.indexOf('.') >= 0
                    || line < 1 || column < 1 || sourceOrdinal < 0) {
                throw new IllegalArgumentException("invalid canonical macro hygiene identity");
            }
        }
    }

    public record WorkItem(
            String module,
            Ast.MacroDecl macro,
            Context context,
            Ast.SourceSite site,
            HygieneMark hygieneMark) {
        public WorkItem {
            java.util.Objects.requireNonNull(module, "macro invocation module");
            java.util.Objects.requireNonNull(macro, "bound macro declaration");
            java.util.Objects.requireNonNull(context, "macro invocation context");
            java.util.Objects.requireNonNull(site, "macro invocation site");
            java.util.Objects.requireNonNull(hygieneMark, "macro hygiene mark");
        }
    }

    public record Plan(List<WorkItem> workItems) {
        public Plan { workItems = List.copyOf(workItems); }
        public boolean hasInvocations() { return !workItems.isEmpty(); }
    }

    private final Map<String, Ast.MacroDecl> macros = new LinkedHashMap<>();
    private final Map<String, LinkedMacroImports.Binding> importedMacros;
    private final List<WorkItem> workItems = new ArrayList<>();
    private final String sourceUnitId;
    private int ordinal;

    private MacroExpansionPlanner(
            String sourceUnitId,
            Map<String, LinkedMacroImports.Binding> importedMacros) {
        this.sourceUnitId = sourceUnitId;
        // Preserve explicit importer order for deterministic error admission.
        this.importedMacros = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(importedMacros));
    }

    public static Plan plan(Ast.Program program) {
        return plan(program, Map.of());
    }

    public static Plan plan(
            Ast.Program program,
            Map<String, LinkedMacroImports.Binding> importedMacros) {
        MacroExpansionPlanner planner =
                new MacroExpansionPlanner(MacroSourceUnitIdentity.unitId(program), importedMacros);
        planner.collect(program);
        planner.scan(program);
        return new Plan(planner.workItems);
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.MacroDecl macro)) continue;
                String key = module.name() + "." + macro.name();
                Ast.MacroDecl previous = macros.putIfAbsent(key, macro);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate define macro '" + macro.name()
                                    + "' in module '" + module.name() + "'");
                }
            }
        }
        for (Map.Entry<String, LinkedMacroImports.Binding> entry : importedMacros.entrySet()) {
            LinkedMacroImports.Binding binding = entry.getValue();
            binding.verify();
            if (macros.putIfAbsent(entry.getKey(), binding.declaration()) != null) {
                throw new IllegalArgumentException(
                        "imported macro alias collides with a local module macro: "
                                + entry.getKey());
            }
        }
    }

    private void scan(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                scanDeclaration(module.name(), declaration);
            }
        }
    }

    private void scanDeclaration(String module, Ast.Decl declaration) {
        if (declaration instanceof Ast.MacroDecl) {
            // A macro body is compile-time code. Nested invocations inside it
            // are discovered by the evaluator only if that macro executes.
            return;
        }
        if (declaration instanceof Ast.MacroInvocationDecl invocation) {
            scanMacroArguments(module, invocation.arguments());
            addInvocation(module, invocation.name(), invocation.arguments(),
                    Context.DECLARATION, invocation.site());
            return;
        }
        if (declaration instanceof Ast.FunctionDecl fn) {
            scanStatements(module, fn.body());
            return;
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            for (Ast.FieldDecl field : klass.fields()) {
                if (field.initializer() != null) scanExpr(module, field.initializer());
            }
            if (klass.constructor() != null) {
                scanStatements(module, klass.constructor().body());
            }
            for (Ast.MethodDecl method : klass.methods()) {
                scanStatements(module, method.body());
            }
            return;
        }
        if (declaration instanceof Ast.FieldDecl field && field.initializer() != null) {
            scanExpr(module, field.initializer());
        }
    }

    private void addInvocation(
            String module,
            String name,
            List<Ast.MacroArgument> arguments,
            Context context,
            Ast.SourceSite site) {
        Ast.MacroDecl macro = resolve(module, name);
        validateResultContext(macro, context, name);
        validateArguments(macro, arguments, name);
        int sourceOrdinal = ordinal++;
        workItems.add(new WorkItem(
                module, macro, context, site,
                hygieneMark(
                        sourceUnitId,
                        module,
                        macroOwnerModule(module, name),
                        macro.name(),
                        site,
                        sourceOrdinal)));
    }

    private Ast.MacroDecl resolve(String module, String name) {
        Ast.MacroDecl macro;
        String owner = module;
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
                            + "' in module '" + module
                            + "'; runtime call fallback is forbidden");
        }
        if (!owner.equals(module) && macro.visibility() != Ast.Visibility.PUBLIC) {
            throw new IllegalArgumentException(
                    "private compile-time macro '" + name
                            + "' is not visible from module '" + module
                            + "'; macro visibility cannot be bypassed by qualification");
        }
        return macro;
    }

    private void validateResultContext(Ast.MacroDecl macro, Context context, String name) {
        Ast.MacroResultKind result = macro.resultKind();
        boolean valid = switch (context) {
            case DECLARATION -> result.syntaxKind() == Ast.MacroSyntaxKind.DECL;
            case EXPRESSION -> !result.list()
                    && result.syntaxKind() == Ast.MacroSyntaxKind.EXPR;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "macro '" + name + "' returns "
                            + render(result) + " and cannot be used in "
                            + context.name().toLowerCase() + " position");
        }
    }

    private void validateArguments(
            Ast.MacroDecl macro,
            List<Ast.MacroArgument> arguments,
            String invocationName) {
        if (arguments.size() != macro.parameters().size()) {
            throw new IllegalArgumentException(
                    "macro '" + invocationName + "' expects "
                            + macro.parameters().size() + " compile-time argument(s), got "
                            + arguments.size());
        }

        for (int i = 0; i < arguments.size(); i++) {
            Ast.MacroParam parameter = macro.parameters().get(i);
            Ast.MacroArgument argument = arguments.get(i);
            boolean valid = switch (parameter.type().kind()) {
                case TYPE -> argument instanceof Ast.MacroTypeArgument;
                case CONST -> argument instanceof Ast.MacroConstArgument;
                case IDENTIFIER -> argument instanceof Ast.MacroIdentifierArgument;
                case SYNTAX -> syntaxArgumentMatches(
                        parameter.type().syntaxKind(), argument);
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        "macro '" + invocationName + "' argument " + (i + 1)
                                + " for parameter '" + parameter.name()
                                + "' requires " + render(parameter.type())
                                + ", got " + render(argument));
            }
            if (argument instanceof Ast.MacroConstArgument constant) {
                validateClosedConstType(
                        invocationName, parameter, constant.expression());
            }
        }
    }

    private void validateClosedConstType(
            String invocationName,
            Ast.MacroParam parameter,
            Ast.Expr expression) {
        Ast.TypeRef expected = parameter.type().constType();
        CompileTimeConstEvaluator.Evaluation evaluated;
        try {
            evaluated = expression instanceof Ast.ListExpr list && list.elements().isEmpty()
                    ? CompileTimeConstEvaluator.evaluate(expression, expected)
                    : CompileTimeConstEvaluator.evaluate(expression);
        } catch (CompileTimeConstEvaluator.Failure failure) {
            String detail = switch (failure.kind()) {
                case RESOURCE -> "exceeds deterministic compile-time evaluation budgets: "
                        + failure.getMessage();
                case INVALID -> "is not a valid compile-time constant: "
                        + failure.getMessage();
                case RUNTIME_DEPENDENT, UNSUPPORTED ->
                        "is not a closed compile-time expression on this compiler head: "
                                + failure.getMessage();
            };
            throw new IllegalArgumentException(
                    "macro '" + invocationName + "' Const<"
                            + CompileTimeConstEvaluator.displayType(expected)
                            + "> argument for '" + parameter.name() + "' "
                            + detail + "; runtime evaluation fallback is forbidden",
                    failure);
        }
        if (!CompileTimeConstEvaluator.typeMatches(expected, evaluated.type())) {
            throw new IllegalArgumentException(
                    "macro '" + invocationName + "' Const<"
                            + CompileTimeConstEvaluator.displayType(expected)
                            + "> argument for '" + parameter.name()
                            + "' has compile-time type "
                            + CompileTimeConstEvaluator.displayType(evaluated.type())
                            + "; macro Const<T> arguments must match their declared type exactly");
        }
    }

    private boolean syntaxArgumentMatches(
            Ast.MacroSyntaxKind expected,
            Ast.MacroArgument argument) {
        return switch (expected) {
            case EXPR -> argument instanceof Ast.MacroSyntaxExprArgument;
            case STMT -> argument instanceof Ast.MacroSyntaxStmtArgument;
            case TYPE -> argument instanceof Ast.MacroSyntaxTypeArgument;
            case PATTERN -> argument instanceof Ast.MacroSyntaxPatternArgument;
            case DECL -> false; // source declaration quotation remains intentionally closed
        };
    }

    private void scanMacroArguments(String module, List<Ast.MacroArgument> arguments) {
        // Syntax<K> is quoted compiler data: do not expand invocations inside
        // it until/unless a macro later splices that syntax into output.
        for (Ast.MacroArgument argument : arguments) {
            if (argument instanceof Ast.MacroConstArgument constant) {
                scanExpr(module, constant.expression());
            }
        }
    }

    private void scanStatements(String module, List<Ast.Stmt> statements) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt s) scanExpr(module, s.initializer());
            else if (statement instanceof Ast.DestructureStmt s) scanExpr(module, s.initializer());
            else if (statement instanceof Ast.ReturnStmt s && s.value() != null) scanExpr(module, s.value());
            else if (statement instanceof Ast.YieldStmt s) scanExpr(module, s.value());
            else if (statement instanceof Ast.ExprStmt s) scanExpr(module, s.expression());
            else if (statement instanceof Ast.DeferStmt s) scanExpr(module, s.expression());
            else if (statement instanceof Ast.BlockStmt s) scanStatements(module, s.body());
            else if (statement instanceof Ast.TaskScopeStmt s) scanStatements(module, s.body());
            else if (statement instanceof Ast.UsingBorrowStmt s) {
                scanExpr(module, s.resource());
                scanStatements(module, s.body());
            } else if (statement instanceof Ast.IfStmt s) {
                for (Ast.IfBranch branch : s.branches()) {
                    scanExpr(module, branch.condition());
                    scanStatements(module, branch.body());
                }
                scanStatements(module, s.elseBody());
            } else if (statement instanceof Ast.MatchStmt s) {
                scanExpr(module, s.subject());
                for (Ast.MatchArm arm : s.arms()) {
                    if (arm.guard() != null) scanExpr(module, arm.guard());
                    scanStatements(module, arm.body());
                }
            } else if (statement instanceof Ast.SwitchStmt s) {
                scanExpr(module, s.subject());
                for (Ast.SwitchCase arm : s.cases()) {
                    for (Ast.Expr constant : arm.constants()) scanExpr(module, constant);
                    scanStatements(module, arm.body());
                }
                scanStatements(module, s.defaultBody());
            } else if (statement instanceof Ast.TryStmt s) {
                scanStatements(module, s.body());
                scanStatements(module, s.catchBody());
                scanStatements(module, s.finallyBody());
            } else if (statement instanceof Ast.ForOfStmt s) {
                scanExpr(module, s.iterable());
                scanStatements(module, s.body());
            } else if (statement instanceof Ast.ForOfDestructureStmt s) {
                scanExpr(module, s.iterable());
                scanStatements(module, s.body());
            } else if (statement instanceof Ast.ForStmt s) {
                if (s.initializer() != null) scanStatements(module, List.of(s.initializer()));
                if (s.condition() != null) scanExpr(module, s.condition());
                if (s.update() != null) scanExpr(module, s.update());
                scanStatements(module, s.body());
            } else if (statement instanceof Ast.DoWhileStmt s) {
                scanStatements(module, s.body());
                scanExpr(module, s.condition());
            } else if (statement instanceof Ast.LoopStmt s) {
                scanStatements(module, s.body());
            } else if (statement instanceof Ast.SelectStmt s) {
                for (Ast.SelectArm arm : s.arms()) {
                    if (arm.channel() != null) scanExpr(module, arm.channel());
                    if (arm.value() != null) scanExpr(module, arm.value());
                    scanStatements(module, arm.body());
                }
            }
        }
    }

    private void scanExpr(String module, Ast.Expr expression) {
        if (expression == null) return;
        if (expression instanceof Ast.MacroInvocationExpr invocation) {
            scanMacroArguments(module, invocation.arguments());
            addInvocation(module, invocation.name(), invocation.arguments(),
                    Context.EXPRESSION, invocation.site());
            return;
        }
        if (expression instanceof Ast.BinaryExpr e) {
            scanExpr(module, e.left());
            scanExpr(module, e.right());
        } else if (expression instanceof Ast.UnaryExpr e) {
            scanExpr(module, e.operand());
        } else if (expression instanceof Ast.AssignExpr e) {
            scanExpr(module, e.target());
            scanExpr(module, e.value());
        } else if (expression instanceof Ast.ConditionalExpr e) {
            scanExpr(module, e.condition());
            scanExpr(module, e.whenTrue());
            scanExpr(module, e.whenFalse());
        } else if (expression instanceof Ast.MatchExpr e) {
            scanExpr(module, e.subject());
            for (Ast.MatchValueArm arm : e.arms()) {
                if (arm.guard() != null) scanExpr(module, arm.guard());
                scanExpr(module, arm.value());
            }
        } else if (expression instanceof Ast.TypeTestExpr e) {
            scanExpr(module, e.value());
        } else if (expression instanceof Ast.PatternTestExpr e) {
            scanExpr(module, e.value());
        } else if (expression instanceof Ast.CastExpr e) {
            scanExpr(module, e.value());
        } else if (expression instanceof Ast.SpreadExpr e) {
            scanExpr(module, e.expression());
        } else if (expression instanceof Ast.CallExpr e) {
            scanExpr(module, e.callee());
            for (Ast.Expr argument : e.arguments()) scanExpr(module, argument);
        } else if (expression instanceof Ast.RuntimeCallExpr e) {
            for (Ast.Expr argument : e.arguments()) scanExpr(module, argument);
        } else if (expression instanceof Ast.MemberExpr e) {
            scanExpr(module, e.receiver());
        } else if (expression instanceof Ast.IndexExpr e) {
            scanExpr(module, e.receiver());
            scanExpr(module, e.index());
        } else if (expression instanceof Ast.NewExpr e) {
            for (Ast.Expr argument : e.arguments()) scanExpr(module, argument);
        } else if (expression instanceof Ast.AwaitExpr e) {
            scanExpr(module, e.expression());
        } else if (expression instanceof Ast.TaskExpr e) {
            scanStatements(module, e.body());
        } else if (expression instanceof Ast.TrapExpr e) {
            scanExpr(module, e.expression());
        } else if (expression instanceof Ast.ChannelOpExpr e) {
            scanExpr(module, e.channel());
            if (e.value() != null) scanExpr(module, e.value());
            scanStatements(module, e.callbackBody());
        } else if (expression instanceof Ast.DynamicSelectExpr e) {
            scanExpr(module, e.cases());
        } else if (expression instanceof Ast.ListExpr e) {
            for (Ast.Expr item : e.elements()) scanExpr(module, item);
        } else if (expression instanceof Ast.TupleExpr e) {
            for (Ast.Expr item : e.elements()) scanExpr(module, item);
        } else if (expression instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) scanExpr(module, field.value());
        } else if (expression instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) scanExpr(module, e.expressionBody());
            if (e.blockBody() != null) scanStatements(module, e.blockBody());
        }
    }

    private String render(Ast.MacroResultKind result) {
        if (result.list()) return "SyntaxList<Decl>";
        return "Syntax<" + syntaxName(result.syntaxKind()) + ">";
    }

    private String render(Ast.MacroParamType type) {
        return switch (type.kind()) {
            case TYPE -> "Type";
            case IDENTIFIER -> "Identifier";
            case CONST -> "Const<" + type.constType().name() + ">";
            case SYNTAX -> "Syntax<" + syntaxName(type.syntaxKind()) + ">";
        };
    }

    private String render(Ast.MacroArgument argument) {
        if (argument instanceof Ast.MacroTypeArgument) return "type";
        if (argument instanceof Ast.MacroConstArgument) return "const";
        if (argument instanceof Ast.MacroIdentifierArgument) return "Identifier";
        if (argument instanceof Ast.MacroSyntaxExprArgument) return "Syntax<Expr>";
        if (argument instanceof Ast.MacroSyntaxStmtArgument) return "Syntax<Stmt>";
        if (argument instanceof Ast.MacroSyntaxTypeArgument) return "Syntax<Type>";
        if (argument instanceof Ast.MacroSyntaxPatternArgument) return "Syntax<Pattern>";
        return argument.getClass().getSimpleName();
    }

    private String syntaxName(Ast.MacroSyntaxKind kind) {
        return switch (kind) {
            case EXPR -> "Expr";
            case STMT -> "Stmt";
            case DECL -> "Decl";
            case TYPE -> "Type";
            case PATTERN -> "Pattern";
        };
    }

    private String macroOwnerModule(String callerModule, String invocationName) {
        LinkedMacroImports.Binding imported = importedMacros.get(invocationName);
        if (imported != null) return imported.canonicalOwnerIdentity();
        int separator = invocationName.lastIndexOf('.');
        return separator >= 0
                ? invocationName.substring(0, separator)
                : callerModule;
    }

    private HygieneMark hygieneMark(
            String sourceUnitId,
            String callerModule,
            String macroOwnerModule,
            String macroName,
            Ast.SourceSite site,
            int sourceOrdinal) {
        return new HygieneMark(
                sourceUnitId,
                callerModule,
                macroOwnerModule,
                macroName,
                site.line(),
                site.column(),
                sourceOrdinal);
    }

}
