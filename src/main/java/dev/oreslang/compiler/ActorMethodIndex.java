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

    /**
     * Both in-class actor methods and manifest-bound namespace mtd have
     * the same nominal method selector. This index is static metadata;
     * it never certifies the HIR/MIR execution of an external method.
     */
    static ActorMethodIndex collect(String packageId, Map<String, Ast.Program> units) {
        return collect(packageId, units,
                dev.oreslang.types.ActorReceiverBinder.bindSourceSet(packageId, units));
    }

    static ActorMethodIndex collect(
            String packageId, Map<String, Ast.Program> units,
            Map<dev.oreslang.types.ActorReceiverBinder.SourceSlot,
                    dev.oreslang.types.ActorReceiverBinder.SourceBinding> external) {
        if (packageId == null || packageId.isBlank()) {
            throw new IllegalArgumentException("actor method index requires package identity");
        }
        Map<ActorKey, ActorDeclaration> actors = new LinkedHashMap<>();
        Map<MethodKey, MethodSignature> slots = new LinkedHashMap<>();

        // Actor identity is indexed before method implementation; source-file
        // order does not grant or revoke an external receiver method.
        for (Map.Entry<String, Ast.Program> entry : new TreeMap<>(units).entrySet()) {
            Ast.Program program = entry.getValue();
            if (program.namespace() == null) continue;
            for (Ast.ModuleDecl module : program.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl decl : module.declarations()) {
                    ActorKey key;
                    ActorDeclaration declaration;
                    if (decl instanceof Ast.DeclaredActorDecl actor) {
                        key = new ActorKey(packageId, program.namespace(), actor.name());
                        declaration = new ActorDeclaration(
                                entry.getKey(), actor.visibility(), false, actor.actorKind());
                    } else if (decl instanceof Ast.ClassDecl klass
                            && klass.actorKind() != Ast.ActorKind.NONE) {
                        key = new ActorKey(packageId, program.namespace(), klass.name());
                        declaration = new ActorDeclaration(
                                entry.getKey(), klass.visibility(),
                                !klass.genericParameters().isEmpty(), klass.actorKind());
                        // Ast.StandaloneActorDecl is a distinct Decl, never a ClassDecl.
                        // This branch only indexes fully defined class-like actors;
                        // standalone declarations never acquire codegen authority here.
                    } else {
                        continue;
                    }
                    if (actors.putIfAbsent(key, declaration) != null) {
                        throw new IllegalArgumentException(
                                "ambiguous nominal actor '" + key.actorName()
                                        + "' in namespace '" + key.namespace() + "'");
                    }
                }
            }
        }

        for (Map.Entry<String, Ast.Program> entry : new TreeMap<>(units).entrySet()) {
            Ast.Program program = entry.getValue();
            if (program.namespace() == null) continue;
            for (Ast.ModuleDecl module : program.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl decl : module.declarations()) {
                    if (!(decl instanceof Ast.ClassDecl klass)
                            || klass.actorKind() == Ast.ActorKind.NONE) continue;
                    ActorKey actor = new ActorKey(packageId, program.namespace(), klass.name());
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (method.isStatic() || CONTROL_METHOD_NAMES.contains(method.name())) {
                            continue;
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
                                    "duplicate actor method slot '" + key + "'");
                        }
                    }
                }
            }
        }

        for (var entry : external.entrySet()) {
            var sourceKey = entry.getKey();
            var origin = entry.getValue();
            ActorKey actor = new ActorKey(sourceKey.packageIdentity(),
                    sourceKey.namespace(), sourceKey.actor());
            if (!packageId.equals(actor.packageId()) || !actors.containsKey(actor)) {
                throw new IllegalArgumentException(
                        "external method lacks locally proven actor identity: " + sourceKey);
            }
            Ast.ExternalActorMethodDecl method = origin.nominalBinding().method();
            if (CONTROL_METHOD_NAMES.contains(method.name())) {
                // Runtime/CONTROL hooks never count as a public API witness.
                continue;
            }
            MethodKey key = new MethodKey(actor, method.name(), method.parameters().size());
            MethodSignature signature = new MethodSignature(
                    origin.methodUnit(), method.visibility(),
                    method.parameters().stream().map(Ast.Param::type).toList(),
                    method.parameters().stream().map(Ast.Param::mutable).toList(),
                    method.returnType(), false, false, false,
                    method.parameters().stream().anyMatch(Ast.Param::structural),
                    false, false);
            if (slots.putIfAbsent(key, signature) != null) {
                throw new IllegalArgumentException(
                        "external actor method overlaps in-class method slot: " + key);
            }
        }
        return new ActorMethodIndex(actors, slots);
    }
}
