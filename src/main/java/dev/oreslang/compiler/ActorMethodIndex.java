package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Closed-world index of DECLARED nominal actor method slots.
 *
 * This is compile-time metadata. Lookup NEVER constructs an ActorRef,
 * acquires ActorContext, dispatches a mailbox message, or makes an external
 * receiver-method body executable. The trusted package builder collects it
 * from every supplied source unit, in deterministic source-ID order.
 */
public final class ActorMethodIndex {
    public record ActorKey(String packageId, String namespace, String actorName) { }
    public record ActorDeclaration(
            String declaringUnit,
            Ast.Visibility visibility,
            boolean generic,
            Ast.ActorKind kind) {
        /** Declaration eligibility only, not actor activation or mailbox authority. */
        public boolean publicNominalWitness() {
            return visibility == Ast.Visibility.PUBLIC && !generic
                    && kind != Ast.ActorKind.NONE;
        }
    }
    public record MethodKey(ActorKey actor, String methodName, int arity) { }
    public record MethodSignature(
            String declaringUnit,
            Ast.Visibility visibility,
            List<Ast.TypeRef> argumentTypes,
            List<Boolean> mutableArguments,
            Ast.TypeRef returnType,
            boolean async,
            boolean suspends,
            boolean generic,
            boolean structural,
            boolean checkedEffects,
            boolean abstractMethod) {
        public MethodSignature {
            argumentTypes = List.copyOf(argumentTypes);
            mutableArguments = List.copyOf(mutableArguments);
        }
        /** Eligibility for static declaration-only nominal contract matching.
         * Never a proof of actual actor mailbox or ownership execution. */
        public boolean declarationEligible() {
            return visibility == Ast.Visibility.PUBLIC
                    && !async && !suspends && !generic && !structural
                    && !checkedEffects && !abstractMethod;
        }
    }

    private static final Set<String> CONTROL_METHOD_NAMES = Set.of(
            "tear_down", "handle_state", "on_panic", "on_throw",
            "on_throws", "on_raise", "on_recover", "on_start", "on_stop",
            "on_restart", "receive", "send", "end", "endWithCleanup");

    private final Map<ActorKey, ActorDeclaration> declaredActors;
    private final Map<MethodKey, MethodSignature> methodSlots;

    private ActorMethodIndex(
            Map<ActorKey, ActorDeclaration> declaredActors,
            Map<MethodKey, MethodSignature> methodSlots) {
        this.declaredActors = Map.copyOf(declaredActors);
        this.methodSlots = Map.copyOf(methodSlots);
    }

    /** A namespace-qualified lookup never falls back to another namespace. */
    public boolean hasActor(ActorKey actor) {
        return declaredActors.containsKey(actor);
    }
    public String declaringUnit(ActorKey actor) {
        ActorDeclaration declaration = declaredActors.get(actor);
        return declaration == null ? null : declaration.declaringUnit();
    }
    public ActorDeclaration actor(ActorKey key) { return declaredActors.get(key); }
    public MethodSignature method(MethodKey key) { return methodSlots.get(key); }
    public Map<ActorKey, ActorDeclaration> actors() { return declaredActors; }
    public Map<MethodKey, MethodSignature> methods() { return methodSlots; }

    static ActorMethodIndex collect(String packageId, Map<String, Ast.Program> units) {
        if (packageId == null || packageId.isBlank()) {
            throw new IllegalArgumentException("actor method index requires a package identity");
        }
        Map<ActorKey, ActorDeclaration> actors = new LinkedHashMap<>();
        Map<MethodKey, MethodSignature> slots = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Program> entry : new TreeMap<>(units).entrySet()) {
            Ast.Program program = entry.getValue();
            if (program.namespace() == null) continue; // no ambient root namespace
            for (Ast.ModuleDecl module : program.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl decl : module.declarations()) {
                    if (!(decl instanceof Ast.ClassDecl klass)
                            || klass.actorKind() == Ast.ActorKind.NONE) continue;
                    ActorKey actor = new ActorKey(
                            packageId, program.namespace(), klass.name());
                    ActorDeclaration actorDeclaration = new ActorDeclaration(
                            entry.getKey(), klass.visibility(),
                            !klass.genericParameters().isEmpty(), klass.actorKind());
                    if (actors.putIfAbsent(actor, actorDeclaration) != null) {
                        throw new IllegalArgumentException(
                                "ambiguous nominal actor '" + klass.name()
                                        + "' in namespace '" + program.namespace()
                                        + "' across source units");
                    }
                    if (klass.standaloneBinding() != null) {
                        throw new IllegalArgumentException(
                                "unlowered standalone actor binding cannot enter method index: "
                                        + klass.name());
                    }
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (method.isStatic() || CONTROL_METHOD_NAMES.contains(method.name())) {
                            continue; // lifecycle/CONTROL is not a callable receiver slot
                        }
                        MethodKey key = new MethodKey(actor, method.name(), method.arity());
                        MethodSignature signature = new MethodSignature(
                                entry.getKey(), method.visibility(),
                                method.parameters().stream().map(Ast.Param::type).toList(),
                                method.parameters().stream().map(Ast.Param::mutable).toList(),
                                method.returnType(), method.async(), method.suspends(),
                                !method.genericParameters().isEmpty(),
                                method.structural() || method.parameters().stream()
                                        .anyMatch(Ast.Param::structural)
                                        || method.explicitReceiverType() != null,
                                !method.effects().isEmpty(), method.isAbstract());
                        if (slots.putIfAbsent(key, signature) != null) {
                            throw new IllegalArgumentException(
                                    "duplicate actor method slot '" + klass.name() + "."
                                            + method.name() + "' with arity " + method.arity());
                        }
                    }
                }
            }
        }
        return new ActorMethodIndex(actors, slots);
    }
}
