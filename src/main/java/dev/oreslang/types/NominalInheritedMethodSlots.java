package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.NominalEnumOverloads.PairStatus;
import dev.oreslang.types.NominalEnumOverloads.Signature;
import dev.oreslang.types.NominalMethodSlots.Domain;
import dev.oreslang.types.NominalMethodSlots.Slot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed-world, compiler-only proof for one name/arity method family across
 * inherited classes. It does not activate dispatch or publish a runtime vtable.
 *
 * <p>Local classes and enum declaration identities are mandatory. Generic
 * owners/parents, imports, structural parameters, unsupported effects, and
 * unresolved identities cannot silently become runtime lookup policies.
 */
public final class NominalInheritedMethodSlots {
    private NominalInheritedMethodSlots() { }

    public record Target(String ownerId, Ast.ClassDecl owner, Ast.MethodDecl method) {
        public Target {
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(method);
        }
    }

    /** Returns exact declaration identities after validating diamonds and
     * overrides. A child replaces only the matching full nominal slot, never
     * all other overloads that share its name and argument count. */
    public static Map<Slot, Target> prove(
            Ast.Program program, String rootClass,
            Domain domain, String methodName, int arity) {
        Objects.requireNonNull(program);
        Objects.requireNonNull(rootClass);
        Objects.requireNonNull(domain);
        Objects.requireNonNull(methodName);
        if (rootClass.isBlank() || methodName.isBlank()
                || arity < 0 || arity > NominalEnumOverloads.MAX_OVERLOAD_ARITY) {
            throw new IllegalArgumentException("invalid nominal method family request");
        }
        Planner planner = new Planner(program);
        return planner.collect(planner.resolve(rootClass, null), domain, methodName, arity);
    }

    /**
     * Compiler-only selection for an inherited method family. The argument
     * proofs MUST originate from statically resolved enum declarations; this
     * method never accepts runtime object values, enum tags, or payloads.
     *
     * <p>Selection does not make the target executable. A separate immutable
     * call-site binding and matching runtime/JIT/AOT vtable ABI are required.
     */
    public static Target selectStatic(
            Ast.Program program, String rootClass, Domain domain,
            String methodName, int arity, Map<Integer, String> staticEnumProofs) {
        Objects.requireNonNull(staticEnumProofs, "static enum proofs");
        Map<Slot, Target> plan = prove(program, rootClass, domain, methodName, arity);
        if (plan.isEmpty()) {
            throw new IllegalArgumentException("no inherited nominal method candidates");
        }
        List<Signature> signatures = new ArrayList<>(plan.size());
        for (Slot slot : plan.keySet()) {
            signatures.add(new Signature(slot.name(), slot.positions()));
        }
        Signature selected = NominalEnumOverloads.select(signatures, staticEnumProofs);
        for (Map.Entry<Slot, Target> entry : plan.entrySet()) {
            if (selected.positions().equals(entry.getKey().positions())) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException("static method selection lost exact declaration identity");
    }

    private static final class Planner {
        private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();
        private final IdentityHashMap<Ast.ClassDecl, String> qualifiedNames = new IdentityHashMap<>();
        private final IdentityHashMap<Ast.ClassDecl, String> moduleNames = new IdentityHashMap<>();
        private final Map<String, String> enums;
        // One Planner handles exactly one name/arity/domain. Memoization
        // prevents exponential re-walks through repeated diamond ancestry.
        private final IdentityHashMap<Ast.ClassDecl, Map<Slot, Target>> memo =
                new IdentityHashMap<>();
        private static final int MAX_INHERITANCE_DEPTH = 128;
        private static final int MAX_PROOF_CLASSES = 1024;

        private Planner(Ast.Program program) {
            enums = NominalEnumOverloads.eligibleEnumSymbols(program);
            String namespace = program.namespace() == null || program.namespace().isBlank()
                    ? "" : program.namespace() + "::";
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl decl : module.declarations()) {
                    if (!(decl instanceof Ast.ClassDecl cls)) continue;
                    String qualified = module.name() + "." + cls.name();
                    if (classes.putIfAbsent(qualified, cls) != null) {
                        throw new IllegalArgumentException("duplicate class identity " + qualified);
                    }
                    qualifiedNames.put(cls, namespace + module.name() + "::" + cls.name());
                    moduleNames.put(cls, module.name());
                    if (!ambiguous.contains(cls.name())) {
                        Ast.ClassDecl old = classes.putIfAbsent(cls.name(), cls);
                        if (old != null && old != cls) {
                            ambiguous.add(cls.name());
                            classes.remove(cls.name());
                        }
                    }
                }
            }
        }

        private Ast.ClassDecl resolve(String name, Ast.ClassDecl current) {
            // Local lexical module wins even when the simple spelling occurs
            // in several modules; never guess among ambiguous foreign owners.
            if (current != null && !name.contains(".")) {
                Ast.ClassDecl local = classes.get(moduleNames.get(current) + "." + name);
                if (local != null) return local;
            }
            if (ambiguous.contains(name)) {
                throw new IllegalArgumentException("ambiguous class declaration identity " + name);
            }
            Ast.ClassDecl found = classes.get(name);
            if (found != null) return found;
            throw new IllegalArgumentException(
                    "nominal method proof cannot import or resolve class " + name);
        }

        private Map<Slot, Target> collect(
                Ast.ClassDecl owner, Domain domain, String name, int arity) {
            return collect(owner, domain, name, arity,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        private Map<Slot, Target> collect(
                Ast.ClassDecl owner, Domain domain, String name, int arity,
                Set<Ast.ClassDecl> visiting) {
            Map<Slot, Target> cached = memo.get(owner);
            if (cached != null) return cached;
            if (visiting.size() >= MAX_INHERITANCE_DEPTH) {
                throw new IllegalArgumentException("nominal method ancestry depth bound exceeded");
            }
            // A shallow inheritance DAG can still have unbounded breadth.
            // Bound completed and currently active nodes before descending.
            if (memo.size() + visiting.size() >= MAX_PROOF_CLASSES) {
                throw new IllegalArgumentException("nominal method ancestry class-count bound exceeded");
            }
            if (!visiting.add(owner)) {
                throw new IllegalArgumentException("inheritance cycle in nominal method proof");
            }
            try {
                if (!owner.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "generic class requires substitution before nominal method proof");
                }
                if (owner.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "actor methods are excluded from nominal method vtable proof");
                }
                // This helper is intentionally not an interface/contract solver.
                // Never advertise a complete inherited proof until those
                // obligations have been reconciled by the production checker.
                if (!owner.interfaces().isEmpty()) {
                    throw new IllegalArgumentException(
                            "interface contracts require integrated nominal method slot validation");
                }
                for (Ast.FieldDecl field : owner.fields()) {
                    if (field.name().equals(name) && domain == Domain.INSTANCE) {
                        throw new IllegalArgumentException(
                                "field and nominal method share a member name: " + name);
                    }
                }
                Map<Slot, List<Target>> inherited = new LinkedHashMap<>();
                for (Ast.TypeRef parentRef : owner.parents()) {
                    if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                    if (parentRef.inferArguments() || !parentRef.arguments().isEmpty()) {
                        throw new IllegalArgumentException("unsubstituted generic parent class");
                    }
                    Ast.ClassDecl parent = resolve(parentRef.name(), owner);
                    for (Map.Entry<Slot, Target> entry :
                            collect(parent, domain, name, arity, visiting).entrySet()) {
                        // A private ancestor method is not an inheritable
                        // virtual slot. Conservatively reject until the
                        // visibility-aware linker can distinguish hiding,
                        // replacement, and accessible override origins.
                        if (entry.getValue().method().visibility()
                                != Ast.Visibility.PUBLIC) {
                            throw new IllegalArgumentException(
                                    "private ancestor method cannot enter nominal inherited vtable proof");
                        }
                        List<Target> contributions =
                                inherited.computeIfAbsent(entry.getKey(),
                                        ignored -> new ArrayList<>());
                        boolean sameDeclaration = contributions.stream().anyMatch(existing ->
                                existing.method() == entry.getValue().method());
                        if (!sameDeclaration) contributions.add(entry.getValue());
                    }
                }

                Map<Slot, Target> local = new LinkedHashMap<>();
                for (Ast.MethodDecl method : owner.methods()) {
                    if ((method.isStatic() ? Domain.STATIC : Domain.INSTANCE) != domain
                            || !method.name().equals(name) || method.arity() != arity) continue;
                    if (!method.genericParameters().isEmpty()) {
                        throw new IllegalArgumentException("generic method needs specialization");
                    }
                    if (method.structural()) {
                        throw new IllegalArgumentException(
                                "structural methods cannot enter nominal method proof");
                    }
                    if (domain == Domain.STATIC && method.isAbstract()) {
                        throw new IllegalArgumentException(
                                "abstract static method has no executable nominal slot");
                    }
                    Signature sig = NominalEnumOverloads.signature(
                            name, method.parameters(), enums);
                    Slot slot = new Slot(domain, name, sig.positions());
                    Target target = new Target(qualifiedNames.get(owner), owner, method);
                    if (local.putIfAbsent(slot, target) != null) {
                        throw new IllegalArgumentException("duplicate local nominal method slot");
                    }
                }

                Map<Slot, Target> effective = new LinkedHashMap<>();
                for (Map.Entry<Slot, List<Target>> inheritedSlot : inherited.entrySet()) {
                    Target override = local.get(inheritedSlot.getKey());
                    if (override != null) {
                        for (Target base : inheritedSlot.getValue()) {
                            checkOverride(base, override);
                        }
                        effective.put(inheritedSlot.getKey(), override);
                    } else {
                        if (inheritedSlot.getValue().size() != 1) {
                            throw new IllegalArgumentException(
                                    "ambiguous inherited nominal diamond slot "
                                            + inheritedSlot.getKey());
                        }
                        effective.put(inheritedSlot.getKey(), inheritedSlot.getValue().getFirst());
                    }
                }
                effective.putAll(local);
                if (!owner.isAbstract()) {
                    for (Target target : effective.values()) {
                        if (target.method().isAbstract()) {
                            throw new IllegalArgumentException(
                                    "concrete class has unresolved abstract nominal method: "
                                            + name + "/" + arity);
                        }
                    }
                }
                if (effective.size() > NominalEnumOverloads.MAX_SAME_ARITY_CANDIDATES) {
                    throw new IllegalArgumentException("inherited nominal overload candidate bound exceeded");
                }
                List<Signature> signatures = new ArrayList<>(effective.size());
                for (Slot slot : effective.keySet()) {
                    signatures.add(new Signature(slot.name(), slot.positions()));
                }
                for (int i = 0; i < signatures.size(); i++) {
                    for (int j = i + 1; j < signatures.size(); j++) {
                        if (NominalEnumOverloads.compare(signatures.get(i), signatures.get(j))
                                != PairStatus.DISTINCT_ENUMS) {
                            throw new IllegalArgumentException(
                                    "inherited nominal method family is not pairwise distinct");
                        }
                    }
                }
                Map<Slot, Target> proved = Map.copyOf(effective);
                memo.put(owner, proved);
                return proved;
            } finally {
                visiting.remove(owner);
            }
        }

        private static void checkOverride(Target base, Target child) {
            Ast.MethodDecl old = base.method();
            Ast.MethodDecl now = child.method();
            if (old.visibility() == Ast.Visibility.PUBLIC
                    && now.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("nominal override weakens public visibility");
            }
            // Until a complete effect/covariance checker is integrated, exact
            // ABI equality is safer than guessing whether a change is sound.
            if (!Objects.equals(old.returnType(), now.returnType())
                    || old.async() != now.async()
                    || old.suspends() != now.suspends()
                    || old.structural() != now.structural()
                    || !Objects.equals(old.explicitReceiverType(), now.explicitReceiverType())
                    // Annotations may carry capability/effect/ABI contracts.
                    // Until each is classified, do not silently override them.
                    || !Objects.equals(old.annotations(), now.annotations())
                    || (now.isAbstract() && !old.isAbstract())) {
                throw new IllegalArgumentException(
                        "incompatible nominal method override contract for " + old.name());
            }
        }
    }
}
