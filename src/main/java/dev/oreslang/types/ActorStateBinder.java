package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Static-only sealed state attachment to a nominal namespace actor.
 * No physical actor heap, state pointer, critical permit, call dispatch,
 * contract witness, or public mutable value is created by this index.
 *
 * The caller-supplied source set is NOT a verified package manifest.
 */
public final class ActorStateBinder {
    private ActorStateBinder() { }

    public record Slot(String packageIdentity, String namespace, String actor) {
        public Slot {
            if (packageIdentity == null || packageIdentity.isBlank()
                    || namespace == null || namespace.isBlank()
                    || actor == null || actor.isBlank()) {
                throw new IllegalArgumentException("invalid actor state slot");
            }
        }
    }

    public record Binding(String actorUnit, String stateUnit, Ast.ActorStateDecl schema) {
        public Binding {
            java.util.Objects.requireNonNull(actorUnit, "actor origin");
            java.util.Objects.requireNonNull(stateUnit, "state origin");
            java.util.Objects.requireNonNull(schema, "sealed state schema");
        }
    }

    public static Map<Slot, Binding> bindSourceSet(
            String packageIdentity, Map<String, Ast.Program> sourceUnits) {
        if (packageIdentity == null || packageIdentity.isBlank()) {
            throw new IllegalArgumentException("attached actor state requires a package identity");
        }
        java.util.Objects.requireNonNull(sourceUnits, "state source set");
        if (sourceUnits.size() > 4096) {
            throw new IllegalArgumentException("attached actor state source set exceeds 4096 units");
        }

        Map<String, Ast.Program> ordered = new TreeMap<>();
        Map<String, String> folded = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Program> entry : sourceUnits.entrySet()) {
            String sourceId = entry.getKey();
            if (!validSourceId(sourceId)) {
                throw new IllegalArgumentException("invalid actor state source unit: " + sourceId);
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("null actor state source program: " + sourceId);
            }
            String previous = folded.putIfAbsent(sourceId.toLowerCase(Locale.ROOT), sourceId);
            if (previous != null && !previous.equals(sourceId)) {
                throw new IllegalArgumentException("actor state source path case collision");
            }
            ordered.put(sourceId, entry.getValue());
        }

        Map<Slot, Ast.Decl> actors = new LinkedHashMap<>();
        Map<Slot, String> actorOrigins = new LinkedHashMap<>();
        Map<Slot, Set<String>> existingMembers = new LinkedHashMap<>();
        Map<Slot, Binding> states = new LinkedHashMap<>();
        Map<Ast.Decl, String> origins = new IdentityHashMap<>();
        int declarationCount = 0;

        // All declarations must have one provenance, including the actor
        // and the state when they occur in different source units.
        for (Map.Entry<String, Ast.Program> unit : ordered.entrySet()) {
            String namespace = unit.getValue().namespace();
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalArgumentException("attached state requires explicit source namespace");
            }
            for (Ast.ModuleDecl module : unit.getValue().modules()) {
                for (Ast.Decl decl : module.declarations()) {
                    if (++declarationCount > 65536 || decl == null) {
                        throw new IllegalArgumentException("invalid actor state declaration budget");
                    }
                    String prior = origins.putIfAbsent(decl, unit.getKey());
                    if (prior != null && !prior.equals(unit.getKey())) {
                        throw new IllegalArgumentException("ambiguous actor state declaration provenance");
                    }
                    if (!Parser.ROOT_MODULE.equals(module.name())) {
                        if (decl instanceof Ast.ActorStateDecl) {
                            throw new IllegalArgumentException(
                                    "attached actor state cannot be placed in named module");
                        }
                        continue;
                    }
                    String actorName = null;
                    if (decl instanceof Ast.StandaloneActorDecl actor) actorName = actor.name();
                    if (decl instanceof Ast.ClassDecl klass
                            && klass.actorKind() != Ast.ActorKind.NONE) actorName = klass.name();
                    if (actorName == null) continue;
                    Slot slot = new Slot(packageIdentity, namespace, actorName);
                    if (actors.putIfAbsent(slot, decl) != null) {
                        throw new IllegalArgumentException(
                                "duplicate namespace actor identity for attached state: " + actorName);
                    }
                    actorOrigins.put(slot, unit.getKey());
                    Set<String> members = new LinkedHashSet<>();
                    if (decl instanceof Ast.ClassDecl klass) {
                        for (Ast.FieldDecl field : klass.fields()) members.add(field.name());
                        for (Ast.MethodDecl method : klass.methods()) {
                            if (!method.isStatic()) members.add(method.name());
                        }
                    }
                    existingMembers.put(slot, members);
                }
            }
        }

        for (Map.Entry<String, Ast.Program> unit : ordered.entrySet()) {
            String namespace = unit.getValue().namespace();
            for (Ast.ModuleDecl module : unit.getValue().modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.ExternalActorMethodDecl method) {
                        Slot slot = new Slot(packageIdentity, namespace, method.actorName());
                        existingMembers.computeIfAbsent(
                                slot, ignored -> new LinkedHashSet<>()).add(method.name());
                    }
                }
            }
        }

        for (Map.Entry<String, Ast.Program> unit : ordered.entrySet()) {
            String namespace = unit.getValue().namespace();
            for (Ast.ModuleDecl module : unit.getValue().modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl decl : module.declarations()) {
                    if (!(decl instanceof Ast.ActorStateDecl state)) continue;
                    Slot slot = new Slot(packageIdentity, namespace, state.actorName());
                    Ast.Decl actor = actors.get(slot);
                    if (actor == null) {
                        throw new IllegalArgumentException(
                                "attached state has no nominal actor '" + state.actorName()
                                        + "' in namespace '" + namespace + "'");
                    }
                    if (actor instanceof Ast.StandaloneActorDecl standalone
                            && !standalone.implementedContracts().isEmpty()) {
                        // The contract/namespace linker must validate these
                        // obligations; a state schema is never a witness.
                        throw new IllegalArgumentException(
                                "ACTOR_CONTRACT_WITNESS_UNSUPPORTED: attached state cannot "
                                        + "satisfy a named actor contract");
                    }
                    if (actor instanceof Ast.ClassDecl) {
                        // This v1 state schema is exclusive to the newly
                        // declared, out-of-line actor representation. In-class
                        // actors use a separate layout/runtime ownership path.
                        throw new IllegalArgumentException(
                                "attached actor state requires a 'declare actor' header; "
                                        + "inline actor definitions cannot acquire an out-of-line state schema");
                    }
                    Set<String> members = existingMembers.getOrDefault(slot, Set.of());
                    for (Ast.ProtectedStateField field : state.fields()) {
                        if (!field.type().name().equals("int")
                                || !field.type().arguments().isEmpty()
                                || field.type().isBorrow() || field.type().inferArguments()
                                || !(field.initializer() instanceof Ast.LiteralExpr literal)
                                || !(literal.value() instanceof Long)
                                || field.name().equals("state")
                                || field.name().equals("handle_state")) {
                            throw new IllegalArgumentException(
                                    "attached actor state field requires signed int literal and sealed name");
                        }
                        if (members.contains(field.name())) {
                            throw new IllegalArgumentException(
                                    "attached state field '" + field.name()
                                            + "' conflicts with actor method or legacy field");
                        }
                    }
                    if (states.putIfAbsent(slot,
                            new Binding(actorOrigins.get(slot), unit.getKey(), state)) != null) {
                        throw new IllegalArgumentException(
                                "duplicate attached state schema on actor '" + state.actorName() + "'");
                    }
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }

    private static boolean validSourceId(String path) {
        if (path == null || path.isBlank() || path.startsWith("/")
                || path.indexOf('\\') >= 0
                || (!path.endsWith(".atto") && !path.endsWith(".ores"))) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) return false;
        }
        return true;
    }
}
