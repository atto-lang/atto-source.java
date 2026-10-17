package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.Objects;

/**
 * Fail-closed v1 execution gate for source and programmatically built ASTs.
 *
 * <p>Annotations and parser-produced metadata are not physical execution
 * permits. Current Java/GraalVM actor scheduling admits one carrier only.
 * This check must run before compilation, linking, or executing guest code.
 */
public final class ActorThreadAdmission {
    private ActorThreadAdmission() { }

    public static void requireExclusiveBackend(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) {
                    requireExclusiveBackend(klass);
                }
            }
        }
    }

    public static void requireExclusiveBackend(Ast.ClassDecl klass) {
        Objects.requireNonNull(klass, "actor/class declaration");
        Ast.ActorThreadPolicy limits = Objects.requireNonNull(klass.actorThreads(), "actor thread policy");
        if (klass.actorKind() == Ast.ActorKind.NONE) {
            if (!limits.equals(Ast.ActorThreadPolicy.defaults())) {
                throw new IllegalArgumentException("non-actor class cannot request actor threads");
            }
            return;
        }
        if (limits.max() > 1) {
            throw new IllegalArgumentException("actor '" + klass.name()
                    + "' requests max=" + limits.max()
                    + "; parallel actor threads require scheduler admission, "
                    + "state effect checking and verified backend lowering");
        }
        if (limits.min() != 1) {
            throw new IllegalArgumentException("actor '" + klass.name()
                    + "' requires min=1 with the exclusive actor scheduler");
        }
    }
}
