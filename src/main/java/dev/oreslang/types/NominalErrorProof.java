package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Conservative nominal ancestry proof for declared exceptional-effect payloads.
 *
 * <p>The root must be a declaration obtained from independently authenticated
 * std/error library metadata. This class does NOT authenticate that metadata:
 * callers must never designate a class parsed from untrusted guest source as
 * the trusted Error root. Identity, not a shared name or structural fields,
 * establishes the proof. This verifier is not an exceptional-effect runtime
 * admission gate.
 */
public final class NominalErrorProof {
    private static final int MAX_ANCESTRY_NODES = 128;

    private NominalErrorProof() { }

    public static void requireNominalError(
            Ast.TypeRef payload,
            Ast.ClassDecl trustedRoot,
            Function<Ast.TypeRef, Ast.ClassDecl> resolveClass) {
        Objects.requireNonNull(payload, "effect payload");
        Objects.requireNonNull(trustedRoot, "trusted std/error Error root");
        Objects.requireNonNull(resolveClass, "nominal class resolver");
        if (!trustedRoot.name().equals("Error")
                || !trustedRoot.genericParameters().isEmpty()
                || trustedRoot.actorKind() != Ast.ActorKind.NONE) {
            throw new IllegalArgumentException("invalid trusted std/error.Error root metadata");
        }
        ensureConcrete(payload, "effect payload");
        Ast.ClassDecl candidate = resolveClass.apply(payload);
        if (candidate == null) {
            throw new IllegalArgumentException("checked effect payload must resolve to a nominal class: " + payload);
        }
        Set<Ast.ClassDecl> ancestry = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!descendsFromRoot(candidate, trustedRoot, resolveClass, ancestry, new int[]{0})) {
            throw new IllegalArgumentException(
                    "checked effect payload is not a proven std/error.Error descendant: " + payload);
        }
    }

    public static void requireNominalEffects(
            Ast.EffectSpec effects,
            Ast.ClassDecl trustedRoot,
            Function<Ast.TypeRef, Ast.ClassDecl> resolveClass) {
        Objects.requireNonNull(effects, "checked effects");
        requireCategory(effects.throwsTypes(), "throws", trustedRoot, resolveClass);
        requireCategory(effects.raisesTypes(), "raises", trustedRoot, resolveClass);
        requireCategory(effects.panicsTypes(), "panics", trustedRoot, resolveClass);
    }

    private static void requireCategory(
            List<Ast.TypeRef> payloads,
            String category,
            Ast.ClassDecl root,
            Function<Ast.TypeRef, Ast.ClassDecl> resolver) {
        for (Ast.TypeRef payload : payloads) {
            try {
                requireNominalError(payload, root, resolver);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("invalid checked " + category
                        + " payload: " + invalid.getMessage(), invalid);
            }
        }
    }

    private static void ensureConcrete(Ast.TypeRef reference, String label) {
        // Generic error instantiation requires separate substitution, ownership,
        // wire-serialization and payload bounds. Reject rather than guess.
        if (reference.inferArguments() || !reference.arguments().isEmpty()
                || reference.name().startsWith("$")) {
            throw new IllegalArgumentException(label
                    + " must be a concrete, non-generic nominal class: " + reference);
        }
    }

    private static boolean descendsFromRoot(
            Ast.ClassDecl candidate,
            Ast.ClassDecl trustedRoot,
            Function<Ast.TypeRef, Ast.ClassDecl> resolveClass,
            Set<Ast.ClassDecl> ancestry,
            int[] visited) {
        if (++visited[0] > MAX_ANCESTRY_NODES) {
            throw new IllegalArgumentException("checked error ancestry exceeds proof budget");
        }
        if (candidate == trustedRoot) return true;
        if (candidate.actorKind() != Ast.ActorKind.NONE
                || !candidate.genericParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "checked error ancestry cannot contain actors or generic classes");
        }
        if (!ancestry.add(candidate)) {
            throw new IllegalArgumentException("cycle in checked error nominal ancestry");
        }
        try {
            for (Ast.TypeRef parentRef : candidate.parents()) {
                if (parentRef.name().equals("Object") && parentRef.arguments().isEmpty()) {
                    continue; // implicit ordinary object ancestry proves no Error relationship
                }
                ensureConcrete(parentRef, "checked error superclass");
                Ast.ClassDecl parent = resolveClass.apply(parentRef);
                if (parent == null) {
                    throw new IllegalArgumentException(
                            "unresolved checked error superclass: " + parentRef);
                }
                if (descendsFromRoot(parent, trustedRoot, resolveClass, ancestry, visited)) {
                    return true;
                }
            }
            return false;
        } finally {
            ancestry.remove(candidate);
        }
    }
}
