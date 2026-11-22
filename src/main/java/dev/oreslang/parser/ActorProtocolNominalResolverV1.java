package dev.oreslang.parser;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Source-local, compiler-owned nominal response marker resolver. It consumes
 * declarations from the production Parser AST, not a user-supplied reply map.
 * This is a typed declaration preflight; NO stream handle, reply slot or AOT
 * execution permission is produced.
 */
public final class ActorProtocolNominalResolverV1 {
    private ActorProtocolNominalResolverV1() { }

    private static final int MAX_NOMINALS = 128;
    private static final int MAX_PARENTS = 16;
    private static final int MAX_DEPTH = 16;
    private static final Set<String> MARKERS =
            Set.of("OneWayMessage", "UnaryRequest", "StreamRequest");
    private static final Set<String> SCALARS =
            Set.of("int", "bool", "float", "f64", "String", "string", "Bytes");

    /** Immutable compiler IR describing validated source-local declarations. */
    public record ResolvedDeclarations(
            ActorProtocolTypestateV1.Catalog protocol,
            Map<String, ActorProtocolTypestateV1.NominalMessage> messages,
            List<ActorProtocolTypestateV1.ActorBinding> actors) {
        public ResolvedDeclarations {
            protocol = Objects.requireNonNull(protocol);
            messages = Map.copyOf(messages);
            actors = List.copyOf(actors);
        }
    }

    private record Nominal(String name, List<Ast.TypeRef> parents,
                           boolean concrete, boolean actor, boolean generic) {
        private Nominal {
            parents = List.copyOf(parents);
        }
    }
    private enum ResponseKind { ONE_WAY, UNARY, STREAM }
    private record Response(ResponseKind kind, String type) { }

    private static IllegalArgumentException error(String code, String detail) {
        return new IllegalArgumentException(code + ": " + detail);
    }

    /**
     * All type names in this first slice must be unambiguous and source-local.
     * Imported aliases, generic message declarations and foreign type graphs
     * require verified linker metadata before becoming admissible.
     */
    public static ResolvedDeclarations resolve(
            ActorProtocolTypestateV1.Catalog catalog, Ast.Program program) {
        Objects.requireNonNull(catalog, "protocol catalog");
        Objects.requireNonNull(program, "parsed program");
        Map<String, Nominal> nodes = new LinkedHashMap<>();
        Set<String> aliases = new LinkedHashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) {
                    List<Ast.TypeRef> parents = new ArrayList<>(klass.parents());
                    parents.addAll(klass.interfaces());
                    insert(nodes, klass.name(), parents, true,
                            klass.actorKind() != Ast.ActorKind.NONE,
                            !klass.genericParameters().isEmpty());
                } else if (declaration instanceof Ast.InterfaceDecl iface) {
                    if (iface.moduleContract())
                        throw error("E_PROTOCOL_UNVERIFIED_MESSAGE",
                                "module contracts cannot mint response markers");
                    insert(nodes, iface.name(), iface.parents(), false, false,
                            !iface.genericParameters().isEmpty());
                } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    if (MARKERS.contains(alias.name()))
                        throw error("E_PROTOCOL_FORGED_MARKER",
                                "cannot redefine compiler marker " + alias.name());
                    if (!aliases.add(alias.name()))
                        throw error("E_PROTOCOL_AMBIGUOUS_TYPE", alias.name());
                }
            }
        }
        for (String alias : aliases)
            if (nodes.containsKey(alias))
                throw error("E_PROTOCOL_AMBIGUOUS_TYPE", alias);
        if (nodes.size() + aliases.size() > MAX_NOMINALS)
            throw error("E_PROTOCOL_LIMIT", "nominal symbol budget exceeded");

        Map<String, ActorProtocolTypestateV1.NominalMessage> validated =
                new LinkedHashMap<>();
        // Memoize completed nominal modes. Shared diamond inheritance must be
        // O(nodes + edges), never exponential in the number of paths.
        Map<String, Response> memo = new LinkedHashMap<>();
        // One nominal message class has one payload schema even when multiple
        // protocol states admit it. A state-dependent payload would require
        // a different nominal message kind or an explicitly checked ADT.
        Map<String, String> messagePayloads = new LinkedHashMap<>();
        for (ActorProtocolTypestateV1.Protocol protocol : catalog.protocols()) {
            if (nodes.containsKey(protocol.name()) || aliases.contains(protocol.name())
                    || MARKERS.contains(protocol.name()))
                throw error("E_PROTOCOL_AMBIGUOUS_TYPE",
                        "protocol name collides with nominal type " + protocol.name());
            for (ActorProtocolTypestateV1.Transition transition : protocol.transitions()) {
                if (messagePayloads.containsKey(transition.message())
                        && !Objects.equals(messagePayloads.get(transition.message()),
                                transition.parameterType()))
                    throw error("E_PROTOCOL_MESSAGE_PAYLOAD",
                            "same message has incompatible payload schemas in protocol states");
                messagePayloads.put(transition.message(), transition.parameterType());
                checkConcreteType(transition.parameterType(), nodes, aliases, true);
                checkConcreteType(transition.responseType(), nodes, aliases, false);
                Nominal node = nodes.get(transition.message());
                if (node == null || !node.concrete() || node.actor() || node.generic())
                    throw error("E_PROTOCOL_UNVERIFIED_MESSAGE",
                            "message must name a concrete non-generic class: "
                                    + transition.message());
                Response marker = inheritedMode(node.name(), nodes,
                        new LinkedHashSet<>(), memo, 0);
                if (marker == null)
                    throw error("E_PROTOCOL_UNVERIFIED_MESSAGE",
                            "missing explicit nominal response marker on "
                                    + transition.message());
                if (marker.kind() == ResponseKind.ONE_WAY)
                    throw error("E_PROTOCOL_RESPONSE_MODE",
                            "one-way message cannot implement a unary/stream transition");
                ActorProtocolTypestateV1.Mode mode =
                        marker.kind() == ResponseKind.UNARY
                                ? ActorProtocolTypestateV1.Mode.UNARY
                                : ActorProtocolTypestateV1.Mode.STREAM;
                var resolved = new ActorProtocolTypestateV1.NominalMessage(mode, marker.type());
                var previous = validated.putIfAbsent(transition.message(), resolved);
                if (previous != null && !previous.equals(resolved))
                    throw error("E_PROTOCOL_RESPONSE_MODE",
                            "conflicting nominal reply modes on " + transition.message());
            }
        }
        catalog.verifyResolvedMessages(validated);

        // Only actual AST actor declarations, not lexical occurrences of
        // "define actor" inside a method or a comment, can bind protocols.
        Set<String> protocolNames = new LinkedHashSet<>();
        for (var p : catalog.protocols()) protocolNames.add(p.name());
        List<ActorProtocolTypestateV1.ActorBinding> bindings = new ArrayList<>();
        for (Nominal nominal : nodes.values()) {
            if (!nominal.actor()) continue;
            for (Ast.TypeRef parent : nominal.parents()) {
                if (!protocolNames.contains(parent.name())) continue;
                if (nominal.generic() || parent.inferArguments()
                        || !parent.arguments().isEmpty())
                    throw error("E_PROTOCOL_UNVERIFIED_MESSAGE",
                            "protocol actor bindings must be monomorphic");
                bindings.add(new ActorProtocolTypestateV1.ActorBinding(
                        nominal.name(), parent.name()));
            }
        }
        if (bindings.size() > MAX_NOMINALS)
            throw error("E_PROTOCOL_LIMIT", "too many protocol actor bindings");
        return new ResolvedDeclarations(catalog, validated, bindings);
    }

    private static void insert(Map<String, Nominal> nodes,
                               String name, List<Ast.TypeRef> parents,
                               boolean concrete, boolean actor, boolean generic) {
        if (MARKERS.contains(name))
            throw error("E_PROTOCOL_FORGED_MARKER", name);
        if (nodes.size() >= MAX_NOMINALS)
            throw error("E_PROTOCOL_LIMIT", "nominal declaration limit exceeded");
        if (parents.size() > MAX_PARENTS)
            throw error("E_PROTOCOL_LIMIT", "nominal inheritance fanout exceeded");
        if (nodes.putIfAbsent(name,
                new Nominal(name, parents, concrete, actor, generic)) != null)
            throw error("E_PROTOCOL_AMBIGUOUS_TYPE", name);
    }

    private static void checkConcreteType(
            String name, Map<String, Nominal> nodes, Set<String> aliases, boolean payload) {
        if (name == null) return;
        if (name.isBlank() || Set.of("void", "null", "any", "Any", "_").contains(name)
                || MARKERS.contains(name) || aliases.contains(name))
            throw error("E_PROTOCOL_UNVERIFIED_TYPE", "erased/aliased " + name);
        if (SCALARS.contains(name)) return;
        Nominal node = nodes.get(name);
        if (node == null || !node.concrete() || node.actor() || node.generic())
            throw error("E_PROTOCOL_UNVERIFIED_TYPE",
                    (payload ? "payload " : "response ") + name
                            + " lacks a source-local concrete type");
    }

    private static Response inheritedMode(
            String name, Map<String, Nominal> nodes, Set<String> active,
            Map<String, Response> memo, int depth) {
        if (memo.containsKey(name)) return memo.get(name);
        if (depth > MAX_DEPTH)
            throw error("E_PROTOCOL_LIMIT", "nominal inheritance depth exceeded");
        if (!active.add(name))
            throw error("E_PROTOCOL_CYCLE", "nominal inheritance cycle through " + name);
        Nominal n = nodes.get(name);
        if (n == null)
            throw error("E_PROTOCOL_UNVERIFIED_MESSAGE",
                    "unlinked nominal parent " + name);
        Response mode = null;
        for (Ast.TypeRef parent : n.parents()) {
            Response candidate = marker(parent, nodes);
            if (candidate == null) {
                if (parent.name().equals("Object") && parent.arguments().isEmpty()) continue;
                if (parent.inferArguments() || !parent.arguments().isEmpty())
                    throw error("E_PROTOCOL_UNVERIFIED_MESSAGE",
                            "generic parent needs linked specialization: " + parent.name());
                candidate = inheritedMode(parent.name(), nodes, active, memo, depth + 1);
            }
            if (candidate == null) continue;
            if (mode != null && !mode.equals(candidate))
                throw error("E_PROTOCOL_RESPONSE_MODE",
                        "incompatible inherited response markers on " + name);
            mode = candidate;
        }
        active.remove(name);
        memo.put(name, mode);
        return mode;
    }

    private static Response marker(Ast.TypeRef ref, Map<String, Nominal> nodes) {
        String name = ref.name();
        if (!MARKERS.contains(name)) return null;
        if (ref.inferArguments())
            throw error("E_PROTOCOL_UNVERIFIED_TYPE", "marker cannot infer generic arguments");
        if (name.equals("OneWayMessage")) {
            if (!ref.arguments().isEmpty())
                throw error("E_PROTOCOL_RESPONSE_TYPE", "OneWayMessage takes no response type");
            return new Response(ResponseKind.ONE_WAY, null);
        }
        if (ref.arguments().size() != 1)
            throw error("E_PROTOCOL_RESPONSE_TYPE", name + " requires exactly one reply/item type");
        Ast.TypeRef payload = ref.arguments().getFirst();
        if (payload.inferArguments() || !payload.arguments().isEmpty())
            throw error("E_PROTOCOL_UNVERIFIED_TYPE", "nested/erased marker type is unsupported");
        checkConcreteType(payload.name(), nodes, Set.of(), false);
        return new Response(name.equals("UnaryRequest")
                ? ResponseKind.UNARY : ResponseKind.STREAM, payload.name());
    }
}
