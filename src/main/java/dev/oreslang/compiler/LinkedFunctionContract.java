package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Strict v1 contract for cross-unit function imports that may participate
 * in VerifiedSccAdmission. No unresolved, nominal, effectful, borrowed, or
 * user-annotated signature may enter the primitive-only semantic subset.
 *
 * Every provider function is still checked in its own compilation unit.
 * This shape check alone is never proof of correct function bodies.
 */
public final class LinkedFunctionContract {
    private LinkedFunctionContract() { }

    public static void requirePrimitiveContract(Ast.FunctionDecl fn) {
        if (fn == null || fn.visibility() != Ast.Visibility.PUBLIC
                || fn.kind() != Ast.CallableKind.FNC
                || fn.actorKind() != Ast.ActorKind.NONE
                || fn.async() || fn.suspends() || fn.generator()
                || fn.structural() || fn.nonLexical() || fn.trapped()
                || !fn.genericParameters().isEmpty()
                || !fn.annotations().isEmpty()
                || !simplePrimitive(fn.returnType(), true)) {
            throw new IllegalArgumentException(
                    "linked function is not a public, effect-free primitive-signature callable");
        }
        Set<String> names = new HashSet<>();
        for (Ast.Param param : fn.parameters()) {
            if (param == null || param.name() == null || param.name().isBlank()
                    || !names.add(param.name())
                    || param.structural() || param.mutable()
                    || !simplePrimitive(param.type(), false)) {
                throw new IllegalArgumentException(
                        "linked parameters must have distinct names and primitive immutable types");
            }
        }
    }

    private static boolean simplePrimitive(Ast.TypeRef ref, boolean allowVoid) {
        if (ref == null || ref.name() == null
                || ref.inferArguments() || !ref.arguments().isEmpty()) return false;
        return switch (ref.name()) {
            case "int", "float", "bool", "String" -> true;
            case "void" -> allowVoid;
            default -> false;
        };
    }
}
