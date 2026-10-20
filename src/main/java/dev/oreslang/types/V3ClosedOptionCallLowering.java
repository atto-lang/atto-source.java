package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A source-closed Option<T> adaptation slice, not a general v3 call executor.
 *
 * The surrounding compiler must independently prove the selected local
 * declaration, absence of aliases/shadowing, ownership, imports and effects.
 * It may be used only by the explicitly opt-in single-module normalizer
 * whose only declarations are ordinary synchronous functions. A nominal
 * Option identity is not inferred from this helper's string names alone.
 *
 * Admitted supplied expressions: immutable int/bool/string literals, or
 * the built-in None value in a proven Option-typed argument slot. In a call
 * involving Option types, no user expressions or custom Some constructors
 * are accepted. Synthesized Some/None expressions are checked again by the
 * ordinary type and ownership checker.
 */
public final class V3ClosedOptionCallLowering {
    private V3ClosedOptionCallLowering() { }

    public static Ast.CallExpr lower(Ast.FunctionDecl selected, Ast.StagedCallExpr original) {
        Objects.requireNonNull(selected, "selected declaration");
        Objects.requireNonNull(original, "staged call");
        if (!(original.callee() instanceof Ast.NameExpr name)
                || !selected.name().equals(name.name())
                || original.typeArgumentsPresent()
                || selected.kind() != Ast.CallableKind.FNC
                || selected.async() || selected.suspends()
                || selected.generator() || selected.nonLexical()
                || selected.structural() || selected.trapped()
                || selected.actorKind() != Ast.ActorKind.NONE
                || !selected.genericParameters().isEmpty()) {
            throw new IllegalArgumentException("closed Option lowering requires a plain direct fnc");
        }
        for (Ast.Annotation annotation : selected.annotations()) {
            if (!annotation.name().equals("DefaultArgVals")) {
                throw new IllegalArgumentException(
                        "unknown callable annotation cannot be erased by Option lowering");
            }
        }

        List<V3DefaultArgValsPolicy.FormalPreview> signature =
                V3DefaultArgValsPolicy.validateDeclaration(
                        selected.parameters(), selected.annotations());
        var shapes = OptionalCallShapeBinder.stagedV3ParameterShapes(
                selected.parameters(), selected.annotations());
        var plan = OptionalCallShapeBinder.bindParsed(shapes, original.arguments());
        var preview = V3CallLoweringPreview.plan(
                selected.parameters(), selected.annotations(), original.arguments(), plan);
        List<Ast.Expr> supplied = preview.unevaluatedExpressionsInSourceOrder();
        List<Ast.Expr> fullArity = new ArrayList<>(signature.size());
        for (int i = 0; i < signature.size(); i++) {
            var formal = signature.get(i);
            var slot = preview.formalOrderSlots().get(i);
            Ast.TypeRef type = formal.bodyType();
            Ast.TypeRef payload = formal.optionTyped()
                    && type.arguments().size() == 1 ? type.arguments().getFirst() : type;
            if (!scalar(payload) || type.isBorrow() || type.containsStagedV3Option()
                    || (formal.optionTyped()
                        && (!type.name().equals("Option")
                            || type.arguments().size() != 1 || type.inferArguments()))) {
                throw new IllegalArgumentException(
                        "closed Option lowerer only admits intrinsic primitive Option<T> shape");
            }
            Ast.Expr value = switch (slot.action()) {
                case CHECK_SUPPLIED -> {
                    int dense = slot.evaluatedSourceIndex();
                    if (dense < 0 || dense >= supplied.size()) {
                        throw new IllegalArgumentException("checked supplied source is missing");
                    }
                    Ast.Expr arg = supplied.get(dense);
                    // Explicit None is SUPPLIED, and must override a default.
                    if (formal.optionTyped() && arg instanceof Ast.NameExpr nameExpr
                            && nameExpr.name().equals("None")) {
                        yield new Ast.NameExpr("None");
                    }
                    if (!matches(arg, payload)) {
                        throw new IllegalArgumentException(
                                "Option argument requires immutable exact payload literal or supplied None");
                    }
                    yield formal.optionTyped() ? some(arg) : arg;
                }
                case DEFAULT_SCALAR -> defaultLiteral(slot.closedDefault(), payload);
                case DEFAULT_SOME -> {
                    if (!formal.optionTyped()) {
                        throw new IllegalArgumentException("Some default requires Option<T>");
                    }
                    yield some(defaultLiteral(slot.closedDefault(), payload));
                }
                case DEFAULT_NONE, OMITTED_OPTION_NONE -> {
                    if (!formal.optionTyped()) {
                        throw new IllegalArgumentException("None omission requires Option<T>");
                    }
                    yield new Ast.NameExpr("None");
                }
            };
            fullArity.add(value);
        }
        return new Ast.CallExpr(new Ast.NameExpr(selected.name()), List.copyOf(fullArity));
    }

    private static Ast.CallExpr some(Ast.Expr payload) {
        return new Ast.CallExpr(new Ast.NameExpr("Some"), List.of(payload));
    }

    private static Ast.Expr defaultLiteral(
            DefaultArgumentPolicy.Literal literal, Ast.TypeRef expected) {
        if (literal == null) {
            throw new IllegalArgumentException("missing independently checked default");
        }
        Object value = switch (literal.kind()) {
            case INT, BOOL, STRING -> literal.value();
            case NONE -> throw new IllegalArgumentException("None cannot be a scalar default");
        };
        Ast.LiteralExpr expr = new Ast.LiteralExpr(value);
        if (!matches(expr, expected)) {
            throw new IllegalArgumentException("default literal has wrong primitive payload type");
        }
        return expr;
    }

    private static boolean scalar(Ast.TypeRef ref) {
        return ref.arguments().isEmpty() && !ref.inferArguments()
                && !ref.isBorrow() && !ref.containsStagedV3Option()
                && (ref.name().equals("int") || ref.name().equals("bool")
                    || ref.name().equals("string"));
    }

    private static boolean matches(Ast.Expr expression, Ast.TypeRef expected) {
        if (!(expression instanceof Ast.LiteralExpr literal)) return false;
        return switch (expected.name()) {
            case "int" -> literal.value() instanceof Long;
            case "bool" -> literal.value() instanceof Boolean;
            case "string" -> literal.value() instanceof String;
            default -> false;
        };
    }
}
