package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole-package, closed-source-set namespace conformance admission.
 *
 * A namespace is not an ambient host directory. Only the source units
 * explicitly supplied to the one IncrementalCompiler.compile invocation
 * are eligible, and they must declare the same flat namespace header.
 *
 * This proof MUST run before incremental cache reuse, so deleting the
 * last actor witness cannot retain previously accepted namespace state.
 */
final class NamespaceContractChecker {
    private NamespaceContractChecker() { }

    static void validate(Map<String, Ast.Program> sourceUnits) {
        Map<String, Map<String, Ast.Program>> grouped = namespaces(sourceUnits);
        for (Map.Entry<String, Map<String, Ast.Program>> entry : grouped.entrySet()) {
            validateGroup(entry.getKey(), entry.getValue());
        }
    }

    private static Map<String, Map<String, Ast.Program>> namespaces(
            Map<String, Ast.Program> units) {
        Map<String, Map<String, Ast.Program>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Program> entry : units.entrySet()) {
            Ast.Program program = entry.getValue();
            if (program.namespace() == null) continue;
            grouped.computeIfAbsent(program.namespace(), unused -> new LinkedHashMap<>())
                    .put(entry.getKey(), program);
        }
        return grouped;
    }

    private static void validateGroup(String namespace, Map<String, Ast.Program> units) {
        Map<String, Ast.InterfaceDecl> contracts = new LinkedHashMap<>();
        Map<String, Ast.ClassDecl> actors = new LinkedHashMap<>();
        Map<String, Ast.FunctionDecl> functions = new LinkedHashMap<>();
        Set<String> topLevelTypes = new LinkedHashSet<>();
        Map<String, String> valueKinds = new LinkedHashMap<>();
        List<Ast.TypeRef> claimed = List.of();
        String ownerFile = null;
        for (Map.Entry<String, Ast.Program> unit : units.entrySet()) {
            Ast.Program program = unit.getValue();
            if (!program.namespaceContracts().isEmpty()) {
                if (ownerFile != null) {
                    throw new IllegalArgumentException(
                            "namespace '" + namespace + "' has multiple conformance headers in '"
                                    + ownerFile + "' and '" + unit.getKey() + "'");
                }
                ownerFile = unit.getKey();
                claimed = program.namespaceContracts();
            }
            for (Ast.ModuleDecl module : program.modules()) {
                if (!module.name().equals("__root__")) continue;
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.InterfaceDecl iface) {
                        if (!topLevelTypes.add(iface.name())) {
                            throw duplicate(namespace, iface.name(), "type");
                        }
                        if (iface.namespaceContract()
                                && contracts.putIfAbsent(iface.name(), iface) != null) {
                            throw duplicate(namespace, iface.name(), "namespace contract");
                        }
                    } else if (decl instanceof Ast.ClassDecl klass) {
                        if (!topLevelTypes.add(klass.name())) {
                            throw duplicate(namespace, klass.name(), "type");
                        }
                        if (valueKinds.putIfAbsent(klass.name(), "class/actor") != null) {
                            throw duplicate(namespace, klass.name(), "runtime value");
                        }
                        if (klass.actorKind() != Ast.ActorKind.NONE
                                && actors.putIfAbsent(klass.name(), klass) != null) {
                            throw duplicate(namespace, klass.name(), "actor");
                        }
                    } else if (decl instanceof Ast.TypeAliasDecl alias) {
                        if (!topLevelTypes.add(alias.name())) {
                            throw duplicate(namespace, alias.name(), "type");
                        }
                    } else if (decl instanceof Ast.FunctionDecl fn) {
                        String previousKind = valueKinds.putIfAbsent(fn.name(), "callable");
                        if (previousKind != null && !previousKind.equals("callable")) {
                            throw duplicate(namespace, fn.name(), "runtime value");
                        }
                        String key = fn.name() + "#" + fn.parameters().size();
                        if (functions.putIfAbsent(key, fn) != null) {
                            throw duplicate(namespace, key, "function slot");
                        }
                    } else if (decl instanceof Ast.FieldDecl field) {
                        if (valueKinds.putIfAbsent(field.name(), "field") != null) {
                            throw duplicate(namespace, field.name(), "runtime value");
                        }
                    }
                }
            }
        }
        // No declaration makes a contract self-executing. Require an
        // explicit, unique named header in exactly one trusted source unit.
        Set<String> seen = new LinkedHashSet<>();
        for (Ast.TypeRef ref : claimed) {
            if (ref.inferArguments() || !ref.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "namespace '" + namespace + "' contract arguments are not supported: "
                                + ref.name());
            }
            if (!seen.add(ref.name())) {
                throw new IllegalArgumentException(
                        "namespace '" + namespace + "' repeats contract '" + ref.name() + "'");
            }
            Ast.InterfaceDecl required = contracts.get(ref.name());
            if (required == null) {
                throw new IllegalArgumentException(
                        "namespace '" + namespace + "' requires local 'for namespace' contract '"
                                + ref.name() + "' in the complete source set");
            }
            if (!required.genericParameters().isEmpty() || !required.parents().isEmpty()) {
                throw new IllegalArgumentException(
                        "namespace contract '" + required.name()
                                + "' cannot use generics or inheritance before linked proof lowering");
            }
            verifyRequirements(namespace, required, actors, functions);
        }
    }

    private static void verifyRequirements(
            String namespace, Ast.InterfaceDecl contract,
            Map<String, Ast.ClassDecl> actors,
            Map<String, Ast.FunctionDecl> functions) {
        for (Ast.InterfaceMember member : contract.members()) {
            if (member instanceof Ast.InterfaceActorDecl required) {
                Ast.ClassDecl witness = actors.get(required.name());
                if (witness == null || witness.visibility() != Ast.Visibility.PUBLIC
                        || !witness.genericParameters().isEmpty()
                        || witness.standaloneBinding() != null) {
                    throw new IllegalArgumentException(
                            "namespace '" + namespace + "' is missing public non-generic actor '"
                                    + required.name() + "' required by '" + contract.name() + "'");
                }
            } else if (member instanceof Ast.InterfaceFunctionDecl required) {
                if (required.structural() || !required.genericParameters().isEmpty()
                        || !required.effects().isEmpty()) {
                    throw new IllegalArgumentException(
                            "namespace contract function '" + required.name()
                                    + "' requires unsupported generic/structural/effect lowering");
                }
                Ast.FunctionDecl witness = functions.get(
                        required.name() + "#" + required.parameters().size());
                if (witness == null || witness.visibility() != Ast.Visibility.PUBLIC
                        || witness.kind() != Ast.CallableKind.FNC
                        || witness.actorKind() != Ast.ActorKind.NONE
                        || witness.async() || witness.suspends()
                        || witness.generator() || witness.trapped()
                        || witness.structural() || witness.nonLexical()
                        || !witness.genericParameters().isEmpty()
                        || !witness.effects().isEmpty()
                        || !witness.returnType().equals(required.returnType())
                        || witness.parameters().size() != required.parameters().size()) {
                    throw new IllegalArgumentException(
                            "namespace '" + namespace + "' is missing compatible public fnc '"
                                    + required.name() + "' required by '" + contract.name() + "'");
                }
                for (int i = 0; i < required.parameters().size(); i++) {
                    Ast.Param param = required.parameters().get(i);
                    Ast.Param actual = witness.parameters().get(i);
                    if (!param.type().equals(actual.type())
                            || param.mutable() != actual.mutable()
                            || param.structural() || actual.structural()) {
                        throw new IllegalArgumentException(
                                "namespace '" + namespace + "' function '" + required.name()
                                        + "' has incompatible parameter " + (i + 1));
                    }
                }
            } else if (member instanceof Ast.InterfaceActorMethodDecl) {
                throw new IllegalArgumentException(
                        "namespace contract external receiver methods require multi-file "
                                + "actor method lowering; use a dedicated actor contract");
            } else {
                throw new IllegalArgumentException(
                        "namespace contract '" + contract.name()
                                + "' supports only actor and fnc requirements in stage one");
            }
        }
    }

    static Map<String, Set<String>> addNamespaceDependencies(
            Map<String, Ast.Program> units, Map<String, Set<String>> original) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : original.entrySet()) {
            result.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        for (Map<String, Ast.Program> group : namespaces(units).values()) {
            boolean contracted = group.values().stream().anyMatch(
                    p -> !p.namespaceContracts().isEmpty());
            if (!contracted) continue;
            // Conservative namespace ABI closure: a sibling's export change
            // rebuilds every participating unit. No arbitrary filesystem scan.
            for (String id : group.keySet()) {
                Set<String> links = result.computeIfAbsent(id, unused -> new LinkedHashSet<>());
                for (String peer : group.keySet()) if (!peer.equals(id)) links.add(peer);
            }
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        result.forEach((id, linked) -> frozen.put(id, Set.copyOf(linked)));
        return Map.copyOf(frozen);
    }

    private static IllegalArgumentException duplicate(String namespace, String name, String kind) {
        return new IllegalArgumentException(
                "namespace '" + namespace + "' has duplicate " + kind + " '" + name
                        + "' across file roots");
    }
}
