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
    private static final int MAX_DECLARED_EFFECT_TYPES = 64;

    private NominalErrorProof() { }

    public static void requireNominalError(
            Ast.TypeRef payload,
            Ast.ClassDecl trustedRoot,
            Function<Ast.TypeRef, Ast.ClassDecl> resolveClass) {
        Objects.requireNonNull(payload, "effect payload");
        validateRootMetadata(trustedRoot);
        Objects.requireNonNull(resolveClass, "nominal class resolver");
        ensureConcrete(payload, "effect payload");
        Ast.ClassDecl candidate = resolveClass.apply(payload);
        if (candidate == null) {
            throw new IllegalArgumentException("checked effect payload must resolve to a nominal class: " + payload);
        }
        // Identity-resolving import aliases and qualified names need separate
        // trusted module ownership proof. A suffix check is NOT sufficient:
        // attacker.Error must not resolve to a trusted class named Error.
        requireExactNominalReference(payload, candidate, "effect payload");
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
        validateRootMetadata(trustedRoot);
        Objects.requireNonNull(resolveClass, "nominal class resolver");
        // Validate the total row before walking any ancestry graph. This caps
        // up-front work from compiler-generated or linked metadata as well as
        // guest parser input. A type repeated across categories still counts
        // once per category because those may-effects are independent.
        long declared = (long) effects.throwsTypes().size()
                + effects.raisesTypes().size() + effects.panicsTypes().size();
        if (declared > MAX_DECLARED_EFFECT_TYPES) {
            throw new IllegalArgumentException("checked effect declaration exceeds payload budget");
        }
        requireCategory(effects.throwsTypes(), "throws", trustedRoot, resolveClass);
        requireCategory(effects.raisesTypes(), "raises", trustedRoot, resolveClass);
        requireCategory(effects.panicsTypes(), "panics", trustedRoot, resolveClass);
    }

    private static void validateRootMetadata(Ast.ClassDecl root) {
        Objects.requireNonNull(root, "trusted std/error Error root");
        if (!root.name().equals("Error")
                || !root.genericParameters().isEmpty()
                || root.actorKind() != Ast.ActorKind.NONE
                || root.parents().stream().anyMatch(parent ->
                        !parent.name().equals("Object")
                                || !parent.arguments().isEmpty()
                                || parent.inferArguments())) {
            throw new IllegalArgumentException("invalid trusted std/error.Error root metadata");
        }
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

    // Synchronize these always-builtin source spellings with TypeChecker.resolve.
    // They cannot become nominal Error descendants by a caller-supplied
    // class resolver. Types that can resolve to explicit user classes (Box,
    // Arc, etc.) deliberately are not blacklisted here.
    private static final Set<String> NON_NOMINAL_TYPE_NAMES = Set.of(
            "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
            "int", "uint", "bigint", "f32", "f64", "float",
            "decimal", "complex64", "complex128", "complex",
            "bool", "boolean", "Bool", "string", "String", "Symbol",
            "void", "null", "Object", "obj", "arr", "self",
            "DynamicStruct", "ActorId", "ActorKind", "ActorStream",
            "StreamOperator", "ActorMail", "ActorOutput", "ActorOutputStream",
            "Fnc", "Function", "Option", "Result", "Future", "Task",
            "Generator", "AsyncGenerator", "Iterator", "AsyncIterator", "IteratorResult",
            "Mutex", "MutexGuard", "SharedMutex", "Channel",
            "List", "Array", "Map", "Set", "Any", "Unknown");

    private static void ensureConcrete(Ast.TypeRef reference, String label) {
        // Generic error instantiation requires separate substitution, ownership,
        // wire-serialization and payload bounds. Reject rather than guess.
        if (reference.inferArguments() || !reference.arguments().isEmpty()
                || reference.name().isBlank() || reference.name().startsWith("$")
                || NON_NOMINAL_TYPE_NAMES.contains(reference.name())) {
            throw new IllegalArgumentException(label
                    + " must be a concrete, non-generic nominal class: " + reference);
        }
    }

    private static void requireExactNominalReference(
            Ast.TypeRef reference, Ast.ClassDecl resolved, String label) {
        // The verifier receives an untrusted resolver function and must not
        // accept arbitrary mismatched class objects returned for a spelling.
        // Until an authenticated qualified-name resolver is wired, deliberately
        // reject source-qualified aliases rather than accepting a suffix match.
        if (!reference.name().equals(resolved.name())
                || reference.name().indexOf('.') >= 0
                || reference.name().indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    label + " name does not match resolved class identity: " + reference
                            + " (qualified names require authenticated module ownership)");
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
            boolean foundTrustedRoot = false;
            // Explore every parent: a second ambiguous/unresolved/cyclic
            // ancestry must not become invisible just because the first
            // path happened to reach Error.
            for (Ast.TypeRef parentRef : candidate.parents()) {
                if (parentRef.name().equals("Object") && parentRef.arguments().isEmpty()
                        && !parentRef.inferArguments()) {
                    continue; // only a concrete ordinary Object parent is ignorable
                }
                ensureConcrete(parentRef, "checked error superclass");
                Ast.ClassDecl parent = resolveClass.apply(parentRef);
                if (parent == null) {
                    throw new IllegalArgumentException(
                            "unresolved checked error superclass: " + parentRef);
                }
                requireExactNominalReference(parentRef, parent, "checked error superclass");
                foundTrustedRoot = descendsFromRoot(
                        parent, trustedRoot, resolveClass, ancestry, visited) || foundTrustedRoot;
            }
            return foundTrustedRoot;
        } finally {
            ancestry.remove(candidate);
        }
    }
}
