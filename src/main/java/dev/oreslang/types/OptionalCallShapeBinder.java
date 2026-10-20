package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A source-order-preserving, type-agnostic binding plan for named and optional
 * arguments. This is a pre-integration compiler building block: parser, AST,
 * type/ownership checking, and both runtimes must be integrated before the
 * surface syntax can be enabled.
 *
 * <p>Argument expressions MUST be evaluated exactly once in the original
 * source order before being placed into parameter order according to this plan.
 * A -1 source index means the parameter was omitted and must be lowered to
 * a typed Option.None, not null, undefined or a default argument expression.
 */
public final class OptionalCallShapeBinder {
    private OptionalCallShapeBinder() { }

    /** The call-site label is null for a positional argument. */
    public record Argument(String label, boolean omission) {
        public Argument {
            if (label != null && label.isBlank()) {
                throw new IllegalArgumentException("named argument label cannot be blank");
            }
        }
        public Argument(String label) { this(label, false); }
        public static Argument positional() { return new Argument(null); }
        public static Argument named(String name) {
            return new Argument(Objects.requireNonNull(name, "name"));
        }
        /** An explicit omission is a call item with NO evaluated expression. */
        public static Argument positionalOmission() { return new Argument(null, true); }
        /** The parser maps bare '_' and 'omit' call items to the same omission. */
        public static Argument underscore() { return positionalOmission(); }
        public static Argument namedOmission(String name) {
            return new Argument(Objects.requireNonNull(name, "name"), true);
        }
        /** Named 'label: _' also normalizes to exactly the same omission shape. */
        public static Argument namedUnderscore(String name) { return namedOmission(name); }
        /**
         * Call-argument grammar bridge: only an entire standalone '_' or
         * 'omit' token can denote omission. The lexer/parser must establish
         * argument-item boundaries; this helper NEVER parses an expression.
         * Treating 'None' or 'void' as tokens here would erase an essential
         * type/evaluation distinction.
         */
        public static Argument omittedCallItemToken(String token, String label) {
            Objects.requireNonNull(token, "token");
            if (!"_".equals(token) && !"omit".equals(token)) {
                throw new IllegalArgumentException(
                        "only a standalone '_' or 'omit' may denote an omitted call item");
            }
            return new Argument(label, true);
        }
    }

    /**
     * Canonical bridge from the parser-owned CallArgument AST to the pure
     * shape binder. Explicit omissions have no source expression. Spread
     * lowering is deliberately gated pending typed collection/ownership proof.
     */
    public static List<Argument> parsedArgumentShapes(List<Ast.CallArgument> sourceItems) {
        Objects.requireNonNull(sourceItems, "sourceItems");
        List<Argument> shapes = new ArrayList<>(sourceItems.size());
        for (Ast.CallArgument item : sourceItems) {
            Objects.requireNonNull(item, "call item");
            if (item.spread()) {
                throw new IllegalArgumentException(
                        "spread call items require separately checked typed lowering");
            }
            shapes.add(item.omitted()
                    ? item.label() == null ? Argument.positionalOmission()
                                           : Argument.namedOmission(item.label())
                    : item.label() == null ? Argument.positional()
                                           : Argument.named(item.label()));
        }
        return List.copyOf(shapes);
    }

    /**
     * Bind a complete parsed call-site list, retaining the source AST item
     * indexes. A caller must not use plain values after building this plan:
     * the plan and the source AST are validated as a single snapshot.
     */
    public static Plan bindParsed(
            List<Parameter> parameters, List<Ast.CallArgument> callItems) {
        return bind(parameters, parsedArgumentShapes(callItems));
    }

    /**
     * Minimal executable admission: only pure primitive LITERAL named calls
     * to direct local function/routine names. The compiler must still resolve
     * that name to its unique normal callable declaration in each pass.
     * Other staged AST forms remain explicitly disabled.
     */
    public static boolean isDirectScalarLiteralNamedCall(Ast.StagedCallExpr call) {
        if (call == null || call.typeArgumentsPresent()
                || !(call.callee() instanceof Ast.NameExpr)
                || call.arguments().isEmpty()) return false;
        boolean hasNamed = false;
        for (Ast.CallArgument item : call.arguments()) {
            if (item.omitted() || item.spread()
                    || !(item.value() instanceof Ast.LiteralExpr literal)) return false;
            Object value = literal.value();
            if (!(value instanceof Long || value instanceof Double
                    || value instanceof Boolean || value instanceof String)) return false;
            if (item.label() != null) hasNamed = true;
        }
        return hasNamed;
    }

    /**
     * The primitive-literal-only first integration step. Never reorder
     * effectful guest expressions: they require a source-order temporary
     * schedule, typed Option adaptation, and ownership checks.
     *
     * This method doesn't resolve the target; the caller must independently
     * prove its local declaration identity, visibility and shadowing.
     */
    public static Ast.CallExpr lowerDirectScalarLiteralNamedCall(
            Ast.StagedCallExpr call, Ast.FunctionDecl resolvedTarget) {
        if (!isDirectScalarLiteralNamedCall(call)
                || resolvedTarget == null
                || !(call.callee() instanceof Ast.NameExpr targetName)
                || !targetName.name().equals(resolvedTarget.name())
                || resolvedTarget.async() || resolvedTarget.suspends()
                || resolvedTarget.generator() || resolvedTarget.trapped()
                || resolvedTarget.structural() || resolvedTarget.nonLexical()
                || resolvedTarget.actorKind() != Ast.ActorKind.NONE
                || !resolvedTarget.genericParameters().isEmpty()
                || !resolvedTarget.annotations().isEmpty()) {
            throw new IllegalArgumentException(
                    "named scalar-literal calls require a unique, ordinary, local non-generic function/routine");
        }
        for (Ast.Param param : resolvedTarget.parameters()) {
            if (param.optional() || param.hasDefault() || param.structural()
                    || param.mutable() || param.type().isBorrow()
                    || param.type().inferArguments()) {
                throw new IllegalArgumentException(
                        "optional/default/borrow/mutable named call ABI is not yet enabled");
            }
        }
        List<Parameter> shapes = formalParameterShapes(resolvedTarget.parameters());
        Plan plan = bindParsed(shapes, call.arguments());
        if (plan.variadicParameterIndex() != -1
                || plan.suppliedArgumentCount() != resolvedTarget.parameters().size()
                || !plan.explicitlyOmittedParameterIndices().isEmpty()) {
            throw new IllegalArgumentException(
                    "named scalar-literal call requires all formal slots supplied");
        }
        List<Ast.Expr> source = evaluatedExpressionsInSourceOrder(shapes, call.arguments(), plan);
        List<Ast.Expr> ordered = new ArrayList<>(resolvedTarget.parameters().size());
        for (int formal = 0; formal < resolvedTarget.parameters().size(); formal++) {
            int index = plan.sourceIndex(formal);
            if (index < 0) throw new IllegalArgumentException("missing required named argument");
            Ast.Param declared = resolvedTarget.parameters().get(formal);
            Ast.TypeRef type = declared.type();
            Ast.Expr expression = source.get(index);
            if (!(expression instanceof Ast.LiteralExpr literal)
                    || !type.arguments().isEmpty() || type.inferArguments()
                    || !literalMatchesDirectScalarFormal(literal.value(), type.name())) {
                throw new IllegalArgumentException(
                        "named literal requires an exact primitive scalar formal type: "
                                + declared.name());
            }
            ordered.add(expression);
        }
        return new Ast.CallExpr(call.callee(), List.copyOf(ordered));
    }

    /**
     * This deliberately small direct-AST/runtime admission must not trust
     * previous static typechecking. Only these closed scalar types can be
     * reordered without expression effects or ownership coercions.
     * User-defined nominal types, aliases and Option<T> are NOT eligible.
     */
    private static boolean literalMatchesDirectScalarFormal(Object value, String formalType) {
        return switch (formalType) {
            case "int" -> value instanceof Long;
            case "float" -> value instanceof Double;
            case "bool" -> value instanceof Boolean;
            case "string" -> value instanceof String;
            default -> false;
        };
    }

    /**
     * An untrusted/reconstructed Plan can swap the claimed formal for two
     * omissions while preserving all dense source-index invariants.
     * Rebinding against the actual source items detects such an exchange.
     * All lowering consumers must check the exact selected declaration and
     * this correspondence before evaluating/moving any expression.
     */
    public static void verifyPlanAgainstSource(
            List<Parameter> parameters, List<Ast.CallArgument> callItems, Plan plan) {
        Objects.requireNonNull(plan, "plan");
        Plan expected = bindParsed(parameters, callItems);
        if (!expected.equals(plan)) {
            throw new IllegalArgumentException(
                    "call plan does not match original argument items and formal bindings");
        }
    }

    /**
     * All compiler entry points must reject staged T? formal declarations
     * until Option<T> ABI and ownership lowering is implemented. Check even
     * unused declarations before any optimization or signature collection.
     */
    public static void requireNoUnloweredOptionalFormals(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        for (Ast.ModuleDecl module : program.modules()) {
            requireNoOmittableAnnotations(module.annotations(), "module annotation");
            for (Ast.TypeRef contract : module.contracts()) {
                requireNoOmittableTypeOutsideFormal(contract, "module contract");
            }
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl function) {
                    requireNoUnloweredOptionalFormals(function.parameters());
                    requireNoOmittableAnnotations(function.annotations(), "function annotation");
                    requireNoOmittableTypeOutsideFormal(function.returnType(), "function return");
                    requireNoUnloweredOptionalFormalsInBody(function.body());
                    for (Ast.GenericParameter generic : function.genericSpecs()) {
                        requireNoOmittableTypeOutsideFormal(generic.defaultType(), "generic default");
                    }
                } else if (decl instanceof Ast.ClassDecl klass) {
                    requireNoUnloweredOptionalFormals(klass);
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    for (Ast.TypeRef parent : iface.parents()) {
                        requireNoOmittableTypeOutsideFormal(parent, "interface parent");
                    }
                    for (Ast.InterfaceMember member : iface.members()) {
                        if (member instanceof Ast.InterfaceFunctionDecl function) {
                            requireNoUnloweredOptionalFormals(function.parameters());
                            requireNoOmittableTypeOutsideFormal(function.returnType(), "interface return");
                        } else if (member instanceof Ast.InterfaceFieldDecl field) {
                            requireNoOmittableTypeOutsideFormal(field.type(), "interface field");
                        }
                    }
                } else if (decl instanceof Ast.FieldDecl field) {
                    requireNoOmittableTypeOutsideFormal(field.type(), "module field");
                    requireNoOmittableAnnotations(field.annotations(), "module field annotation");
                    requireNoUnloweredOptionalFormalsInExpr(field.initializer());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    requireNoOmittableTypeOutsideFormal(alias.target(), "type alias");
                }
            }
        }
    }

    /** Reject optional formals from linked class signatures as well. */
    public static void requireNoUnloweredOptionalFormals(Ast.ClassDecl klass) {
        Objects.requireNonNull(klass, "class declaration");
        for (Ast.TypeRef parent : klass.parents()) {
            requireNoOmittableTypeOutsideFormal(parent, "class parent");
        }
        for (Ast.TypeRef iface : klass.interfaces()) {
            requireNoOmittableTypeOutsideFormal(iface, "implemented interface");
        }
        for (Ast.FieldDecl field : klass.fields()) {
            requireNoOmittableTypeOutsideFormal(field.type(), "class field");
            requireNoOmittableAnnotations(field.annotations(), "class field annotation");
            requireNoUnloweredOptionalFormalsInExpr(field.initializer());
        }
        if (klass.constructor() != null) {
            requireNoUnloweredOptionalFormals(klass.constructor().parameters());
            requireNoOmittableAnnotations(klass.constructor().annotations(), "constructor annotation");
            requireNoUnloweredOptionalFormalsInBody(klass.constructor().body());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            requireNoUnloweredOptionalFormals(method.parameters());
            requireNoOmittableTypeOutsideFormal(method.returnType(), "method return");
            requireNoOmittableTypeOutsideFormal(method.explicitReceiverType(), "method receiver");
            requireNoOmittableAnnotations(method.annotations(), "method annotation");
            requireNoUnloweredOptionalFormalsInBody(method.body());
        }
    }

    private static void requireNoOmittableAnnotations(List<Ast.Annotation> annotations, String where) {
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("DefaultArgVals") || !annotation.namedDefaults().isEmpty()) {
                throw new IllegalArgumentException(
                        "@DefaultArgVals in " + where + " requires v3 checked presence/Option/ABI lowering");
            }
            for (Ast.TypeRef type : annotation.arguments()) {
                requireNoOmittableTypeOutsideFormal(type, where);
            }
        }
    }

    /**
     * All entry points (including direct Truffle root construction) must inspect
     * lambda formals in nested, unused, deferred, callback and branch bodies.
     * A declaration-only scan leaves unlowered T? closures reachable without
     * type/ownership admission. Exhaustive-on-known-AST traversal is intentional:
     * new AST kinds must be reviewed here before interpreter admission.
     */
    private static void requireNoUnloweredOptionalFormalsInBody(List<Ast.Stmt> body) {
        for (Ast.Stmt statement : body) requireNoUnloweredOptionalFormalsInStmt(statement);
    }

    private static void requireNoUnloweredOptionalFormalsInPattern(Ast.Pattern pattern) {
        if (pattern instanceof Ast.TypePattern p) {
            requireNoOmittableTypeOutsideFormal(p.type(), "match type pattern");
        } else if (pattern instanceof Ast.StructuralPattern p) {
            requireNoOmittableTypeOutsideFormal(p.target(), "match structural pattern");
        } else if (pattern instanceof Ast.TuplePattern p) {
            for (Ast.Pattern item : p.elements()) requireNoUnloweredOptionalFormalsInPattern(item);
        } else if (pattern instanceof Ast.ConstructorPattern p) {
            for (Ast.Pattern item : p.arguments()) requireNoUnloweredOptionalFormalsInPattern(item);
        } else if (!(pattern instanceof Ast.WildcardPattern
                || pattern instanceof Ast.LiteralPattern
                || pattern instanceof Ast.BindingPattern)) {
            throw new IllegalArgumentException("unreviewed match pattern in optional-formal admission");
        }
    }

    private static void requireNoUnloweredOptionalFormalsInStmt(Ast.Stmt stmt) {
        Objects.requireNonNull(stmt, "statement");
        if (stmt instanceof Ast.BindingStmt s) {
            requireNoOmittableTypeOutsideFormal(s.declaredType(), "local binding");
            requireNoUnloweredOptionalFormalsInExpr(s.initializer());
        } else if (stmt instanceof Ast.DestructureStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.initializer());
        } else if (stmt instanceof Ast.ReturnStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.value());
        } else if (stmt instanceof Ast.YieldStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.value());
        } else if (stmt instanceof Ast.ExprStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.expression());
        } else if (stmt instanceof Ast.DeferStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.expression());
        } else if (stmt instanceof Ast.BlockStmt s) {
            requireNoUnloweredOptionalFormalsInBody(s.body());
        } else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch branch : s.branches()) {
                requireNoUnloweredOptionalFormalsInExpr(branch.condition());
                requireNoUnloweredOptionalFormalsInBody(branch.body());
            }
            requireNoUnloweredOptionalFormalsInBody(s.elseBody());
        } else if (stmt instanceof Ast.MatchStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.subject());
            for (Ast.MatchArm arm : s.arms()) {
                requireNoUnloweredOptionalFormalsInPattern(arm.pattern());
                requireNoUnloweredOptionalFormalsInExpr(arm.guard());
                requireNoUnloweredOptionalFormalsInBody(arm.body());
            }
        } else if (stmt instanceof Ast.SwitchStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.subject());
            for (Ast.SwitchCase branch : s.cases()) {
                for (Ast.Expr value : branch.constants()) requireNoUnloweredOptionalFormalsInExpr(value);
                requireNoUnloweredOptionalFormalsInBody(branch.body());
            }
            requireNoUnloweredOptionalFormalsInBody(s.defaultBody());
        } else if (stmt instanceof Ast.TryStmt s) {
            requireNoUnloweredOptionalFormalsInBody(s.body());
            requireNoUnloweredOptionalFormalsInBody(s.catchBody());
            requireNoUnloweredOptionalFormalsInBody(s.finallyBody());
        } else if (stmt instanceof Ast.ForOfStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.iterable());
            requireNoUnloweredOptionalFormalsInBody(s.body());
        } else if (stmt instanceof Ast.ForOfDestructureStmt s) {
            requireNoUnloweredOptionalFormalsInExpr(s.iterable());
            requireNoUnloweredOptionalFormalsInBody(s.body());
        } else if (stmt instanceof Ast.ForStmt s) {
            if (s.initializer() != null) requireNoUnloweredOptionalFormalsInStmt(s.initializer());
            requireNoUnloweredOptionalFormalsInExpr(s.condition());
            requireNoUnloweredOptionalFormalsInExpr(s.update());
            requireNoUnloweredOptionalFormalsInBody(s.body());
        } else if (stmt instanceof Ast.LoopStmt s) {
            requireNoUnloweredOptionalFormalsInBody(s.body());
        } else if (stmt instanceof Ast.SelectStmt s) {
            for (Ast.SelectArm arm : s.arms()) {
                requireNoOmittableTypeOutsideFormal(arm.bindingType(), "select binding type");
                requireNoUnloweredOptionalFormalsInExpr(arm.channel());
                requireNoUnloweredOptionalFormalsInExpr(arm.value());
                requireNoUnloweredOptionalFormalsInBody(arm.body());
            }
        } else if (!(stmt instanceof Ast.BreakStmt || stmt instanceof Ast.ContinueStmt)) {
            throw new IllegalArgumentException("unreviewed statement kind in optional-formal admission");
        }
    }

    private static void requireNoUnloweredOptionalFormalsInExpr(Ast.Expr expr) {
        if (expr == null) return;
        if (expr instanceof Ast.LambdaExpr e) {
            requireNoUnloweredOptionalFormals(e.parameters());
            requireNoUnloweredOptionalFormalsInExpr(e.expressionBody());
            if (e.blockBody() != null) requireNoUnloweredOptionalFormalsInBody(e.blockBody());
        } else if (expr instanceof Ast.BinaryExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.left());
            requireNoUnloweredOptionalFormalsInExpr(e.right());
        } else if (expr instanceof Ast.UnaryExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.operand());
        } else if (expr instanceof Ast.AssignExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.target());
            requireNoUnloweredOptionalFormalsInExpr(e.value());
        } else if (expr instanceof Ast.ConditionalExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.condition());
            requireNoUnloweredOptionalFormalsInExpr(e.whenTrue());
            requireNoUnloweredOptionalFormalsInExpr(e.whenFalse());
        } else if (expr instanceof Ast.MatchExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.subject());
            requireNoOmittableTypeOutsideFormal(e.resultType(), "match result");
            for (Ast.MatchValueArm arm : e.arms()) {
                requireNoUnloweredOptionalFormalsInPattern(arm.pattern());
                requireNoUnloweredOptionalFormalsInExpr(arm.guard());
                requireNoUnloweredOptionalFormalsInExpr(arm.value());
            }
        } else if (expr instanceof Ast.TypeTestExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.value());
            requireNoOmittableTypeOutsideFormal(e.targetType(), "type test");
        } else if (expr instanceof Ast.PatternTestExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.value());
            requireNoUnloweredOptionalFormalsInPattern(e.pattern());
        } else if (expr instanceof Ast.CastExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.value());
            requireNoOmittableTypeOutsideFormal(e.targetType(), "cast type");
        } else if (expr instanceof Ast.SpreadExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.expression());
        } else if (expr instanceof Ast.CallExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.callee());
            for (Ast.TypeRef type : e.typeArguments()) requireNoOmittableTypeOutsideFormal(type, "call type argument");
            for (Ast.Expr arg : e.arguments()) requireNoUnloweredOptionalFormalsInExpr(arg);
        } else if (expr instanceof Ast.StagedCallExpr e) {
            if (!isDirectScalarLiteralNamedCall(e)) {
                // Reject all unlowered optional, non-literal or dynamic calls
                // before executing ANY initializer or guest side effect.
                throw new IllegalArgumentException(
                        "staged named/optional calls are not yet enabled without checked Option<T> ABI lowering");
            }
            // This narrow exception is only syntactic. Type, ownership and
            // runtime phases each independently resolve and recheck the target.
            requireNoUnloweredOptionalFormalsInExpr(e.callee());
            for (Ast.CallArgument item : e.arguments()) {
                requireNoUnloweredOptionalFormalsInExpr(item.value());
            }
        } else if (expr instanceof Ast.StagedNewExpr) {
            throw new IllegalArgumentException(
                    "staged named/optional construction is not yet enabled without checked Option<T> ABI lowering");
        } else if (expr instanceof Ast.RuntimeCallExpr e) {
            for (Ast.Expr arg : e.arguments()) requireNoUnloweredOptionalFormalsInExpr(arg);
        } else if (expr instanceof Ast.MemberExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.receiver());
        } else if (expr instanceof Ast.IndexExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.receiver());
            requireNoUnloweredOptionalFormalsInExpr(e.index());
        } else if (expr instanceof Ast.NewExpr e) {
            requireNoOmittableTypeOutsideFormal(e.type(), "constructed type");
            for (Ast.Expr arg : e.arguments()) requireNoUnloweredOptionalFormalsInExpr(arg);
        } else if (expr instanceof Ast.AwaitExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.expression());
        } else if (expr instanceof Ast.TrapExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.expression());
        } else if (expr instanceof Ast.ChannelOpExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.channel());
            requireNoUnloweredOptionalFormalsInExpr(e.value());
            requireNoUnloweredOptionalFormalsInBody(e.callbackBody());
        } else if (expr instanceof Ast.DynamicSelectExpr e) {
            requireNoUnloweredOptionalFormalsInExpr(e.cases());
        } else if (expr instanceof Ast.ListExpr e) {
            for (Ast.Expr arg : e.elements()) requireNoUnloweredOptionalFormalsInExpr(arg);
        } else if (expr instanceof Ast.TupleExpr e) {
            for (Ast.Expr arg : e.elements()) requireNoUnloweredOptionalFormalsInExpr(arg);
        } else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) requireNoUnloweredOptionalFormalsInExpr(field.value());
            for (Ast.StructFieldSpec field : e.declaredFields())
                requireNoOmittableTypeOutsideFormal(field.type(), "struct field");
        } else if (!(expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr)) {
            throw new IllegalArgumentException("unreviewed expression kind in optional-formal admission");
        }
    }

    private static void requireNoOmittableTypeOutsideFormal(Ast.TypeRef type, String location) {
        if (type != null && type.containsStagedV3Option()) {
            throw new IllegalArgumentException(
                    "staged v3 type '?' in " + location
                    + " requires verified Option identity and ABI lowering");
        }
        if (type != null && type.isOmittableFormal()) {
            throw new IllegalArgumentException(
                    "legacy T? is only valid on formal argument slots, not " + location);
        }
    }

    public static void requireNoUnloweredOptionalFormals(List<Ast.Param> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        for (Ast.Param formal : parameters) {
            Objects.requireNonNull(formal, "formal");
            if (formal.type().containsStagedV3Option()) {
                throw new IllegalArgumentException(
                        "staged v3 Option type '?' for '" + formal.name()
                        + "' requires nominal resolution/ownership/ABI lowering");
            }
            if (formal.argumentOmittable()) {
                throw new IllegalArgumentException(
                        "v3 parameter-name '?' for '" + formal.name()
                        + "' requires checked default/presence/ABI lowering (not enabled)");
            }
            if (formal.hasDefault()) {
                throw new IllegalArgumentException(
                        "@Default annotation on formal '" + formal.name()
                        + "' requires checked static-constant, presence and ABI lowering (not enabled)");
            }
            if (formal.optional()) {
                throw new IllegalArgumentException(
                        "T? optional formal '" + formal.name()
                        + "' requires checked Option<T> ABI and ownership lowering (not yet enabled)");
            }
        }
    }

    /**
     * V3 compile-time preview only. Do not reuse the legacy optional(T?) binder
     * classification: type optionality and argument omission are distinct.
     */
    public static List<Parameter> stagedV3ParameterShapes(
            List<Ast.Param> formals, List<Ast.Annotation> annotations) {
        List<V3DefaultArgValsPolicy.FormalPreview> verified =
                V3DefaultArgValsPolicy.validateDeclaration(formals, annotations);
        List<Parameter> shapes = new ArrayList<>(verified.size());
        for (V3DefaultArgValsPolicy.FormalPreview formal : verified) {
            shapes.add(formal.omittable() ? Parameter.optional(formal.name())
                                        : Parameter.required(formal.name()));
        }
        checkedParameters(shapes);
        return List.copyOf(shapes);
    }

    /**
     * V3 signature selection is structurally identical once a caller has
     * explicitly chosen the v3 language mode and validated all declaration
     * defaults. This is still not runtime/ownership execution authority.
     */
    public record StagedV3Signature(
            List<Ast.Param> parameters, List<Ast.Annotation> annotations) {
        public StagedV3Signature {
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
        }
    }

    public static Selection selectStagedV3(
            List<StagedV3Signature> signatures, List<Ast.CallArgument> sourceItems) {
        Objects.requireNonNull(signatures, "v3 signatures");
        List<List<Parameter>> candidates = new ArrayList<>();
        for (StagedV3Signature signature : signatures) {
            candidates.add(stagedV3ParameterShapes(signature.parameters(), signature.annotations()));
        }
        Selection selected = bindUnique(candidates, parsedArgumentShapes(sourceItems));
        verifyPlanAgainstSource(candidates.get(selected.signatureIndex()),
                sourceItems, selected.plan());
        return selected;
    }

    /** The reference shape is an omittable source T, never a required Option<T>. */
    public static List<Parameter> declaredParameterShapes(List<Ast.Param> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        List<Parameter> result = new ArrayList<>(parameters.size());
        for (Ast.Param formal : parameters) {
            Objects.requireNonNull(formal, "formal");
            if (formal.argumentOmittable() || formal.type().containsStagedV3Option()) {
                throw new IllegalArgumentException(
                        "legacy binder cannot reinterpret staged v3 type/presence metadata");
            }
            result.add(formal.optional() ? Parameter.optional(formal.name())
                                         : Parameter.required(formal.name()));
        }
        return List.copyOf(result);
    }

    /**
     * Compiler-lowering handoff: dense source expressions in the exact lexical
     * order in which a selected call must evaluate them. Rebind the checked
     * signature and source AST before exposing any expression: a valid-looking
     * dense Plan is not proof that omissions target the correct formals.
     *
     * This returns AST data ONLY. It never evaluates expressions or authorizes
     * runtime invocation, borrow moves, or Option<T> ABI lowering.
     */
    public static List<Ast.Expr> evaluatedExpressionsInSourceOrder(
            List<Parameter> parameters, List<Ast.CallArgument> callItems, Plan selectedPlan) {
        Objects.requireNonNull(callItems, "callItems");
        verifyPlanAgainstSource(parameters, callItems, selectedPlan);
        List<Ast.Expr> evaluated = new ArrayList<>(selectedPlan.suppliedArgumentCount());
        for (int callItemIndex : selectedPlan.callItemIndexByEvaluatedSource()) {
            Ast.CallArgument item = callItems.get(callItemIndex);
            if (item.omitted() || item.spread() || item.value() == null) {
                throw new IllegalArgumentException(
                        "selected call source index references a non-evaluable call item");
            }
            evaluated.add(item.value());
        }
        return List.copyOf(evaluated);
    }

    /**
     * Stateless compile-time selection for a family of parsed declaration
     * signatures. This does not choose by argument type, label alone, or
     * runtime value. Overlapping admitted arities fail closed.
     *
     * The caller must first establish the lexical/qualified declaration family,
     * accessibility, actor isolation and any explicit type arguments.
     */
    public static Selection selectParsed(
            List<List<Ast.Param>> declarationSignatures,
            List<Ast.CallArgument> sourceItems) {
        Objects.requireNonNull(declarationSignatures, "declarationSignatures");
        List<List<Parameter>> shapes = new ArrayList<>(declarationSignatures.size());
        for (List<Ast.Param> signature : declarationSignatures) {
            shapes.add(declaredParameterShapes(signature));
        }
        Selection selected = bindUnique(shapes, parsedArgumentShapes(sourceItems));
        verifyPlanAgainstSource(shapes.get(selected.signatureIndex()),
                sourceItems, selected.plan());
        return selected;
    }

    /**
     * Convert checked declaration metadata, not call-site guesswork, into
     * binder formals. A required Option<T> is not an omittable T? formal.
     * The variadic declaration grammar must be audited independently.
     */
    public static List<Parameter> formalParameterShapes(List<Ast.Param> formals) {
        Objects.requireNonNull(formals, "formals");
        List<Parameter> result = new ArrayList<>(formals.size());
        for (Ast.Param formal : formals) {
            Objects.requireNonNull(formal, "formal");
            if (formal.argumentOmittable() || formal.type().containsStagedV3Option()) {
                throw new IllegalArgumentException(
                        "legacy formal binder cannot reinterpret staged v3 type/presence metadata");
            }
            if (formal.optional() && (formal.structural() || formal.mutable())) {
                throw new IllegalArgumentException(
                        "omittable formal cannot be structural or mutable");
            }
            result.add(formal.optional()
                    ? Parameter.optional(formal.name())
                    : Parameter.required(formal.name()));
        }
        checkedParameters(result);
        return List.copyOf(result);
    }

    /**
     * V2 staged shape bridge. Consume type-checker-VERIFIED intrinsic Option
     * classifications rather than guessing from a TypeRef's simple name.
     * This preserves legacy required-Option binding until a versioned module
     * signature explicitly selects IMPLICIT_OPTION_OMISSION. No executable
     * Option injection or source-call admission is authorized here.
     */
    public static List<Parameter> provenOptionParameterShapes(
            List<OptionFormalPresencePolicy.Formal> verifiedFormals,
            OptionFormalPresencePolicy.LanguageMode mode) {
        Objects.requireNonNull(verifiedFormals, "verified formals");
        Objects.requireNonNull(mode, "language mode");
        List<Parameter> result = new ArrayList<>(verifiedFormals.size());
        for (OptionFormalPresencePolicy.Formal formal : verifiedFormals) {
            Objects.requireNonNull(formal, "verified formal");
            boolean optional = OptionFormalPresencePolicy.omittable(formal, mode);
            result.add(formal.variadic() ? Parameter.variadic(formal.name())
                    : optional ? Parameter.optional(formal.name())
                               : Parameter.required(formal.name()));
        }
        checkedParameters(result);
        return List.copyOf(result);
    }

    /**
     * Stage v2 default-annotation-aware callable shape binding. The compiler
     * supplies already checked constant defaults and nominal Option identity.
     * A default on a plain T formal makes that slot omittable without changing
     * its body-visible T type, whereas an Option<T> formal is omittable in v2
     * even without a default. Never pass this plan to an executable backend
     * until the default constant and Option ABI are lowered consistently.
     */
    public static List<Parameter> defaultedParameterShapes(
            List<DefaultArgumentPolicy.Formal> verifiedFormals,
            OptionFormalPresencePolicy.LanguageMode mode) {
        Objects.requireNonNull(verifiedFormals, "verified formals");
        Objects.requireNonNull(mode, "language mode");
        List<Parameter> result = new ArrayList<>(verifiedFormals.size());
        for (DefaultArgumentPolicy.Formal formal : verifiedFormals) {
            Objects.requireNonNull(formal, "verified formal");
            boolean optional = DefaultArgumentPolicy.omittable(formal, mode);
            result.add(formal.signature().variadic()
                    ? Parameter.variadic(formal.signature().name())
                    : optional ? Parameter.optional(formal.signature().name())
                               : Parameter.required(formal.signature().name()));
        }
        checkedParameters(result);
        return List.copyOf(result);
    }

    /** Structural binder proof only; executable Option<T> lowering is gated. */
    public static Plan bindParsedFormals(
            List<Ast.Param> formals, List<Ast.CallArgument> callItems) {
        return bindParsed(formalParameterShapes(formals), callItems);
    }

    public record Parameter(String name, boolean optional, boolean variadic) {
        public Parameter {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("parameter name cannot be blank");
            }
            if (optional && variadic) {
                throw new IllegalArgumentException("variadic parameter cannot be optional");
            }
        }
        public Parameter(String name, boolean optional) { this(name, optional, false); }
        public static Parameter required(String name) { return new Parameter(name, false); }
        public static Parameter optional(String name) { return new Parameter(name, true); }
        /** A single final rest parameter; omission is an empty collection, NEVER None. */
        public static Parameter variadic(String name) { return new Parameter(name, false, true); }
    }

    /** Exact source-to-formal mapping for an explicit no-value call item. */
    public record OmittedCallItem(int callItemIndex, int parameterIndex) { }

    /**
     * Formal-to-source index plan. -1 marks an omitted OPTIONAL scalar formal;
     * the final variadic formal is represented separately by a list of source
     * indexes and is not omitted even when its list is empty.
     */
    public record Plan(List<Integer> sourceIndexByParameter,
                       int variadicParameterIndex,
                       List<Integer> variadicSourceIndices,
                       List<Integer> explicitlyOmittedParameterIndices,
                       List<Integer> callItemIndexByEvaluatedSource) {
        public Plan(List<Integer> sourceIndexByParameter) {
            this(sourceIndexByParameter, -1, List.of(), List.of(),
                    identityItemIndexes(sourceIndexByParameter, List.of()));
        }
        public Plan(List<Integer> sourceIndexByParameter,
                    int variadicParameterIndex,
                    List<Integer> variadicSourceIndices) {
            this(sourceIndexByParameter, variadicParameterIndex, variadicSourceIndices,
                    List.of(), identityItemIndexes(sourceIndexByParameter, variadicSourceIndices));
        }
        public Plan(List<Integer> sourceIndexByParameter,
                    int variadicParameterIndex,
                    List<Integer> variadicSourceIndices,
                    List<Integer> explicitlyOmittedParameterIndices) {
            this(sourceIndexByParameter, variadicParameterIndex, variadicSourceIndices,
                    rejectMissingOrigins(explicitlyOmittedParameterIndices),
                    identityItemIndexes(sourceIndexByParameter, variadicSourceIndices));
        }

        private static List<Integer> rejectMissingOrigins(List<Integer> omissions) {
            Objects.requireNonNull(omissions, "omissions");
            if (!omissions.isEmpty()) {
                throw new IllegalArgumentException(
                        "explicitly omitted call items require original-source indexes");
            }
            return omissions;
        }

        private static List<Integer> identityItemIndexes(List<Integer> fixed, List<Integer> rest) {
            int count = rest.size();
            for (Integer source : fixed) if (source != null && source >= 0) count++;
            List<Integer> identity = new ArrayList<>(count);
            for (int i = 0; i < count; i++) identity.add(i);
            return identity;
        }

        public Plan {
            sourceIndexByParameter = List.copyOf(sourceIndexByParameter);
            variadicSourceIndices = List.copyOf(variadicSourceIndices);
            explicitlyOmittedParameterIndices = List.copyOf(explicitlyOmittedParameterIndices);
            callItemIndexByEvaluatedSource = List.copyOf(callItemIndexByEvaluatedSource);
            boolean[] claimedOmission = new boolean[sourceIndexByParameter.size()];
            for (int parameterIndex : explicitlyOmittedParameterIndices) {
                if (parameterIndex < 0 || parameterIndex >= sourceIndexByParameter.size()
                        || parameterIndex == variadicParameterIndex
                        || claimedOmission[parameterIndex]
                        || sourceIndexByParameter.get(parameterIndex) != -1) {
                    throw new IllegalArgumentException("invalid explicit omission plan slot");
                }
                claimedOmission[parameterIndex] = true;
            }
            if (variadicParameterIndex != -1
                    && variadicParameterIndex != sourceIndexByParameter.size() - 1) {
                throw new IllegalArgumentException("variadic plan slot must be final");
            }
            if (variadicParameterIndex == -1 && !variadicSourceIndices.isEmpty()) {
                throw new IllegalArgumentException("rest sources without variadic parameter");
            }
            if (variadicParameterIndex >= 0
                    && sourceIndexByParameter.get(variadicParameterIndex) != -1) {
                throw new IllegalArgumentException("rest slot cannot have a scalar source");
            }
            int supplied = variadicSourceIndices.size();
            for (int i = 0; i < sourceIndexByParameter.size(); i++) {
                int source = sourceIndexByParameter.get(i);
                if (source < -1) throw new IllegalArgumentException("invalid call-plan source index");
                if (source >= 0) supplied++;
            }
            if (callItemIndexByEvaluatedSource.size() != supplied) {
                throw new IllegalArgumentException(
                        "evaluated-source to original-call-item index map has incorrect size");
            }
            int rawCallItems = Math.addExact(
                    supplied, explicitlyOmittedParameterIndices.size());
            int priorCallItemIndex = -1;
            for (int callItemIndex : callItemIndexByEvaluatedSource) {
                if (callItemIndex <= priorCallItemIndex || callItemIndex >= rawCallItems) {
                    throw new IllegalArgumentException(
                            "evaluated call-item indexes must be strictly increasing");
                }
                priorCallItemIndex = callItemIndex;
            }
            // The rest tail is a sequence, never a permutation. A public
            // Plan can be reconstructed without going through bind(); validate
            // lexical order here as well as in the source-AST rebind.
            // All fixed positional slots must precede the rest tail. Named
            // arguments cannot precede positional rest elements.
            int latestFixedSource = -1;
            for (int i = 0; i < sourceIndexByParameter.size(); i++) {
                if (i == variadicParameterIndex) continue;
                latestFixedSource = Math.max(latestFixedSource, sourceIndexByParameter.get(i));
            }
            int previousRestSource = latestFixedSource;
            for (int restSource : variadicSourceIndices) {
                if (restSource <= previousRestSource) {
                    throw new IllegalArgumentException(
                            "variadic call-plan sources must follow fixed values in lexical order");
                }
                previousRestSource = restSource;
            }
            if (!variadicSourceIndices.isEmpty()) {
                // Positional rest elements can only appear after every fixed
                // argument item. Any omitted fixed slot must have an explicit
                // source-level '_' / 'omit' item before the first rest element.
                for (int i = 0; i < variadicParameterIndex; i++) {
                    if (sourceIndexByParameter.get(i) == -1 && !claimedOmission[i]) {
                        throw new IllegalArgumentException(
                                "rest elements cannot follow an implicitly omitted fixed parameter");
                    }
                }
                int firstRestSource = variadicSourceIndices.getFirst();
                int firstRestCallItem = callItemIndexByEvaluatedSource.get(firstRestSource);
                if (firstRestCallItem - firstRestSource
                        != explicitlyOmittedParameterIndices.size()) {
                    throw new IllegalArgumentException(
                            "explicit omission call items must precede the rest tail");
                }
            }
            boolean[] seen = new boolean[supplied];
            for (int i = 0; i < sourceIndexByParameter.size(); i++) {
                int source = sourceIndexByParameter.get(i);
                if (source != -1) markSeen(seen, source);
            }
            for (int source : variadicSourceIndices) {
                markSeen(seen, source);
            }
            for (boolean isSeen : seen) {
                if (!isSeen) throw new IllegalArgumentException("noncontiguous call-plan source indexes");
            }
        }

        private static void markSeen(boolean[] seen, int source) {
            if (source < 0 || source >= seen.length || seen[source]) {
                throw new IllegalArgumentException("invalid or duplicate call-plan source index");
            }
            seen[source] = true;
        }

        public int declaredArity() { return sourceIndexByParameter.size(); }
        /**
         * Original call-item index of an evaluated argument. Crucial because
         * '_' and 'omit' consume slots WITHOUT contributing an expression:
         * foo(3, _, effect()) maps evaluated source #1 to call item #2.
         */
        public int callItemIndex(int evaluatedSourceIndex) {
            return callItemIndexByEvaluatedSource.get(evaluatedSourceIndex);
        }
        public int suppliedArgumentCount() {
            int count = variadicSourceIndices.size();
            for (int source : sourceIndexByParameter) if (source >= 0) count++;
            return count;
        }
        /** Count of all syntactic argument items, including explicit omissions. */
        public int callItemCount() {
            return Math.addExact(suppliedArgumentCount(), explicitlyOmittedParameterIndices.size());
        }

        /**
         * Recover the source indexes of syntactic omit items. The original
         * call-item order is preserved even when named bindings are reordered.
         * Implicitly omitted parameters have no call item and are excluded.
         */
        public List<Integer> omittedCallItemIndices() {
            List<Integer> result = new ArrayList<>();
            int evaluated = 0;
            for (int callItem = 0; callItem < callItemCount(); callItem++) {
                if (evaluated < callItemIndexByEvaluatedSource.size()
                        && callItemIndexByEvaluatedSource.get(evaluated) == callItem) {
                    evaluated++;
                } else {
                    result.add(callItem);
                }
            }
            if (result.size() != explicitlyOmittedParameterIndices.size()) {
                throw new IllegalStateException("inconsistent omitted call-item origin plan");
            }
            return List.copyOf(result);
        }

        /**
         * Preserve the source order of explicit omissions while identifying
         * the formal they skip. In named calls, formal indexes can be reordered;
         * never infer a formal from the raw omission item's ordinal.
         */
        public List<OmittedCallItem> omittedCallItemBindings() {
            List<Integer> itemIndexes = omittedCallItemIndices();
            List<OmittedCallItem> mappings = new ArrayList<>(itemIndexes.size());
            for (int i = 0; i < itemIndexes.size(); i++) {
                mappings.add(new OmittedCallItem(
                        itemIndexes.get(i), explicitlyOmittedParameterIndices.get(i)));
            }
            return List.copyOf(mappings);
        }
        public boolean omitted(int parameterIndex) {
            return parameterIndex != variadicParameterIndex
                    && sourceIndexByParameter.get(parameterIndex) == -1;
        }
        public boolean explicitlyOmitted(int parameterIndex) {
            return explicitlyOmittedParameterIndices.contains(parameterIndex);
        }
        public int sourceIndex(int parameterIndex) {
            if (parameterIndex == variadicParameterIndex) {
                throw new IllegalArgumentException("rest parameter has multiple source indexes");
            }
            return sourceIndexByParameter.get(parameterIndex);
        }
    }

    /**
     * Typed lowering decisions for an optional scalar slot. This deliberately
     * does not perform the compiler's nominal/generic subtype check:
     * OPTION_VALUE and NONE_LITERAL must be verified as Option<T> by the
     * type checker. In particular, a void-producing expression is NOT None.
     */
    public enum SuppliedForm {
        PLAIN_VALUE, OPTION_VALUE, NONE_LITERAL, NULL_LITERAL, VOID_EXPRESSION
    }
    public enum OptionalAdaptation { WRAP_SOME, PASS_OPTION, INJECT_NONE }

    public static OptionalAdaptation optionalAdaptation(
            Parameter parameter, boolean omitted, SuppliedForm suppliedForm) {
        Objects.requireNonNull(parameter, "parameter");
        if (!parameter.optional() || parameter.variadic()) {
            throw new IllegalArgumentException("optional adaptation requires optional scalar formal");
        }
        if (omitted) {
            if (suppliedForm != null) {
                throw new IllegalArgumentException("omitted slot cannot have a supplied value");
            }
            return OptionalAdaptation.INJECT_NONE;
        }
        Objects.requireNonNull(suppliedForm, "suppliedForm");
        return switch (suppliedForm) {
            case PLAIN_VALUE -> OptionalAdaptation.WRAP_SOME;
            case OPTION_VALUE, NONE_LITERAL -> OptionalAdaptation.PASS_OPTION;
            case NULL_LITERAL -> throw new IllegalArgumentException(
                    "null is not None; use typed None for an absent optional argument");
            case VOID_EXPRESSION -> throw new IllegalArgumentException(
                    "void is not a value and cannot represent an omitted argument");
        };
    }

    /** The selected declaration index and its permutation; never a runtime overload choice. */
    public record Selection(int signatureIndex, Plan plan) {
        public Selection {
            if (signatureIndex < 0) throw new IllegalArgumentException("negative signature index");
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * Bind without executing or reordering a single expression.
     *
     * <p>Ordinary positional arguments always claim the next leading slot.
     * A middle optional slot can be omitted through a label, not by positional
     * hole shifting. Following the first named argument, positional arguments
     * are forbidden; spreads must be rejected by the frontend before this call.
     * A sole final rest parameter collects remaining positional source indexes
     * in lexical order and uses empty collection (not None) when unsupplied.
     * Passing rest by label is gated until collection and spread checking exist.
     * An explicit positional/named omit is permitted ONLY for an optional
     * fixed slot, consumes exactly one slot, and never evaluates an expression.
     * A separate plan field preserves explicit vs implicit omissions without
     * changing the callee-visible Option<T>.None representation.
     */
    public static Plan bind(List<Parameter> parameters, List<Argument> arguments) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(arguments, "arguments");
        Map<String, Integer> parameterIndex = checkedParameters(parameters);

        int[] binding = new int[parameters.size()];
        Arrays.fill(binding, -1);
        boolean[] claimed = new boolean[parameters.size()];
        List<Integer> explicitOmissions = new ArrayList<>();
        List<Integer> evaluatedCallItems = new ArrayList<>();
        int nextPositional = 0;
        // An explicit omit consumes a CALL SLOT but never evaluates a value.
        // Source indices refer to evaluated expressions, not raw call items.
        int evaluatedSourceIndex = 0;
        int restSlot = parameters.isEmpty() || !parameters.getLast().variadic()
                ? -1 : parameters.size() - 1;
        List<Integer> restSources = new ArrayList<>();
        boolean seenNamed = false;
        for (int itemIndex = 0; itemIndex < arguments.size(); itemIndex++) {
            Argument argument = Objects.requireNonNull(arguments.get(itemIndex), "argument");
            int slot;
            if (argument.label() == null) {
                if (seenNamed) {
                    throw new IllegalArgumentException("positional argument after named argument");
                }
                if (restSlot >= 0 && nextPositional >= restSlot) {
                    if (argument.omission()) {
                        throw new IllegalArgumentException("rest tail cannot contain omit");
                    }
                    restSources.add(evaluatedSourceIndex++);
                    evaluatedCallItems.add(itemIndex);
                    continue;
                }
                if (nextPositional >= parameters.size()) {
                    throw new IllegalArgumentException("too many positional arguments");
                }
                slot = nextPositional++;
            } else {
                seenNamed = true;
                Integer namedSlot = parameterIndex.get(argument.label());
                if (namedSlot == null) {
                    throw new IllegalArgumentException("unknown named argument '" + argument.label() + "'");
                }
                slot = namedSlot;
                if (slot == restSlot) {
                    throw new IllegalArgumentException(
                            "named rest argument requires an explicit collection/spread contract");
                }
            }
            if (claimed[slot]) {
                throw new IllegalArgumentException(
                        "parameter '" + parameters.get(slot).name() + "' was supplied more than once");
            }
            claimed[slot] = true;
            if (argument.omission()) {
                if (!parameters.get(slot).optional()) {
                    throw new IllegalArgumentException(
                            "required parameter '" + parameters.get(slot).name() + "' cannot be omitted");
                }
                explicitOmissions.add(slot);
            } else {
                binding[slot] = evaluatedSourceIndex++;
                evaluatedCallItems.add(itemIndex);
            }
        }

        List<Integer> permutation = new ArrayList<>(binding.length);
        for (int i = 0; i < binding.length; i++) {
            if (i != restSlot && binding[i] == -1 && !parameters.get(i).optional()) {
                throw new IllegalArgumentException(
                        "missing required argument '" + parameters.get(i).name() + "'");
            }
            permutation.add(binding[i]);
        }
        return new Plan(permutation, restSlot, restSources,
                explicitOmissions, evaluatedCallItems);
    }

    private static Map<String, Integer> checkedParameters(List<Parameter> parameters) {
        Map<String, Integer> parameterIndex = new HashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = Objects.requireNonNull(parameters.get(i), "parameter");
            if (parameter.variadic() && i != parameters.size() - 1) {
                throw new IllegalArgumentException("variadic parameter must be final");
            }
            if (parameterIndex.putIfAbsent(parameter.name(), i) != null) {
                throw new IllegalArgumentException("duplicate parameter name '" + parameter.name() + "'");
            }
        }
        return parameterIndex;
    }

    /**
     * Conservative v1 admission check for an existing callable overload domain.
     * An optional declaration accepts a supplied-argument COUNT interval
     * [number of required slots, total slots], or infinity for a rest tail.
     * Any interval intersecting
     * another declaration's interval is rejected at declaration/link time,
     * even if their label sets appear disjoint. This preserves deterministic
     * arity-only dispatch until cross-backend call-shape proofs exist.
     *
     * <p>Nonoptional/nonoptional signature collision rules (including the
     * separately staged nominal enum exception) remain with the caller.
     */
    public static void requireNonOverlappingOptionalArityIntervals(
            List<List<Parameter>> signatures) {
        Objects.requireNonNull(signatures, "signatures");
        for (List<Parameter> signature : signatures) {
            checkedParameters(Objects.requireNonNull(signature, "signature"));
        }
        for (int i = 0; i < signatures.size(); i++) {
            List<Parameter> a = signatures.get(i);
            long aRequired = a.stream().filter(p -> !p.optional() && !p.variadic()).count();
            long aMaximum = a.isEmpty() || !a.getLast().variadic() ? a.size() : Long.MAX_VALUE;
            for (int j = i + 1; j < signatures.size(); j++) {
                List<Parameter> b = signatures.get(j);
                long bRequired = b.stream().filter(p -> !p.optional() && !p.variadic()).count();
                long bMaximum = b.isEmpty() || !b.getLast().variadic() ? b.size() : Long.MAX_VALUE;
                if (aRequired == a.size() && bRequired == b.size()) {
                    continue; // ordinary exact-arity policy remains separate
                }
                if (Math.max(aRequired, bRequired) <= Math.min(aMaximum, bMaximum)) {
                    throw new IllegalArgumentException(
                            "overlapping optional supplied-arity intervals for declarations "
                                    + i + " and " + j);
                }
            }
        }
    }

    /**
     * Resolve a family structurally, by labels and required slots ONLY.
     * Full declared arity is retained for the ordinary callable ABI selector.
     * If omitted optionals make two declarations match, reject ambiguity;
     * neither argument types nor an "exact arity wins" preference may hide it.
     *
     * <p>Signature ownership, visibility, overrides and constructor-receiver
     * erasure must already be validated by the calling compiler phase.
     */
    public static Selection bindUnique(
            List<List<Parameter>> signatures,
            List<Argument> arguments) {
        Objects.requireNonNull(signatures, "signatures");
        Objects.requireNonNull(arguments, "arguments");
        requireNonOverlappingOptionalArityIntervals(signatures);
        // The binder must never accidentally use labels to dispatch two
        // ordinary signatures with the same arity. Exceptional nominal-enum
        // overloads need a separately proved, already-selected declaration,
        // not this type-agnostic family resolver.
        for (int i = 0; i < signatures.size(); i++) {
            for (int j = i + 1; j < signatures.size(); j++) {
                List<Parameter> a = signatures.get(i);
                List<Parameter> b = signatures.get(j);
                if (a.size() == b.size()
                        && a.stream().allMatch(p -> !p.optional() && !p.variadic())
                        && b.stream().allMatch(p -> !p.optional() && !p.variadic())) {
                    throw new IllegalArgumentException(
                            "same-arity overloads cannot dispatch by argument labels");
                }
            }
        }
        Selection selected = null;
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < signatures.size(); i++) {
            // Malformed declarations are errors, not silently skipped candidates.
            checkedParameters(Objects.requireNonNull(signatures.get(i), "signature"));
            try {
                Plan plan = bind(signatures.get(i), arguments);
                selected = new Selection(i, plan);
                valid.add(i);
            } catch (IllegalArgumentException ignored) {
                // The whole family is checked below. A nonmatching candidate
                // must not win solely because its parameter types differ.
            }
        }
        if (valid.size() > 1) {
            throw new IllegalArgumentException(
                    "ambiguous optional/named call shape matches declarations " + valid);
        }
        if (selected == null) {
            if (signatures.size() == 1) {
                // Preserve useful exact diagnostic for single-signature calls.
                bind(signatures.getFirst(), arguments);
            }
            throw new IllegalArgumentException(
                    "no declared callable has a compatible required/named argument shape");
        }
        return selected;
    }
}
