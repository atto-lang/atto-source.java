package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deliberately non-executable singleton semantic slice.
 *
 * <p>Only closed, primitive, deeply immutable constant fields are admitted.
 * Imported aliases, methods, application-domain publication and all native
 * lowering remain unavailable. An immutable snapshot is not a live shared
 * mutable actor capability. Do not use this result as a guest runtime proof.</p>
 */
public final class SingletonCheckOnly {
    private SingletonCheckOnly() { }

    public static List<Ast.SingletonDecl> check(Ast.Program program) {
        if (!program.imports().isEmpty()) {
            throw new IllegalArgumentException(
                    "ORES-SINGLETON-CHECKONLY: linked imports and aliases are not yet proven");
        }
        List<Ast.SingletonDecl> accepted = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.SingletonDecl singleton)) {
                    throw new IllegalArgumentException(
                            "ORES-SINGLETON-CHECKONLY: mixed executable declarations are not admitted");
                }
                String id = (program.namespace() == null ? "" : program.namespace() + "/")
                        + module.name() + "/" + singleton.name();
                if (!ids.add(id)) {
                    throw at("ORES-SINGLETON-DUPLICATE", singleton.line(), singleton.column(),
                            "duplicate declaration identity " + id);
                }
                checkFields(singleton);
                accepted.add(singleton);
            }
        }
        return List.copyOf(accepted);
    }

    private static void checkFields(Ast.SingletonDecl singleton) {
        // Parser quotas and duplicate checks do not protect callers that create ASTs
        // directly (including annotation expansion and future compiler passes).
        if (singleton.fields().size() > 64) {
            throw at("ORES-SINGLETON-FIELD-BUDGET", singleton.line(), singleton.column(),
                    "singleton field budget exceeded");
        }
        Set<String> fieldNames = new HashSet<>();
        for (Ast.SingletonField positioned : singleton.fields()) {
            Ast.FieldDecl field = positioned.declaration();
            if (!fieldNames.add(field.name())) {
                throw at("ORES-SINGLETON-DUPLICATE", positioned.line(), positioned.column(),
                        "duplicate singleton field '" + field.name() + "'");
            }
            int line = positioned.line();
            int column = positioned.column();
            if (field.bindingKind() != Ast.BindingKind.CONST || field.mutableReferent()
                    || !field.annotations().isEmpty()) {
                throw at("ORES-SINGLETON-MUTABLE-ALIAS", line, column,
                        "singleton field '" + field.name()
                        + "' must be an immutable const without borrowed/mutable authority");
            }
            if (field.type() == null || !isClosedPrimitive(field.type())) {
                throw at("ORES-SINGLETON-MUTABLE-ALIAS", line, column,
                        "unproved transitive field type '" + field.name()
                        + "' cannot be published in a singleton");
            }
            if (containsSelf(field.initializer(), singleton.name())) {
                throw at("ORES-SINGLETON-SELF-ESCAPE", line, column,
                        "singleton receiver escapes before complete publication");
            }
            if (!(field.initializer() instanceof Ast.LiteralExpr literal)
                    || !literalMatches(field.type().name(), literal.value())) {
                throw at("ORES-SINGLETON-INIT-EFFECT", line, column,
                        "initializer must be a closed, correctly typed primitive literal");
            }
        }
    }

    private static boolean isClosedPrimitive(Ast.TypeRef type) {
        if (!type.arguments().isEmpty() || type.inferArguments()) return false;
        return switch (type.name()) {
            case "int", "i64", "long", "bool", "boolean", "string", "float", "double" -> true;
            default -> false;
        };
    }

    private static boolean literalMatches(String typeName, Object value) {
        if (value == null) return false;
        return switch (typeName) {
            case "int", "i64", "long" -> value instanceof Long;
            case "float", "double" -> (value instanceof Double d && Double.isFinite(d))
                    || value instanceof Long;
            case "bool", "boolean" -> value instanceof Boolean;
            case "string" -> value instanceof String;
            default -> false;
        };
    }

    /**
     * Find explicit receiver uses even when nested beneath calls, member
     * selectors or expressions. Other effects/captures fail independently:
     * only a primitive LiteralExpr passes checkFields at this stage.
     */
    private static boolean containsSelf(Ast.Expr expr, String declaredName) {
        if (expr == null) return false;
        if (expr instanceof Ast.NameExpr name) {
            return "self".equals(name.name()) || declaredName.equals(name.name());
        }
        if (expr instanceof Ast.MemberExpr member) {
            return containsSelf(member.receiver(), declaredName);
        }
        if (expr instanceof Ast.IndexExpr index) {
            return containsSelf(index.receiver(), declaredName)
                    || containsSelf(index.index(), declaredName);
        }
        if (expr instanceof Ast.CallExpr call) {
            return containsSelf(call.callee(), declaredName)
                    || call.arguments().stream().anyMatch(v -> containsSelf(v, declaredName));
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return containsSelf(binary.left(), declaredName)
                    || containsSelf(binary.right(), declaredName);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return containsSelf(unary.operand(), declaredName);
        }
        if (expr instanceof Ast.AssignExpr assign) {
            return containsSelf(assign.target(), declaredName)
                    || containsSelf(assign.value(), declaredName);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return containsSelf(conditional.condition(), declaredName)
                    || containsSelf(conditional.whenTrue(), declaredName)
                    || containsSelf(conditional.whenFalse(), declaredName);
        }
        if (expr instanceof Ast.LambdaExpr lambda && lambda.expressionBody() != null) {
            return containsSelf(lambda.expressionBody(), declaredName);
        }
        if (expr instanceof Ast.ListExpr list) {
            return list.elements().stream().anyMatch(v -> containsSelf(v, declaredName));
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return tuple.elements().stream().anyMatch(v -> containsSelf(v, declaredName));
        }
        if (expr instanceof Ast.ObjectExpr object) {
            return object.fields().stream().anyMatch(
                    field -> containsSelf(field.value(), declaredName));
        }
        return false;
    }

    private static IllegalArgumentException at(String code, int line, int column, String detail) {
        return new IllegalArgumentException(code + " at " + line + ":" + column + ": " + detail);
    }
}
