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

    /**
     * Java adapter registration bridge. Reading an annotation does not create
     * an actor or authorize an executing carrier; this validates metadata
     * against the current backend capability before any adapter may use it.
     */
    public static Ast.ActorThreadPolicy fromJavaAnnotation(Class<?> adapterType) {
        Objects.requireNonNull(adapterType, "Java actor adapter type");
        dev.oreslang.runtime.ActorThreads annotation =
                adapterType.getAnnotation(dev.oreslang.runtime.ActorThreads.class);
        if (annotation == null) return Ast.ActorThreadPolicy.defaults();
        Ast.ActorThreadPolicy requested =
                new Ast.ActorThreadPolicy(annotation.min(), annotation.max());
        if (requested.max() > 1) {
            throw new IllegalArgumentException("Java actor adapter '"
                    + adapterType.getName()
                    + "' requests unsupported max=" + requested.max()
                    + "; physical parallel actor execution is not yet enabled");
        }
        return requested;
    }

    /**
     * Consume Java-host annotation metadata without requiring runtime reflection.
     * A native-image bridge may supply the build-time resolved annotation.
     * The result is policy data only, not a scheduler execution credential.
     */
    public static Ast.ActorThreadPolicy requireExclusiveJavaAnnotation(
            dev.oreslang.runtime.ActorThreads annotation) {
        if (annotation == null) return Ast.ActorThreadPolicy.defaults();
        Ast.ActorThreadPolicy policy = new Ast.ActorThreadPolicy(annotation.min(), annotation.max());
        if (policy.max() != 1) {
            throw new IllegalArgumentException(
                    "Java @ActorThreads max > 1 requires verified actor scheduler admission");
        }
        return policy;
    }

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
