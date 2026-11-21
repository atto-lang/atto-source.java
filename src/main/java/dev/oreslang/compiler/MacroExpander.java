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
 * caller-supplied syntax passthrough and bounded deterministic Const&lt;T&gt; quotation.
 *
 * <pre>
 * define macro identity(Syntax&lt;Expr&gt; value) -> Syntax&lt;Expr&gt; {
 *     return value;
 * }
 * </pre>
 *
 * Returned syntax may be caller-owned AST, compiler-evaluated closed constant
 * AST, exact-marked generated identifiers, checked function captures, or the
 * narrow private declare_fnc declaration form. Identifier/capture authority is
 * explicit and compiler-only; source-string generation remains forbidden. All
 * unsupported bodies/result positions fail closed. Expansion happens before
 * ordinary type/effect/ownership checks and leaves no macro declarations or
 * invocations in the returned Program.</p>
 */
public final class MacroExpander {
    static final int MAX_EXPANSIONS = 1_024;
    static final int MAX_EXPANSION_DEPTH = 64;
    static final int MAX_TEMPLATE_DEPTH = 64;
    static final int MAX_TEMPLATE_NODES = 2_048;
    static final int MAX_REWRITE_TRAVERSAL_DEPTH = 512;
    static final int MAX_REWRITE_TRAVERSAL_NODES = 1_000_000;

    private final Map<String, Ast.MacroDecl> macros = new HashMap<>();
    private final Map<String, LinkedMacroImports.Binding> importedMacros;
    private final Map<String, List<Ast.Decl>> declarationsByModule = new HashMap<>();
    private final Map<String, Set<String>> occupiedDeclarationNames = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private final String sourceUnitId;
    private int expansions;
    private int rewriteTraversalDepth;
    private int rewriteTraversalNodes;

    private MacroExpander(
            String sourceUnitId,
            Map<String, LinkedMacroImports.Binding> importedMacros) {
        this.sourceUnitId = sourceUnitId;
        // Preserve explicit importer order for deterministic error admission.
        this.importedMacros = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(importedMacros));
    }

    public static Ast.Program expand(Ast.Program program) {
        return expand(program, Map.of());
    }

    public static Ast.Program expand(
            Ast.Program program,
            Map<String, LinkedMacroImports.Binding> importedMacros) {
        MacroExpansionPlanner.Plan plan = MacroExpansionPlanner.plan(program, importedMacros);
        boolean hasMacroDeclarations = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .anyMatch(Ast.MacroDecl.class::isInstance);
        if (!plan.hasInvocations() && !hasMacroDeclarations) return program;

        MacroExpander expander =
                new MacroExpander(MacroSourceUnitIdentity.unitId(program), importedMacros);
        expander.index(program);
        expander.indexImported();
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
                    declarations.add(expander.expandDeclarationInvocation(
                            module.name(), invocation, 0));
                    continue;
                }
                declarations.add(expander.rewriteDeclaration(
                        module.name(), declaration, 0));
            }
            modules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), declarations));
        }
        // Preserve the logical compile-time source identity on the *new* AST.
        // Losing it here makes later hygiene/provenance consumers treat
        // incremental file units as unrelated <memory> compilations.
        return MacroSourceUnitIdentity.attach(
                new Ast.Program(program.namespace(), program.imports(), modules),
                expander.sourceUnitId);
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            declarationsByModule.put(module.name(), module.declarations());
            HashSet<String> occupied = new HashSet<>();
            for (Ast.Decl declaration : module.declarations()) {
                String declarationName = runtimeDeclarationName(declaration);
                if (declarationName != null) occupied.add(declarationName);
                if (!(declaration instanceof Ast.MacroDecl macro)) continue;
                macros.put(module.name() + "." + macro.name(), macro);
            }
            occupiedDeclarationNames.put(module.name(), occupied);
        }
    }

    private void indexImported() {
        for (Map.Entry<String, LinkedMacroImports.Binding> entry : importedMacros.entrySet()) {
            LinkedMacroImports.Binding binding = entry.getValue();
            binding.verify();
            Ast.MacroDecl macro = binding.declaration();
            // A wildcard import may expose more public macros than the caller
            // uses. Validate every descriptor, but reject unsupported evaluator
            // forms only when invoked; unused imports mint no guest authority.
            if (macros.putIfAbsent(entry.getKey(), macro) != null) {
                throw new IllegalArgumentException(
                        "imported macro alias collides with local macro: " + entry.getKey());
            }
        }
    }

    private String runtimeDeclarationName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        return null;
    }

    private void requireSupportedDeclarations(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.MacroDecl macro)) continue;
                if (!isSyntaxExprPassthrough(macro)
                        && !isConstLiteralQuote(macro)
                        && !isSyntaxExprTemplateQuote(macro)
                        && !isIdentifierIntrinsic(macro, "hygienic")
                        && !isIdentifierIntrinsic(macro, "capture_fnc")
                        && !isDeclareFunctionMacro(macro)) {
                    throw unsupported(
                            macro.name(),
                            "the first evaluator slice only admits Syntax<Expr> identity, "
                                    + "bounded Const<T> quotation, identifier-free "
                                    + "Syntax<Expr> quote/unquote templates, generated hygienic "
                                    + "Identifier emission, checked module-function capture, "
                                    + "or the private zero-arg declare_fnc declaration builder");
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
     * Compiler-only constant quotation: a macro accepting Const<T> can emit
     * deterministic, compiler-evaluated closed AST without a runtime macro engine.
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
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent()
                || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.NameExpr input)) {
            return false;
        }
        return input.name().equals(parameter.name());
    }

    /**
     * Typed expression quasiquote subset. Templates may construct only
     * identifier-free expression structure and splice Syntax<Expr> parameters
     * through direct unquote(parameter) calls. Any introduced name/callee stays
     * closed until generated-identifier hygiene is implemented.
     */
    private boolean isSyntaxExprTemplateQuote(Ast.MacroDecl macro) {
        if (macro.resultKind().list()
                || macro.resultKind().syntaxKind() != Ast.MacroSyntaxKind.EXPR
                || macro.body().size() != 1) {
            return false;
        }
        java.util.HashSet<String> syntaxParameters = new java.util.HashSet<>();
        for (Ast.MacroParam parameter : macro.parameters()) {
            if (parameter.type().kind() != Ast.MacroParamKind.SYNTAX
                    || parameter.type().syntaxKind() != Ast.MacroSyntaxKind.EXPR) {
                return false;
            }
            syntaxParameters.add(parameter.name());
        }
        Ast.Expr template = quoteTemplate(macro.body().getFirst());
        if (template == null) return false;
        Map<String, Integer> uses = new HashMap<>();
        if (!safeTemplateExpression(template, syntaxParameters, uses)) return false;
        // No caller syntax is discarded before ordinary semantic and ownership
        // checks. A source expression is never silently removed by a template.
        return syntaxParameters.stream().allMatch(name -> uses.getOrDefault(name, 0) > 0);
    }

    private Ast.Expr quoteTemplate(Ast.Stmt statement) {
        if (!(statement instanceof Ast.ReturnStmt returned)
                || !(returned.value() instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.NameExpr callee)
                || !callee.name().equals("quote")
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent()
                || call.arguments().size() != 1) {
            return null;
        }
        return call.arguments().getFirst();
    }

    private record TemplateNode(Ast.Expr expression, int depth) { }

    /**
     * Iterative, quota-limited validation to avoid recursive compiler-stack
     * exhaustion from a large but syntactically well-formed quote template.
     * Counts every splice so the caller expression cannot be erased.
     */
    private boolean safeTemplateExpression(
            Ast.Expr expression,
            Set<String> syntaxParameters,
            Map<String, Integer> uses) {
        java.util.ArrayDeque<TemplateNode> pending = new java.util.ArrayDeque<>();
        pending.push(new TemplateNode(expression, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            TemplateNode next = pending.pop();
            if (++nodes > MAX_TEMPLATE_NODES || next.depth() >= MAX_TEMPLATE_DEPTH) {
                throw new IllegalArgumentException(
                        "compile-time macro template budget exceeded (nodes/depth); "
                                + "runtime fallback is forbidden");
            }
            Ast.Expr node = next.expression();
            int childDepth = next.depth() + 1;
            if (node instanceof Ast.LiteralExpr literal) {
                Object value = literal.value();
                if (!(value instanceof Long || value instanceof Double
                        || value instanceof String || value instanceof Boolean
                        || value instanceof dev.oreslang.runtime.OresSymbol)) return false;
                if (value instanceof Double floating && !Double.isFinite(floating)) {
                    throw new IllegalArgumentException(
                            "compile-time macro template non-finite float is forbidden; "
                                    + "runtime fallback is forbidden");
                }
                if (value instanceof String text
                        && text.length() > CompileTimeConstEvaluator.MAX_STRING_CHARS) {
                    throw new IllegalArgumentException(
                            "compile-time macro template literal string budget exceeded; "
                                    + "runtime fallback is forbidden");
                }
                continue;
            }
            if (node instanceof Ast.UnaryExpr unary) {
                pending.push(new TemplateNode(unary.operand(), childDepth));
                continue;
            }
            if (node instanceof Ast.BinaryExpr binary) {
                pending.push(new TemplateNode(binary.right(), childDepth));
                pending.push(new TemplateNode(binary.left(), childDepth));
                continue;
            }
            if (node instanceof Ast.ConditionalExpr conditional) {
                pending.push(new TemplateNode(conditional.whenFalse(), childDepth));
                pending.push(new TemplateNode(conditional.whenTrue(), childDepth));
                pending.push(new TemplateNode(conditional.condition(), childDepth));
                continue;
            }
            if (node instanceof Ast.ListExpr list) {
                checkTemplateQueueBudget(nodes, pending.size(), list.elements().size());
                for (Ast.Expr child : list.elements()) {
                    pending.push(new TemplateNode(child, childDepth));
                }
                continue;
            }
            if (node instanceof Ast.TupleExpr tuple) {
                checkTemplateQueueBudget(nodes, pending.size(), tuple.elements().size());
                for (Ast.Expr child : tuple.elements()) {
                    pending.push(new TemplateNode(child, childDepth));
                }
                continue;
            }
            if (node instanceof Ast.ObjectExpr object
                    && object.kind() != Ast.ObjectKind.EXPLICIT_STRUCT) {
                checkTemplateQueueBudget(nodes, pending.size(), object.fields().size());
                for (Ast.ObjectField field : object.fields()) {
                    pending.push(new TemplateNode(field.value(), childDepth));
                }
                continue;
            }
            if (node instanceof Ast.CallExpr call && isDirectUnquote(call)
                    && call.arguments().getFirst() instanceof Ast.NameExpr name
                    && syntaxParameters.contains(name.name())) {
                uses.merge(name.name(), 1, Integer::sum);
                continue;
            }
            // No new identifiers, guest calls, unbound captures, actor handles,
            // runtime capabilities or unchecked compiler invocations.
            return false;
        }
        return true;
    }

    private void checkTemplateQueueBudget(int visited, int pending, int children) {
        if ((long) visited + pending + children > MAX_TEMPLATE_NODES) {
            throw new IllegalArgumentException(
                    "compile-time macro template budget exceeded (nodes/depth); "
                            + "runtime fallback is forbidden");
        }
    }

    private boolean isDirectUnquote(Ast.CallExpr call) {
        return call.callee() instanceof Ast.NameExpr callee
                && callee.name().equals("unquote")
                && call.origin() == Ast.CallOrigin.DIRECT
                && !call.typeArgumentsPresent()
                && call.arguments().size() == 1;
    }

    /**
     * Copy the deliberately bounded caller-syntax expression subset.
     *
     * <p>Every expression receives a fresh identity. Checked semantic metadata
     * and macro origins follow that exact copied node. Compiler-internal names,
     * authority-bearing identifiers and unsupported nodes fail closed rather
     * than being shared or cloned without their identity-bound proof.</p>
     */
    private Ast.Expr copyCallerSyntax(Ast.Expr source, int depth, int[] visited) {
        if (source == null) {
            throw new IllegalArgumentException("unquote syntax cannot contain a null AST node");
        }
        if (depth > MAX_TEMPLATE_DEPTH || ++visited[0] > MAX_TEMPLATE_NODES) {
            throw new IllegalArgumentException(
                    "unquote syntax copy budget exceeded (nodes/depth); runtime fallback is forbidden");
        }

        Ast.Expr copy;
        if (source instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (!(value instanceof Long || value instanceof Double
                    || value instanceof String || value instanceof Boolean
                    || value instanceof dev.oreslang.runtime.OresSymbol
                    || value instanceof Ast.Imaginary)) {
                throw new IllegalArgumentException(
                        "unquote literal is not an admitted immutable scalar");
            }
            copy = new Ast.LiteralExpr(value);
        } else if (source instanceof Ast.NameExpr name) {
            if (name.name().startsWith("$")
                    || MacroIdentifierHygiene.lookup(name) != null) {
                throw new IllegalArgumentException(
                        "unquote of compiler-internal or authority-bearing identifier is denied");
            }
            copy = new Ast.NameExpr(name.name());
        } else if (source instanceof Ast.UnaryExpr unary) {
            copy = new Ast.UnaryExpr(
                    unary.operator(),
                    copyCallerSyntax(unary.operand(), depth + 1, visited));
        } else if (source instanceof Ast.BinaryExpr binary) {
            copy = new Ast.BinaryExpr(
                    binary.operator(),
                    copyCallerSyntax(binary.left(), depth + 1, visited),
                    copyCallerSyntax(binary.right(), depth + 1, visited));
        } else if (source instanceof Ast.AssignExpr assignment) {
            copy = new Ast.AssignExpr(
                    copyCallerSyntax(assignment.target(), depth + 1, visited),
                    copyCallerSyntax(assignment.value(), depth + 1, visited));
        } else if (source instanceof Ast.ConditionalExpr conditional) {
            copy = new Ast.ConditionalExpr(
                    copyCallerSyntax(conditional.condition(), depth + 1, visited),
                    copyCallerSyntax(conditional.whenTrue(), depth + 1, visited),
                    copyCallerSyntax(conditional.whenFalse(), depth + 1, visited));
        } else if (source instanceof Ast.SpreadExpr spread) {
            copy = new Ast.SpreadExpr(
                    copyCallerSyntax(spread.expression(), depth + 1, visited));
        } else if (source instanceof Ast.MemberExpr member) {
            copy = new Ast.MemberExpr(
                    copyCallerSyntax(member.receiver(), depth + 1, visited),
                    member.member());
        } else if (source instanceof Ast.IndexExpr index) {
            copy = new Ast.IndexExpr(
                    copyCallerSyntax(index.receiver(), depth + 1, visited),
                    copyCallerSyntax(index.index(), depth + 1, visited));
        } else if (source instanceof Ast.CallExpr call) {
            copy = call.rewrite(
                    copyCallerSyntax(call.callee(), depth + 1, visited),
                    call.typeArguments(),
                    call.typeArgumentsPresent(),
                    call.arguments().stream()
                            .map(argument -> copyCallerSyntax(argument, depth + 1, visited))
                            .toList());
        } else if (source instanceof Ast.RuntimeCallExpr runtimeCall) {
            copy = new Ast.RuntimeCallExpr(
                    runtimeCall.operation(),
                    runtimeCall.arguments().stream()
                            .map(argument -> copyCallerSyntax(argument, depth + 1, visited))
                            .toList());
        } else if (source instanceof Ast.NewExpr created) {
            copy = new Ast.NewExpr(
                    created.type(),
                    created.arguments().stream()
                            .map(argument -> copyCallerSyntax(argument, depth + 1, visited))
                            .toList());
        } else if (source instanceof Ast.AwaitExpr awaited) {
            copy = new Ast.AwaitExpr(
                    copyCallerSyntax(awaited.expression(), depth + 1, visited));
        } else if (source instanceof Ast.ListExpr list) {
            copy = new Ast.ListExpr(list.elements().stream()
                    .map(item -> copyCallerSyntax(item, depth + 1, visited))
                    .toList());
        } else if (source instanceof Ast.TupleExpr tuple) {
            copy = new Ast.TupleExpr(tuple.elements().stream()
                    .map(item -> copyCallerSyntax(item, depth + 1, visited))
                    .toList());
        } else if (source instanceof Ast.ObjectExpr object
                && object.kind() != Ast.ObjectKind.EXPLICIT_STRUCT) {
            List<Ast.ObjectField> fields = new ArrayList<>(object.fields().size());
            for (Ast.ObjectField field : object.fields()) {
                fields.add(Ast.ObjectField.named(
                        field.name(),
                        copyCallerSyntax(field.value(), depth + 1, visited)));
            }
            copy = new Ast.ObjectExpr(fields, object.kind(), object.declaredFields());
        } else {
            throw new IllegalArgumentException(
                    "unquote syntax node " + source.getClass().getSimpleName()
                            + " is outside the bounded deep-copy allowlist; "
                            + "runtime fallback is forbidden");
        }

        Ast.copyCheckedMetadata(source, copy);
        MacroSyntaxProvenance.copyOrigins(source, copy);
        return copy;
    }

    private Ast.Expr instantiateTemplate(
            Ast.Expr expression,
            Map<String, Ast.Expr> syntaxBindings) {
        if (expression instanceof Ast.CallExpr call && isDirectUnquote(call)) {
            if (!(call.arguments().getFirst() instanceof Ast.NameExpr name)) {
                throw new IllegalStateException("validated unquote lost identifier parameter");
            }
            Ast.Expr bound = syntaxBindings.get(name.name());
            if (bound == null) {
                throw new IllegalStateException(
                        "validated unquote references unknown syntax parameter '" + name.name() + "'");
            }
            return copyCallerSyntax(bound, 0, new int[] {0});
        }
        if (expression instanceof Ast.LiteralExpr literal) {
            // Each template instantiation owns its own AST identity. Reusing the
            // literal node from the macro declaration would alias unrelated
            // invocation sites in identity-keyed provenance/semantic side tables.
            return new Ast.LiteralExpr(literal.value());
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return new Ast.UnaryExpr(
                    unary.operator(),
                    instantiateTemplate(unary.operand(), syntaxBindings));
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return new Ast.BinaryExpr(
                    binary.operator(),
                    instantiateTemplate(binary.left(), syntaxBindings),
                    instantiateTemplate(binary.right(), syntaxBindings));
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            return new Ast.ConditionalExpr(
                    instantiateTemplate(conditional.condition(), syntaxBindings),
                    instantiateTemplate(conditional.whenTrue(), syntaxBindings),
                    instantiateTemplate(conditional.whenFalse(), syntaxBindings));
        }
        if (expression instanceof Ast.ListExpr list) {
            return new Ast.ListExpr(list.elements().stream()
                    .map(item -> instantiateTemplate(item, syntaxBindings))
                    .toList());
        }
        if (expression instanceof Ast.TupleExpr tuple) {
            return new Ast.TupleExpr(tuple.elements().stream()
                    .map(item -> instantiateTemplate(item, syntaxBindings))
                    .toList());
        }
        if (expression instanceof Ast.ObjectExpr object) {
            List<Ast.ObjectField> fields = new ArrayList<>(object.fields().size());
            for (Ast.ObjectField field : object.fields()) {
                fields.add(Ast.ObjectField.named(
                        field.name(),
                        instantiateTemplate(field.value(), syntaxBindings)));
            }
            return new Ast.ObjectExpr(fields, object.kind(), object.declaredFields());
        }
        throw new IllegalStateException(
                "validated macro template reached unsupported node "
                        + expression.getClass().getSimpleName());
    }

    private boolean isIdentifierIntrinsic(Ast.MacroDecl macro, String intrinsic) {
        if (macro.resultKind().list()
                || macro.resultKind().syntaxKind() != Ast.MacroSyntaxKind.EXPR
                || macro.parameters().size() != 1
                || macro.body().size() != 1
                || macro.parameters().getFirst().type().kind()
                        != Ast.MacroParamKind.IDENTIFIER) {
            return false;
        }
        if (!(macro.body().getFirst() instanceof Ast.ReturnStmt returned)
                || !(returned.value() instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.NameExpr callee)
                || !callee.name().equals(intrinsic)
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent()
                || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.NameExpr argument)) {
            return false;
        }
        return argument.name().equals(macro.parameters().getFirst().name());
    }

    private List<Ast.FunctionDecl> resolveCapturedFunctions(
            String callerModule,
            String macroOwnerModule,
            Ast.MacroIdentifierArgument identifier) {
        List<Ast.FunctionDecl> ordinary = declarationsByModule
                .getOrDefault(callerModule, List.of())
                .stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals(identifier.name()))
                .filter(function -> function.actorKind() == Ast.ActorKind.NONE)
                .toList();
        if (ordinary.isEmpty()) {
            throw new IllegalArgumentException(
                    "checked capture_fnc could not resolve ordinary module-level function '"
                            + identifier.name() + "' at call site "
                            + identifier.site().line() + ":" + identifier.site().column()
                            + " in module '" + callerModule + "'");
        }
        if (ordinary.stream().anyMatch(
                function -> function.visibility() != Ast.Visibility.PUBLIC)) {
            throw new IllegalArgumentException(
                    "checked capture_fnc requires public function '"
                            + identifier.name()
                            + "' because capture is lowered to an explicit module reference; "
                            + "private function capture remains closed on this compiler head");
        }
        if (ordinary.size() != 1) {
            throw new IllegalArgumentException(
                    "checked capture_fnc cannot reify overloaded function family '"
                            + identifier.name()
                            + "' as one function value on this compiler head");
        }
        Ast.FunctionDecl function = ordinary.getFirst();
        if (function.kind() != Ast.CallableKind.FNC) {
            throw new IllegalArgumentException(
                    "checked capture_fnc cannot reify routine '"
                            + identifier.name() + "' as a function value");
        }
        if (!function.genericParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "checked capture_fnc cannot reify generic function '"
                            + identifier.name()
                            + "' without an explicit specialization");
        }
        if (function.suspends()) {
            throw new IllegalArgumentException(
                    "checked capture_fnc cannot reify suspends function '"
                            + identifier.name()
                            + "' until function values encode suspension");
        }
        return List.of(function);
    }

    private boolean isDeclareFunctionMacro(Ast.MacroDecl macro) {
        if (macro.resultKind().list()
                || macro.resultKind().syntaxKind() != Ast.MacroSyntaxKind.DECL
                || macro.parameters().size() != 3
                || macro.body().size() != 1) {
            return false;
        }
        Ast.MacroParam name = macro.parameters().get(0);
        Ast.MacroParam returnType = macro.parameters().get(1);
        Ast.MacroParam body = macro.parameters().get(2);
        if (name.type().kind() != Ast.MacroParamKind.IDENTIFIER
                || returnType.type().kind() != Ast.MacroParamKind.TYPE
                || body.type().kind() != Ast.MacroParamKind.SYNTAX
                || body.type().syntaxKind() != Ast.MacroSyntaxKind.EXPR) {
            return false;
        }
        if (!(macro.body().getFirst() instanceof Ast.ReturnStmt returned)
                || !(returned.value() instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.NameExpr callee)
                || !callee.name().equals("declare_fnc")
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent()
                || call.arguments().size() != 3) {
            return false;
        }
        return call.arguments().get(0) instanceof Ast.NameExpr first
                && first.name().equals(name.name())
                && call.arguments().get(1) instanceof Ast.NameExpr second
                && second.name().equals(returnType.name())
                && call.arguments().get(2) instanceof Ast.NameExpr third
                && third.name().equals(body.name());
    }

    private int nextExpansionOrdinal(int depth) {
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
        return expansions - 1;
    }

    private Ast.Decl expandDeclarationInvocation(
            String module,
            Ast.MacroInvocationDecl invocation,
            int depth) {
        int expansionOrdinal = nextExpansionOrdinal(depth);
        Ast.MacroDecl macro = resolve(module, invocation.name());
        if (!isDeclareFunctionMacro(macro)) {
            throw unsupported(
                    invocation.name(),
                    "declaration output currently admits only "
                            + "declare_fnc(Identifier, Type, Syntax<Expr>)");
        }
        if (invocation.arguments().size() != 3
                || !(invocation.arguments().get(0)
                        instanceof Ast.MacroIdentifierArgument identifier)
                || !(invocation.arguments().get(1)
                        instanceof Ast.MacroTypeArgument returnType)
                || !(invocation.arguments().get(2)
                        instanceof Ast.MacroSyntaxExprArgument body)) {
            throw new IllegalArgumentException(
                    "macro '" + invocation.name()
                            + "' declaration builder requires "
                            + "Identifier, Type, Syntax<Expr>");
        }

        Set<String> occupied = occupiedDeclarationNames.computeIfAbsent(
                module, ignored -> new HashSet<>());
        if (occupied.contains(identifier.name())) {
            throw new IllegalArgumentException(
                    "declare_fnc generated name '" + identifier.name()
                            + "' collides with an existing declaration in module '"
                            + module + "'; generated overload/coherence synthesis "
                            + "is not enabled in this declaration slice");
        }

        String activeKey = sourceUnitId + "\0decl\0" + module + "\0"
                + invocation.name() + "\0" + invocation.site().line()
                + ":" + invocation.site().column();
        if (!active.add(activeKey)) {
            throw new IllegalArgumentException(
                    "macro expansion cycle at declaration '" + invocation.name()
                            + "'; runtime fallback is forbidden");
        }
        try {
            Ast.Expr expandedBody =
                    rewriteExpression(module, body.expression(), depth + 1);
            String owner = macroOwnerModule(module, invocation.name());
            MacroSyntaxProvenance.Origin origin =
                    new MacroSyntaxProvenance.Origin(
                            new MacroSyntaxProvenance.HygieneIdentity(
                                    sourceUnitId,
                                    module,
                                    owner,
                                    macro.name(),
                                    invocation.site(),
                                    expansionOrdinal),
                            macro.definitionSite(),
                            MacroSyntaxProvenance.EmissionKind.DECLARE_FUNCTION);

            // Provenance traversal is quota-checked and publishes only after a
            // complete walk. The declaration object is not exposed until both
            // body expansion and provenance succeed.
            MacroSyntaxProvenance.recordTree(expandedBody, origin);
            Ast.FunctionDecl generated = new Ast.FunctionDecl(
                    identifier.name(),
                    Ast.Visibility.PRIVATE,
                    false,
                    List.of(),
                    List.of(),
                    returnType.type(),
                    List.of(),
                    List.of(new Ast.ReturnStmt(expandedBody)));
            MacroDeclarationProvenance.record(
                    generated,
                    origin,
                    new MacroDeclarationProvenance.ExposedName(
                            identifier.name(),
                            identifier.site(),
                            "declare_fnc"));
            occupied.add(identifier.name());
            return generated;
        } finally {
            active.remove(activeKey);
        }
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
        if (importedMacros.containsKey(name)
                && !isSyntaxExprPassthrough(macro)
                && !isConstLiteralQuote(macro)) {
            throw new IllegalArgumentException(
                    "imported macro '" + name
                            + "' is outside M1's bounded identity/constant quote slice; "
                            + "compile-time invocation fails closed");
        }
        return macro;
    }

    private String macroOwnerModule(String callerModule, String invocationName) {
        LinkedMacroImports.Binding imported = importedMacros.get(invocationName);
        if (imported != null) return imported.canonicalOwnerIdentity();
        int separator = invocationName.lastIndexOf('.');
        return separator >= 0
                ? invocationName.substring(0, separator)
                : callerModule;
    }

    private Ast.Expr expandExpressionInvocation(
            String module,
            Ast.MacroInvocationExpr invocation,
            int depth) {
        int expansionOrdinal = nextExpansionOrdinal(depth);

        Ast.MacroDecl macro = resolve(module, invocation.name());
        boolean syntaxIdentity = isSyntaxExprPassthrough(macro);
        boolean constQuote = isConstLiteralQuote(macro);
        boolean templateQuote = isSyntaxExprTemplateQuote(macro);
        boolean hygienicIdentifier = isIdentifierIntrinsic(macro, "hygienic");
        boolean checkedFunctionCapture = isIdentifierIntrinsic(macro, "capture_fnc");
        if (!syntaxIdentity && !constQuote && !templateQuote
                && !hygienicIdentifier && !checkedFunctionCapture) {
            throw unsupported(
                    invocation.name(),
                    "this invocation requires an evaluator feature not enabled "
                            + "by the first Syntax<Expr> evaluator slice");
        }

        Ast.Expr emitted;
        if (syntaxIdentity) {
            if (invocation.arguments().size() != 1
                    || !(invocation.arguments().getFirst()
                    instanceof Ast.MacroSyntaxExprArgument quoted)) {
                throw new IllegalArgumentException(
                        "macro '" + invocation.name() + "' requires one Syntax<Expr>");
            }
            emitted = quoted.expression();
        } else if (constQuote) {
            if (invocation.arguments().size() != 1
                    || !(invocation.arguments().getFirst()
                    instanceof Ast.MacroConstArgument constant)) {
                throw new IllegalArgumentException(
                        "macro '" + invocation.name() + "' requires one Const<T>");
            }
            try {
                emitted = CompileTimeConstEvaluator.evaluate(
                        constant.expression(),
                        macro.parameters().getFirst().type().constType()).expression();
            } catch (CompileTimeConstEvaluator.Failure failure) {
                throw unsupported(
                        invocation.name(),
                        "Const<T> quotation failed deterministic evaluation: "
                                + failure.getMessage());
            }
        } else if (hygienicIdentifier || checkedFunctionCapture) {
            if (invocation.arguments().size() != 1
                    || !(invocation.arguments().getFirst()
                    instanceof Ast.MacroIdentifierArgument identifier)) {
                throw new IllegalArgumentException(
                        "macro '" + invocation.name() + "' requires one Identifier");
            }
            String owner = macroOwnerModule(module, invocation.name());
            MacroSyntaxProvenance.HygieneIdentity hygiene =
                    new MacroSyntaxProvenance.HygieneIdentity(
                            sourceUnitId,
                            module,
                            owner,
                            macro.name(),
                            invocation.site(),
                            expansionOrdinal);
            if (hygienicIdentifier) {
                emitted = MacroIdentifierHygiene.generated(
                        hygiene, identifier.name(), 0);
            } else {
                List<Ast.FunctionDecl> captured =
                        resolveCapturedFunctions(module, owner, identifier);
                emitted = MacroIdentifierHygiene.capturedFunction(
                        hygiene, identifier, captured);
            }
        } else {
            if (invocation.arguments().size() != macro.parameters().size()) {
                throw new IllegalArgumentException(
                        "macro '" + invocation.name() + "' argument count changed after planning");
            }
            Map<String, Ast.Expr> syntaxBindings = new java.util.LinkedHashMap<>();
            for (int i = 0; i < macro.parameters().size(); i++) {
                Ast.MacroParam parameter = macro.parameters().get(i);
                Ast.MacroArgument argument = invocation.arguments().get(i);
                if (!(argument instanceof Ast.MacroSyntaxExprArgument syntax)) {
                    throw new IllegalArgumentException(
                            "macro '" + invocation.name()
                                    + "' template parameter '" + parameter.name()
                                    + "' requires Syntax<Expr>");
                }
                syntaxBindings.put(parameter.name(), syntax.expression());
            }
            Ast.Expr template = quoteTemplate(macro.body().getFirst());
            if (template == null) {
                throw new IllegalStateException("validated macro template lost quote body");
            }
            // A duplicated splice is safe only for a syntactically trivial
            // caller expression. General expressions could re-run effects,
            // move a value twice or duplicate actor-state access.
            Map<String, Integer> uses = new HashMap<>();
            Set<String> names = syntaxBindings.keySet();
            if (!safeTemplateExpression(template, names, uses)) {
                throw unsupported(invocation.name(), "template quotation is not hygienic");
            }
            for (Map.Entry<String, Ast.Expr> entry : syntaxBindings.entrySet()) {
                int count = uses.getOrDefault(entry.getKey(), 0);
                if (count == 0) {
                    throw unsupported(invocation.name(),
                            "syntax parameter '" + entry.getKey()
                                    + "' would be discarded before semantic checking");
                }
                if (count > 1 && !(entry.getValue() instanceof Ast.LiteralExpr)) {
                    throw unsupported(invocation.name(),
                            "repeated unquote of nontrivial syntax parameter '"
                                    + entry.getKey()
                                    + "' may duplicate effects or ownership transfers");
                }
            }
            // Shared scalar macro profile: Java, LLVM and .NET must reject
            // provable signed-i64 overflow before emitting the two-splice
            // template. Ordinary runtime addition can have separate checked
            // semantics, but macro expansion must not silently amplify a
            // closed literal into an overflowing expression.
            if (template instanceof Ast.BinaryExpr addition
                    && "+".equals(addition.operator())
                    && addition.left() instanceof Ast.CallExpr left
                    && addition.right() instanceof Ast.CallExpr right
                    && isDirectUnquote(left) && isDirectUnquote(right)
                    && left.arguments().getFirst() instanceof Ast.NameExpr first
                    && right.arguments().getFirst() instanceof Ast.NameExpr second
                    && first.name().equals(second.name())
                    && syntaxBindings.get(first.name()) instanceof Ast.LiteralExpr literal
                    && literal.value() instanceof Long signedI64) {
                try {
                    Math.addExact(signedI64, signedI64);
                } catch (ArithmeticException overflow) {
                    throw unsupported(invocation.name(),
                            "MACRO_CONST_OVERFLOW: repeated signed-i64 literal addition overflows");
                }
            }
            emitted = instantiateTemplate(template, syntaxBindings);
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
            // Emits deterministic compiler-owned AST or caller-owned syntax.
            // Newly generated identifiers are compiler-unspellable and exact-marked;
            // caller binding access exists only through checked capture_fnc.
            // No generated source strings, ambient authority or runtime evaluation.
            Ast.Expr expanded = rewriteExpression(module, emitted, depth + 1);
            MacroSyntaxProvenance.Origin origin =
                    new MacroSyntaxProvenance.Origin(
                            new MacroSyntaxProvenance.HygieneIdentity(
                                    sourceUnitId,
                                    module,
                                    macroOwnerModule(module, invocation.name()),
                                    macro.name(),
                                    invocation.site(),
                                    expansionOrdinal),
                            macro.definitionSite(),
                            constQuote
                                    ? MacroSyntaxProvenance.EmissionKind.CONST_QUOTE
                                    : templateQuote
                                            ? MacroSyntaxProvenance.EmissionKind.TEMPLATE_QUOTE
                                            : hygienicIdentifier
                                                    ? MacroSyntaxProvenance.EmissionKind.HYGIENIC_IDENTIFIER
                                                    : checkedFunctionCapture
                                                            ? MacroSyntaxProvenance.EmissionKind.CHECKED_CAPTURE
                                                            : MacroSyntaxProvenance.EmissionKind.CALL_SITE_SPLICE);
            // Publish provenance only after the complete subtree expansion has
            // succeeded. Failed/partial expansions leave no visible metadata.
            MacroSyntaxProvenance.recordTree(expanded, origin);
            return expanded;
        } finally {
            active.remove(activeKey);
        }
    }

    private void enterRewriteTraversal() {
        if (++rewriteTraversalNodes > MAX_REWRITE_TRAVERSAL_NODES) {
            throw new IllegalArgumentException(
                    "macro rewrite traversal node budget exceeds "
                            + MAX_REWRITE_TRAVERSAL_NODES
                            + "; runtime fallback is forbidden");
        }
        if (++rewriteTraversalDepth > MAX_REWRITE_TRAVERSAL_DEPTH) {
            rewriteTraversalDepth--;
            throw new IllegalArgumentException(
                    "macro rewrite traversal depth exceeds "
                            + MAX_REWRITE_TRAVERSAL_DEPTH
                            + "; runtime fallback is forbidden");
        }
    }

    private void exitRewriteTraversal() {
        if (--rewriteTraversalDepth < 0) {
            rewriteTraversalDepth = 0;
            throw new IllegalStateException("macro rewrite traversal depth underflow");
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
        enterRewriteTraversal();
        try {
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
    
        } finally {
            exitRewriteTraversal();
        }
    }

    private Ast.Expr rewriteExpression(
            String module, Ast.Expr expression, int depth) {
        enterRewriteTraversal();
        try {
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
            MacroIdentifierHygiene.Identity identity =
                    MacroIdentifierHygiene.lookup(e);
            if (identity instanceof MacroIdentifierHygiene.CapturedFunction) {
                // Checked capture is already a fully lowered, authority-bound
                // module/function reference. Rebuilding either the MemberExpr
                // or its receiver would discard identity-bound admission.
                return e;
            }
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
    
        } finally {
            exitRewriteTraversal();
        }
    }
}
