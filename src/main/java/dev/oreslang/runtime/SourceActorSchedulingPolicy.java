package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import java.util.Objects;

/**
 * Compiler-authored scheduling policy from the immutable, VM-published AST.
 * ActorCodeExecutor metadata is only a consistency check, never authority.
 */
public final class SourceActorSchedulingPolicy {
    private SourceActorSchedulingPolicy() { }

    public static boolean isSerialActor(Ast.Program program, String actorName) {
        Objects.requireNonNull(program, "linked source program");
        if (actorName == null || actorName.isBlank()) {
            throw new IllegalArgumentException("source actor type name is required");
        }
        Ast.ClassDecl match = null;
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ClassDecl klass)
                        || !klass.name().equals(actorName)) continue;
                if (match != null) {
                    throw new SecurityException(
                            "ambiguous actor declaration in linked code image: " + actorName);
                }
                match = klass;
            }
        }
        if (match == null || match.actorKind() == Ast.ActorKind.NONE) {
            throw new SecurityException(
                    "source actor is not declared in linked code image: " + actorName);
        }
        return match.serial();
    }

    /** Validate advisory executor metadata without allowing it to grant or
     * remove scheduling constraints established by the linked AST. */
    public static boolean verifyClaim(
            Ast.Program program, String actorName, boolean executorSerialClaim) {
        boolean serial = isSerialActor(program, actorName);
        if (serial != executorSerialClaim) {
            throw new SecurityException(
                    "source actor executor scheduling metadata disagrees with linked code image: "
                            + actorName);
        }
        return serial;
    }
}
