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

    /**
     * Reject any externally visible signature drift between the declaration
     * used to check importers and the provider's final checked declaration.
     * Function bodies intentionally do not participate: this is an *interface*
     * proof, never an optimized object-code cache key.
     */
    public static void requireStableSignature(
            Ast.FunctionDecl initiallyResolved, Ast.FunctionDecl finallyChecked) {
        requirePrimitiveContract(initiallyResolved);
        requirePrimitiveContract(finallyChecked);
        if (!Objects.equals(initiallyResolved.name(), finallyChecked.name())
                || initiallyResolved.kind() != finallyChecked.kind()
                || initiallyResolved.visibility() != finallyChecked.visibility()
                || initiallyResolved.actorKind() != finallyChecked.actorKind()
                || initiallyResolved.async() != finallyChecked.async()
                || initiallyResolved.suspends() != finallyChecked.suspends()
                || initiallyResolved.generator() != finallyChecked.generator()
                || initiallyResolved.structural() != finallyChecked.structural()
                || initiallyResolved.nonLexical() != finallyChecked.nonLexical()
                || initiallyResolved.trapped() != finallyChecked.trapped()
                || !initiallyResolved.genericParameters().equals(
                        finallyChecked.genericParameters())
                || !initiallyResolved.annotations().equals(finallyChecked.annotations())
                || !initiallyResolved.parameters().equals(finallyChecked.parameters())
                || !Objects.equals(initiallyResolved.returnType(), finallyChecked.returnType())) {
            throw new IllegalArgumentException(
                    "linked provider signature changed during SCC verification");
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
