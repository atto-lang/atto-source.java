package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Static-only nominal binding of external actor receiver declarations.
 *
 * This pass does not typecheck or lower method bodies, emit callable symbols,
 * allocate ActorContext capabilities or grant any actor execution authority.
 * TypeChecker must continue to reject standalone actors and external method
 * bodies until checked actor-context/MIR lowering is available.
 */
public final class ActorReceiverBinder {
    private ActorReceiverBinder() { }

    public record Slot(String module, String actor, String name, int arity) {
        public Slot {
            if (module == null || module.isBlank()
                    || actor == null || actor.isBlank()
                    || name == null || name.isBlank() || arity < 0) {
                throw new IllegalArgumentException("invalid external actor method slot");
            }
        }
    }

    public record Binding(Ast.Decl nominalActor, Ast.ExternalActorMethodDecl method) {
        public Binding {
            java.util.Objects.requireNonNull(method, "external actor method");
            String targetName;
            if (nominalActor instanceof Ast.StandaloneActorDecl standalone) {
                targetName = standalone.name();
            } else if (nominalActor instanceof Ast.ClassDecl klass
                    && klass.actorKind() != Ast.ActorKind.NONE) {
                targetName = klass.name();
            } else {
                throw new IllegalArgumentException("receiver target must be a nominal actor declaration");
            }
            if (!targetName.equals(method.actorName())) {
                throw new IllegalArgumentException(
                        "receiver binding's actor identity must match the external method target");
            }
        }
    }

    /**
     * Every module in a compilation unit is an independent ownership domain.
     * Equal names in different modules NEVER imply shared implementation
     * authority; no implicit cross-file or cross-package linking is performed.
     */
    public static Map<Slot, Binding> bind(Ast.Program program) {
        java.util.Objects.requireNonNull(program, "actor receiver program");
        boolean hasExternalActorSyntax = program.modules().stream().anyMatch(
                module -> module.declarations().stream().anyMatch(
                        decl -> decl instanceof Ast.StandaloneActorDecl
                                || decl instanceof Ast.ExternalActorMethodDecl));
        // Do not perturb existing class-like actor programs before new
        // standalone/external receiver syntax actually appears.
        if (!hasExternalActorSyntax) return Map.of();

        Map<Slot, Binding> bindings = new LinkedHashMap<>();
        Set<String> modules = new LinkedHashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            if (!modules.add(module.name())) {
                throw new IllegalArgumentException(
                        "duplicate module '" + module.name() + "' in actor receiver binding");
            }
            bindModule(module, bindings);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    /**
     * An explicitly supplied package source set can resolve root-namespace
     * external actor methods across files. This is STATIC METADATA ONLY:
     * the caller must authenticate package membership separately, and the
     * executable compiler must still reject external actor method bodies.
     *
     * In particular, equal textual namespaces do not imply cross-package
     * authority. Named modules are never implicitly coalesced across files.
     */
    public record SourceSlot(
            String packageIdentity, String namespace,
            String actor, String name, int arity) {
        public SourceSlot {
            if (packageIdentity == null || packageIdentity.isBlank()
                    || namespace == null || namespace.isBlank()
                    || actor == null || actor.isBlank()
                    || name == null || name.isBlank() || arity < 0) {
                throw new IllegalArgumentException("invalid package actor receiver slot");
            }
        }
    }

    public record SourceBinding(
            String actorUnit, String methodUnit, Binding nominalBinding) {
        public SourceBinding {
            java.util.Objects.requireNonNull(actorUnit, "actor source unit");
            java.util.Objects.requireNonNull(methodUnit, "method source unit");
            java.util.Objects.requireNonNull(nominalBinding, "nominal binding");
        }
    }

    public static Map<SourceSlot, SourceBinding> bindSourceSet(
            String packageIdentity, Map<String, Ast.Program> sourceUnits) {
        if (packageIdentity == null || packageIdentity.isBlank()) {
            throw new IllegalArgumentException("cross-file receiver binding requires a package identity");
        }
        java.util.Objects.requireNonNull(sourceUnits, "explicit package source units");
        if (sourceUnits.size() > 4096) {
            throw new IllegalArgumentException("cross-file actor binding source-set exceeds 4096 units");
        }
        Map<String, Ast.Program> ordered = new TreeMap<>();
        Map<String, String> foldedSourceIds = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Program> unit : sourceUnits.entrySet()) {
            String sourceId = unit.getKey();
            if (!validSourceUnitId(sourceId)) {
                throw new IllegalArgumentException("invalid package-relative source unit ID: " + sourceId);
            }
            if (unit.getValue() == null) {
                throw new IllegalArgumentException("null source program: " + sourceId);
            }
            String previous = foldedSourceIds.putIfAbsent(
                    sourceId.toLowerCase(Locale.ROOT), sourceId);
            if (previous != null && !previous.equals(sourceId)) {
                throw new IllegalArgumentException(
                        "case-insensitive source unit collision: " + previous + " and " + sourceId);
            }
            ordered.put(sourceId, unit.getValue());
        }
        Map<String, List<Ast.Decl>> grouped = new TreeMap<>();
        // Record identity instead of structural equality: two identical AST
        // values from different files remain different declarations. Reusing
        // one AST instance under two source IDs is ambiguous and rejected.
        Map<Ast.Decl, String> origins = new IdentityHashMap<>();
        int declarationCount = 0;
        for (Map.Entry<String, Ast.Program> unit : ordered.entrySet()) {
            String sourceId = unit.getKey();
            Ast.Program program = unit.getValue();
            String namespace = program.namespace();
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException(
                        "cross-file actor methods require an explicit source namespace: " + sourceId);
            }
            for (Ast.ModuleDecl module : program.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) {
                    if (module.declarations().stream()
                            .anyMatch(d -> d instanceof Ast.ExternalActorMethodDecl)) {
                        throw new IllegalArgumentException(
                                "named modules do not share cross-file actor receiver scope: " + sourceId);
                    }
                    continue;
                }
                List<Ast.Decl> declarations =
                        grouped.computeIfAbsent(namespace, ignored -> new ArrayList<>());
                for (Ast.Decl declaration : module.declarations()) {
                    if (++declarationCount > 65536) {
                        throw new IllegalArgumentException(
                                "cross-file actor binding exceeds 65536 root declarations");
                    }
                    if (declaration == null) {
                        throw new IllegalArgumentException("null source declaration: " + sourceId);
                    }
                    String previousUnit = origins.putIfAbsent(declaration, sourceId);
                    if (previousUnit != null && !previousUnit.equals(sourceId)) {
                        throw new IllegalArgumentException(
                                "ambiguous declaration provenance: one AST node appears in source units '"
                                        + previousUnit + "' and '" + sourceId + "'");
                    }
                    declarations.add(declaration);
                }
            }
        }

        Map<SourceSlot, SourceBinding> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, List<Ast.Decl>> group : grouped.entrySet()) {
            // Validate namespace-wide nominal type collisions even if there
            // are zero external methods. Otherwise adding an mtd later could
            // silently change the actor bound to an existing type identity.
            Ast.ModuleDecl syntheticRoot = new Ast.ModuleDecl(
                    Parser.ROOT_MODULE, List.of(), List.of(), group.getValue());
            Map<Slot, Binding> local = new LinkedHashMap<>();
            bindModule(syntheticRoot, local);
            for (Map.Entry<Slot, Binding> entry : local.entrySet()) {
                Binding binding = entry.getValue();
                String actorUnit = origins.get(binding.nominalActor());
                String methodUnit = origins.get(binding.method());
                if (actorUnit == null || methodUnit == null) {
                    throw new IllegalArgumentException(
                            "external actor method has no source provenance");
                }
                // Method attachment is actor-owned code inside the same
                // verified namespace, including for a non-public actor.
                // This metadata does not make that actor public or authorize
                // an unrelated function to call its private members.
                Slot slot = entry.getKey();
                SourceSlot key = new SourceSlot(
                        packageIdentity, group.getKey(), slot.actor(),
                        slot.name(), slot.arity());
                if (resolved.putIfAbsent(key, new SourceBinding(
                        actorUnit, methodUnit, binding)) != null) {
                    throw new IllegalArgumentException(
                            "duplicate cross-file external actor method slot: " + key);
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
    }

    /** Only normalized, package-relative slash-delimited source IDs are valid. */
    private static boolean validSourceUnitId(String sourceId) {
        if (sourceId == null || sourceId.isBlank()
                || sourceId.startsWith("/") || sourceId.indexOf('\\') >= 0
                || (!sourceId.endsWith(".atto") && !sourceId.endsWith(".ores"))) {
            return false;
        }
        // Keep source-unit identities portable and unambiguous on Windows,
        // POSIX and filesystem-based package resolvers. ':' also denotes
        // drive letters and NTFS alternate data streams. Bidi/format control
        // code points can visually impersonate trusted package paths.
        if (sourceId.indexOf(':') >= 0) return false;
        for (String segment : sourceId.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        for (int i = 0; i < sourceId.length(); ) {
            int codePoint = sourceId.codePointAt(i);
            if (Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT
                    || codePoint == 0x2028 || codePoint == 0x2029) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static void bindModule(
            Ast.ModuleDecl module, Map<Slot, Binding> bindings) {
        Map<String, Ast.Decl> nominalActors = new LinkedHashMap<>();
        Map<String, Set<String>> embeddedInstanceSlots = new LinkedHashMap<>();
        Map<String, String> declaredTypes = new LinkedHashMap<>();
        Set<String> receiverAliases = new LinkedHashSet<>();

        // Two passes: the actor may be declared after its external mtd body
        // in the same module; no order-dependent resolution is permitted.
        for (Ast.Decl declaration : module.declarations()) {
            String typeName = null;
            String category = null;
            if (declaration instanceof Ast.StandaloneActorDecl actor) {
                typeName = actor.name();
                category = "standalone actor";
                // Canonical 'declare actor A;' has no class-level alias.
                // The (Actor receiver) name belongs to each mtd declaration.
                if (actor.receiverName() != null
                        && !receiverAliases.add(actor.receiverName())) {
                    throw new IllegalArgumentException(
                            "duplicate standalone actor receiver binding '"
                                    + module.name() + "." + actor.receiverName() + "'");
                }
                nominalActors.put(typeName, actor);
            } else if (declaration instanceof Ast.ClassDecl klass) {
                typeName = klass.name();
                category = klass.actorKind() == Ast.ActorKind.NONE ? "class" : "actor class";
                if (klass.actorKind() != Ast.ActorKind.NONE) {
                    nominalActors.put(typeName, klass);
                    Set<String> slots = new LinkedHashSet<>();
                    for (Ast.MethodDecl owned : klass.methods()) {
                        if (owned.isStatic()) continue;
                        if (!slots.add(methodSelector(owned.name(), owned.arity()))) {
                            throw new IllegalArgumentException(
                                    "duplicate in-class actor method slot '" + module.name() + "."
                                            + klass.name() + "." + owned.name() + "/"
                                            + owned.arity() + "'");
                        }
                    }
                    embeddedInstanceSlots.put(typeName, slots);
                }
            } else if (declaration instanceof Ast.InterfaceDecl iface) {
                typeName = iface.name();
                category = iface.moduleContract() ? "contract" : "interface";
            } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                typeName = alias.name();
                category = "type alias";
            }
            if (typeName != null) {
                if (typeName.equals("Symbol")) {
                    throw new IllegalArgumentException(
                            "Symbol is a reserved nominal built-in; standalone actors may not shadow it");
                }
                String previous = declaredTypes.putIfAbsent(typeName, category);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate actor/type identity '" + module.name() + "."
                                    + typeName + "' (" + previous + " versus " + category + ")");
                }
            }
        }

        // Resolve bootstrap conflicts over the ENTIRE namespace before
        // binding any method. Init and on_start are mutually exclusive even
        // when one is in-class and the other is attached from another file.
        // This pass is order independent and never grants runtime authority.
        Map<String, Set<String>> startupRoles = new LinkedHashMap<>();
        for (Ast.Decl declaration : module.declarations()) {
            if (declaration instanceof Ast.ClassDecl klass
                    && klass.actorKind() != Ast.ActorKind.NONE) {
                for (Ast.MethodDecl owned : klass.methods()) {
                    if (owned.name().equals("init") || owned.name().equals("on_start")) {
                        startupRoles.computeIfAbsent(klass.name(),
                                ignored -> new LinkedHashSet<>()).add(owned.name());
                    }
                }
            } else if (declaration instanceof Ast.ExternalActorMethodDecl attached
                    && nominalActors.containsKey(attached.actorName())
                    && (attached.name().equals("init") || attached.name().equals("on_start"))) {
                startupRoles.computeIfAbsent(attached.actorName(),
                        ignored -> new LinkedHashSet<>()).add(attached.name());
            }
        }
        for (Map.Entry<String, Set<String>> entry : startupRoles.entrySet()) {
            if (entry.getValue().contains("init") && entry.getValue().contains("on_start")) {
                throw new IllegalArgumentException(
                        "ACTOR_INIT_ON_START_CONFLICT: actor '" + entry.getKey()
                                + "' may not register both init and legacy on_start");
            }
        }

        for (Ast.Decl declaration : module.declarations()) {
            if (!(declaration instanceof Ast.ExternalActorMethodDecl method)) continue;
            Ast.Decl target = nominalActors.get(method.actorName());
            if (target == null) {
                throw new IllegalArgumentException(
                        "external actor method '" + module.name() + "." + method.name()
                                + "' has no nominal actor '" + method.actorName()
                                + "' declared in the same module; imported actors and "
                                + "actors in other modules cannot grant receiver authority");
            }
            // A fresh canonical declaration does not inherit the pre-v1
            // on_start adapter; mixing spellings must never double-start.
            if (target instanceof Ast.StandaloneActorDecl standalone
                    && standalone.receiverName() == null
                    && method.name().equals("on_start")) {
                throw new IllegalArgumentException(
                        "ACTOR_LEGACY_START_UNSUPPORTED: declare actor requires "
                                + "external init, not legacy on_start");
            }
            // Canonical declarations use the strict runtime ingress ABI.
            // Legacy 'define actor A as a;' and inline class actors retain
            // their existing unmarked method compatibility until migration.
            if (target instanceof Ast.StandaloneActorDecl standalone
                    && standalone.receiverName() == null
                    && (method.name().equals("init") || method.name().equals("receive"))
                    && !method.runtimeEntrypoint()) {
                throw new IllegalArgumentException(
                        "ACTOR_EXTERNAL_REQUIRED: declared actor lifecycle method '"
                                + method.name() + "' must use 'define external mtd'");
            }
            // Lifecycle-public denial takes precedence over actor export visibility.
            validateHookShape(method);
            Ast.Visibility actorVisibility =
                    target instanceof Ast.StandaloneActorDecl standalone
                            ? standalone.visibility()
                            : ((Ast.ClassDecl) target).visibility();
            if (method.visibility() == Ast.Visibility.PUBLIC
                    && actorVisibility != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException(
                        "public external actor method '" + module.name() + "."
                                + method.name() + "' cannot expose private actor '"
                                + method.actorName() + "'");
            }
            if (target instanceof Ast.ClassDecl klass) {
                if (!klass.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "external receiver method on generic actor '"
                                    + module.name() + "." + klass.name()
                                    + "' requires generic witness lowering");
                }
                if (embeddedInstanceSlots.get(klass.name()).contains(
                        methodSelector(method.name(), method.parameters().size()))) {
                    throw new IllegalArgumentException(
                            "external actor method slot '" + module.name() + "."
                                    + klass.name() + "." + method.name() + "/"
                                    + method.parameters().size()
                                    + "' conflicts with an in-class actor method");
                }
            }
            // Runtime lifecycle slots are nominal roles, not overloadable by
            // argument count. Preserve the old name/arity rule for ordinary mtd.
            if (method.name().equals("init") || method.name().equals("receive")) {
                for (Slot existing : bindings.keySet()) {
                    if (existing.module().equals(module.name())
                            && existing.actor().equals(method.actorName())
                            && existing.name().equals(method.name())) {
                        throw new IllegalArgumentException(
                                "ACTOR_DUPLICATE_" + method.name().toUpperCase(java.util.Locale.ROOT)
                                        + ": duplicate lifecycle entrypoint role");
                    }
                }
                if (method.runtimeEntrypoint() && target instanceof Ast.ClassDecl classActor
                        && classActor.methods().stream().anyMatch(m -> m.name().equals(method.name()))) {
                    throw new IllegalArgumentException(
                            "external actor lifecycle role conflicts with inline actor method");
                }
            }
            Slot key = new Slot(
                    module.name(), method.actorName(), method.name(),
                    method.parameters().size());
            Binding previous = bindings.putIfAbsent(key, new Binding(target, method));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate external actor method slot '"
                                + key.module() + "." + key.actor() + "."
                                + key.name() + "/" + key.arity()
                                + "'; parameter types and return types do not disambiguate overloads");
            }
        }
    }

    private static String methodSelector(String name, int arity) {
        // The caller-visible selector is name/arity. Types, parameter names,
        // effects and return types never distinguish two instance method slots.
        return name + "/" + arity;
    }

    private static void validateHookShape(Ast.ExternalActorMethodDecl method) {
        String name = method.name();
        if (method.runtimeEntrypoint()) {
            if (!name.equals("init") && !name.equals("receive")) {
                throw new IllegalArgumentException(
                        "ACTOR_EXTERNAL_ROLE_UNSUPPORTED: 'external' marks only "
                                + "init/receive runtime entrypoint roles in v1");
            }
            if (method.visibility() != Ast.Visibility.PRIVATE) {
                throw new IllegalArgumentException(
                        "ACTOR_EXTERNAL_LIFECYCLE_PRIVATE: runtime entrypoints "
                                + "cannot be public guest methods");
            }
            if (name.equals("init")) {
                if (method.returnType().name().equals(method.actorName())) {
                    throw new IllegalArgumentException(
                            "ACTOR_INIT_OWNER_ESCAPE: init must not return raw actor self");
                }
                // A user-defined nominal type called 'state' is NOT proof of
                // a compiler-issued sealed state builder/capability. Until
                // builder provenance and ownership are checked in typed IR,
                // accept only the non-escaping void bootstrap ABI.
                if (!isVoid(method.returnType())) {
                    throw new IllegalArgumentException(
                            "ACTOR_INIT_STATE_BUILDER_UNVERIFIED: external init "
                                    + "must return void until compiler-owned state builders "
                                    + "have verified provenance and ownership lowering");
                }
            }
        }
        if (Set.of("on_panic", "on_throw", "on_throws", "on_raise",
                "on_recover", "handle_state", "on_stop", "on_restart").contains(name)) {
            throw new IllegalArgumentException(
                    "external actor method '" + name
                            + "' cannot implement a compiler-owned or unverified CONTROL hook");
        }
        if (name.equals("receive")) {
            // Same private ActorMail<T> -> void ABI as the existing actor-local
            // receive hook; never authorize public or arbitrary typed-message
            // overloads through a static nominal binding.
            if (method.visibility() != Ast.Visibility.PRIVATE
                    || method.parameters().size() != 1
                    || !method.parameters().getFirst().type().name().equals("ActorMail")
                    || method.parameters().getFirst().type().arguments().size() != 1
                    || method.parameters().getFirst().type().inferArguments()
                    || !isVoid(method.returnType())) {
                throw new IllegalArgumentException(
                        "external receive must be private and accept one "
                                + "ActorMail<T> argument with void return");
            }
        } else if (name.equals("on_start") || name.equals("tear_down")) {
            if (method.visibility() != Ast.Visibility.PRIVATE
                    || !method.parameters().isEmpty()
                    || !isVoid(method.returnType())) {
                throw new IllegalArgumentException(
                        "external lifecycle method '" + name
                                + "' must be private, zero-argument and return void");
            }
        }
    }

    private static boolean isVoid(Ast.TypeRef type) {
        return type.name().equals("void") && type.arguments().isEmpty()
                && !type.inferArguments();
    }
}
