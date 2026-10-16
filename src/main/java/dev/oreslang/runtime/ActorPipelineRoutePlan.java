package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Trusted host-side typed actor-DAG routing plan. Does NOT send a guest
 * message, convey an ActorRef capability or lower self.forward_* syntax.
 *
 * A scheduler must authenticate the source instance, current graph generation,
 * payload ownership/type and mailbox credits before using a selected edge.
 */
public final class ActorPipelineRoutePlan {
    public enum Role { READABLE, TRANSFORM, WRITABLE, DUPLEX }

    public record Stage(
            String instanceId, String actorType, Role role,
            String inbound, String outbound) {
        public Stage {
            Objects.requireNonNull(instanceId);
            Objects.requireNonNull(actorType);
            Objects.requireNonNull(role);
            Objects.requireNonNull(inbound);
            Objects.requireNonNull(outbound);
        }
    }

    public record Edge(String source, String target, String payloadType) {
        public Edge {
            Objects.requireNonNull(source);
            Objects.requireNonNull(target);
            Objects.requireNonNull(payloadType);
        }
    }

    private static final int MAX_STAGES = 1024;
    private static final int MAX_EDGES = 4096;
    private static final int MAX_NAME_BYTES = 256;
    private static final int MAX_KEY_BYTES = 4096;
    private final List<Edge> edges;
    private final Map<String, List<Edge>> outgoing;
    private final Map<String, Integer> cursors = new HashMap<>();

    public ActorPipelineRoutePlan(List<Stage> stages, List<Edge> edges) {
        Objects.requireNonNull(stages);
        Objects.requireNonNull(edges);
        if (stages.size() < 2 || stages.size() > MAX_STAGES
                || edges.isEmpty() || edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("pipeline graph stage/edge budget invalid");
        }

        Map<String, Stage> byId = new HashMap<>();
        for (Stage stage : stages) {
            if (stage.instanceId().isEmpty() || stage.actorType().isEmpty()
                    || stage.instanceId().length() > MAX_NAME_BYTES
                    || stage.actorType().length() > MAX_NAME_BYTES
                    || byId.putIfAbsent(stage.instanceId(), stage) != null) {
                throw new IllegalArgumentException("duplicate or invalid pipeline instance ID");
            }
            boolean hasInput = !stage.inbound().isEmpty();
            boolean hasOutput = !stage.outbound().isEmpty();
            if (switch (stage.role()) {
                case READABLE -> hasInput || !hasOutput;
                case WRITABLE -> !hasInput || hasOutput;
                case TRANSFORM, DUPLEX -> !hasInput || !hasOutput;
            }) {
                throw new IllegalArgumentException("pipeline stage has invalid typed ports");
            }
        }

        Map<String, List<Edge>> routes = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        Set<String> pairs = new HashSet<>();
        for (Stage stage : stages) {
            incoming.put(stage.instanceId(), 0);
            indegree.put(stage.instanceId(), 0);
        }
        for (Edge edge : edges) {
            Stage source = byId.get(edge.source());
            Stage target = byId.get(edge.target());
            if (source == null || target == null || source == target) {
                throw new IllegalArgumentException("unknown or self-referential pipeline edge");
            }
            if (source.role() == Role.WRITABLE || target.role() == Role.READABLE
                    || edge.payloadType().isEmpty()
                    || !edge.payloadType().equals(source.outbound())
                    || !edge.payloadType().equals(target.inbound())) {
                throw new IllegalArgumentException("pipeline edge violates nominal payload or direction");
            }
            String pair = edge.source() + "\u0000" + edge.target();
            if (!pairs.add(pair)) {
                throw new IllegalArgumentException("duplicate pipeline connection");
            }
            routes.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            incoming.merge(edge.target(), 1, Integer::sum);
            indegree.merge(edge.target(), 1, Integer::sum);
        }
        for (Stage stage : stages) {
            int reads = incoming.get(stage.instanceId());
            int writes = routes.getOrDefault(stage.instanceId(), List.of()).size();
            if (reads == 0 && writes == 0
                    || stage.role() == Role.READABLE && writes == 0
                    || stage.role() == Role.WRITABLE && reads == 0
                    || stage.role() == Role.TRANSFORM && (reads == 0 || writes == 0)) {
                throw new IllegalArgumentException("pipeline stage missing required connection");
            }
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Stage stage : stages) {
            if (indegree.get(stage.instanceId()) == 0) ready.add(stage.instanceId());
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            String node = ready.removeFirst();
            visited++;
            for (Edge edge : routes.getOrDefault(node, List.of())) {
                int remaining = indegree.merge(edge.target(), -1, Integer::sum);
                if (remaining == 0) ready.addLast(edge.target());
            }
        }
        if (visited != stages.size()) {
            throw new IllegalArgumentException("pipeline graph contains a cycle");
        }

        Map<String, List<Edge>> immutable = new HashMap<>();
        routes.forEach((name, options) -> immutable.put(name, List.copyOf(options)));
        this.outgoing = Map.copyOf(immutable);
        this.edges = List.copyOf(edges);
    }

    public List<Edge> edges() { return edges; }

    private List<Edge> connected(String source) {
        Objects.requireNonNull(source);
        List<Edge> matches = outgoing.get(source);
        if (matches == null || matches.isEmpty()) {
            throw new IllegalArgumentException("source has no connected downstream consumers");
        }
        return matches;
    }

    /** Single destination only: fan-out requires an explicit routing policy. */
    public Edge forward(String source) {
        List<Edge> matches = connected(source);
        if (matches.size() != 1) {
            throw new IllegalArgumentException("ambiguous forward: select an explicit routing policy");
        }
        return matches.getFirst();
    }

    /** Stable immutable ordering; this is a selection plan, not admission. */
    public List<Edge> forwardAll(String source) {
        return connected(source);
    }

    /** Attempt counts even if subsequent mailbox admission fails. */
    public synchronized Edge forwardRoundRobin(String source) {
        List<Edge> matches = connected(source);
        int cursor = cursors.getOrDefault(source, 0);
        Edge selected = matches.get(cursor);
        cursors.put(source, cursor + 1 == matches.size() ? 0 : cursor + 1);
        return selected;
    }

    public Edge forwardTo(String source, String target) {
        Objects.requireNonNull(target);
        for (Edge edge : connected(source)) {
            if (edge.target().equals(target)) return edge;
        }
        throw new IllegalArgumentException("destination is not connected to source");
    }

    /** FNV-1a over strict UTF-8 bytes; deterministic, not authorization. */
    public Edge forwardByKey(String source, String key) {
        Objects.requireNonNull(key);
        ByteBuffer bytes;
        try {
            bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(key));
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("malformed routing key", malformed);
        }
        if (bytes.remaining() > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("routing key exceeds byte limit");
        }
        long hash = 0xcbf29ce484222325L;
        while (bytes.hasRemaining()) {
            hash ^= bytes.get() & 0xffL;
            hash *= 0x100000001b3L;
        }
        List<Edge> matches = connected(source);
        return matches.get((int) Long.remainderUnsigned(hash, matches.size()));
    }
}
