package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;

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
            boolean abstractMethod,
            boolean unloweredExternal) {
        public MethodSignature {
            argumentTypes = List.copyOf(argumentTypes);
            mutableArguments = List.copyOf(mutableArguments);
        }
        /** Eligibility for static declaration-only nominal contract matching.
         * Out-of-line bodies without full type/effect proof remain ineligible.
         * Never a proof of actor mailbox or ownership execution. */
        public boolean declarationEligible() {
            return visibility == Ast.Visibility.PUBLIC
                    && !async && !suspends && !generic && !structural
                    && !checkedEffects && !abstractMethod && !unloweredExternal;
        }
    }

    private static final Set<String> CONTROL_METHOD_NAMES = Set.of(
            "tear_down", "handle_state", "on_panic", "on_throw",
            "on_throws", "on_raise", "on_recover", "on_start", "on_stop",
            "on_restart", "init", "receive", "send", "end", "endWithCleanup");

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
        return collect(packageId, units, Map.of());
    }

    /**
     * Build one declaration-only index from an already validated closed
     * source inventory and its prevalidated receiver attachments. The caller
     * must first authenticate and bind every source unit, state schema and
     * external slot. This method never bypasses the normal TypeChecker gate.
     */
    static ActorMethodIndex collect(
            String packageId,
            Map<String, Ast.Program> units,
            Map<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding> receivers) {
        if (packageId == null || packageId.isBlank()) {
            throw new IllegalArgumentException("actor method index requires a package identity");
        }
        java.util.Objects.requireNonNull(units, "complete actor source inventory");
        java.util.Objects.requireNonNull(receivers, "prevalidated actor receiver attachments");
        Map<ActorKey, ActorDeclaration> actors = new LinkedHashMap<>();
        Map<MethodKey, MethodSignature> slots = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Program> entry : new TreeMap<>(units).entrySet()) {
            Ast.Program program = entry.getValue();
            if (program.namespace() == null) continue;
            for (Ast.ModuleDecl module : program.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl decl : module.declarations()) {
                    String actorName;
                    Ast.Visibility actorVisibility;
                    boolean generic;
                    Ast.ActorKind kind;
                    if (decl instanceof Ast.ClassDecl klass
                            && klass.actorKind() != Ast.ActorKind.NONE) {
                        actorName = klass.name();
                        actorVisibility = klass.visibility();
                        generic = !klass.genericParameters().isEmpty();
                        kind = klass.actorKind();
                    } else if (decl instanceof Ast.StandaloneActorDecl standalone) {
                        actorName = standalone.name();
                        actorVisibility = standalone.visibility();
                        generic = false;
                        kind = standalone.actorKind();
                    } else {
                        continue;
                    }
                    ActorKey actor = new ActorKey(packageId, program.namespace(), actorName);
                    ActorDeclaration declaration = new ActorDeclaration(
                            entry.getKey(), actorVisibility, generic, kind);
                    if (actors.putIfAbsent(actor, declaration) != null) {
                        throw new IllegalArgumentException(
                                "ambiguous nominal actor '" + actorName
                                        + "' in namespace '" + program.namespace()
                                        + "' across source units");
                    }
                    if (decl instanceof Ast.ClassDecl klass) {
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
                                    !method.effects().isEmpty(), method.isAbstract(), false);
                            if (slots.putIfAbsent(key, signature) != null) {
                                throw new IllegalArgumentException(
                                        "duplicate actor method slot '" + actorName + "."
                                                + method.name() + "/" + method.arity() + "'");
                            }
                        }
                    }
                }
            }
        }

        // Out-of-line entries are permitted only after the caller's
        // actor receiver binder has checked package, namespace, visibility,
        // duplicate slots and ingress ABI. Never export an ingress method.
        for (Map.Entry<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding> e
                : receivers.entrySet()) {
            ActorReceiverBinder.SourceSlot slot = e.getKey();
            ActorReceiverBinder.SourceBinding binding = e.getValue();
            if (!packageId.equals(slot.packageIdentity())) {
                throw new IllegalArgumentException("cross-package receiver index injection");
            }
            ActorKey key = new ActorKey(packageId, slot.namespace(), slot.actor());
            ActorDeclaration declaration = actors.get(key);
            if (declaration == null || !declaration.declaringUnit().equals(binding.actorUnit())) {
                throw new IllegalArgumentException(
                        "receiver index actor has no exact source declaration provenance");
            }
            Ast.ExternalActorMethodDecl method = binding.nominalBinding().method();
            if (!method.actorName().equals(slot.actor())
                    || !method.name().equals(slot.name())
                    || method.parameters().size() != slot.arity()) {
                throw new IllegalArgumentException("receiver slot metadata does not match method");
            }
            if (method.runtimeIngress() || CONTROL_METHOD_NAMES.contains(method.name())) {
                continue; // not public API; never authorize init/receive dispatch
            }
            MethodKey methodKey = new MethodKey(key, slot.name(), slot.arity());
            MethodSignature signature = new MethodSignature(
                    binding.methodUnit(), method.visibility(),
                    method.parameters().stream().map(Ast.Param::type).toList(),
                    method.parameters().stream().map(Ast.Param::mutable).toList(),
                    method.returnType(), false, false, false, false, false, false, true);
            if (slots.putIfAbsent(methodKey, signature) != null) {
                throw new IllegalArgumentException(
                        "duplicate inline/out-of-line actor method slot: " + methodKey);
            }
        }
        return new ActorMethodIndex(actors, slots);
    }
}
