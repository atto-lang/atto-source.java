package atto.compiler.pipe;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler-owned, pre-HIR DATA edge admission for continuous {@code pipe} and
 * {@code rpipe}. This is not a guest scheduler, transport, PipeHandle, or
 * capability broker. Actors MUST obtain input from declared {@code receives T}
 * and output from declared {@code emits T}: {@code sends T} is not a source.
 *
 * A trusted frontend supplies nominally resolved, already authorized endpoint
 * descriptors. No untrusted application can grant itself tenant capabilities
 * by constructing one of these records.
 */
public final class AttoDirectedPipePlan {
    public enum Direction { PIPE, RPIPE }
    public enum Endpoint { ACTOR, CHANNEL, STREAM_READABLE, STREAM_SINK, STREAM_DUPLEX }
    public record Stage(String id, String tenant, Endpoint endpoint, String receives, String emits) {
        public Stage {
            id = label(id, "E_PIPE_ID");
            tenant = label(tenant, "E_PIPE_TENANT_ID");
            Objects.requireNonNull(endpoint, "E_PIPE_ENDPOINT");
            receives = type(receives);
            emits = type(emits);
            if (receives == null && emits == null)
                throw new IllegalArgumentException("E_PIPE_PORTS");
            switch (endpoint) {
                case STREAM_READABLE -> {
                    if (receives != null || emits == null)
                        throw new IllegalArgumentException("E_PIPE_READONLY_PORT");
                }
                case STREAM_SINK -> {
                    if (receives == null || emits != null)
                        throw new IllegalArgumentException("E_PIPE_WRITEONLY_PORT");
                }
                case CHANNEL, STREAM_DUPLEX -> {
                    if (receives == null || emits == null || !receives.equals(emits))
                        throw new IllegalArgumentException("E_PIPE_CHANNEL_PORTS");
                }
                case ACTOR -> { /* actor roles come from declarations, not sends */ }
            }
        }
        public static Stage actor(String id, String tenant, String receives, String emits) {
            return new Stage(id, tenant, Endpoint.ACTOR, receives, emits);
        }
        public static Stage channel(String id, String tenant, String itemType) {
            return new Stage(id, tenant, Endpoint.CHANNEL, itemType, itemType);
        }
        public static Stage readable(String id, String tenant, String itemType) {
            return new Stage(id, tenant, Endpoint.STREAM_READABLE, null, itemType);
        }
        public static Stage sink(String id, String tenant, String itemType) {
            return new Stage(id, tenant, Endpoint.STREAM_SINK, itemType, null);
        }
        public static Stage duplex(String id, String tenant, String itemType) {
            return new Stage(id, tenant, Endpoint.STREAM_DUPLEX, itemType, itemType);
        }
    }
    public record Edge(int sourceWrittenIndex, int sinkWrittenIndex,
                       String source, String sink, String itemType) {}
    public record Plan(Direction direction, List<Stage> writtenStages,
                       List<Integer> flowIndices, List<Edge> dataEdges, String tenant) {
        public Plan {
            Objects.requireNonNull(direction);
            writtenStages = List.copyOf(writtenStages);
            flowIndices = List.copyOf(flowIndices);
            dataEdges = List.copyOf(dataEdges);
        }
    }
    private AttoDirectedPipePlan() {}

    private static String label(String raw, String code) {
        if (raw == null || raw.isEmpty() || raw.length() > 256 ||
            raw.getBytes(StandardCharsets.UTF_8).length > 256 ||
            !raw.matches("[A-Za-z_][A-Za-z0-9_-]*"))
            throw new IllegalArgumentException(code);
        return raw;
    }
    private static String type(String raw) {
        if (raw == null) return null;
        if (raw.length() > 256 || raw.isEmpty() ||
            !raw.matches("[A-Za-z_][A-Za-z0-9_.<>]*") ||
            raw.getBytes(StandardCharsets.UTF_8).length > 256)
            throw new IllegalArgumentException("E_PIPE_TYPE_LABEL");
        return raw;
    }

    /**
     * Validate all directed DATA edges without re-evaluating a single endpoint.
     * Index order is reversed for RPIPE, but writtenStages never moves.
     */
    public static Plan plan(List<Stage> writtenStages, Direction direction) {
        Objects.requireNonNull(writtenStages, "E_PIPE_STAGES");
        Objects.requireNonNull(direction, "E_PIPE_DIRECTION");
        if (writtenStages.size() < 2 || writtenStages.size() > 16)
            throw new IllegalArgumentException("E_PIPE_STAGE_LIMIT");
        String tenant = null;
        Set<String> identities = new HashSet<>();
        for (Stage stage : writtenStages) {
            Objects.requireNonNull(stage, "E_PIPE_STAGE");
            if (tenant != null && !tenant.equals(stage.tenant()))
                throw new IllegalArgumentException("E_PIPE_TENANT_BOUNDARY");
            tenant = stage.tenant();
            if (!identities.add(stage.id()))
                throw new IllegalArgumentException("E_PIPE_DUPLICATE_STAGE");
        }
        List<Integer> flow = new ArrayList<>(writtenStages.size());
        for (int i = 0; i < writtenStages.size(); i++)
            flow.add(direction == Direction.PIPE ? i : writtenStages.size() - i - 1);
        List<Edge> edges = new ArrayList<>(writtenStages.size() - 1);
        for (int i = 0; i + 1 < flow.size(); i++) {
            int from = flow.get(i), to = flow.get(i + 1);
            Stage source = writtenStages.get(from), sink = writtenStages.get(to);
            if (source.emits() == null)
                throw new IllegalArgumentException("E_PIPE_MISSING_EMITS");
            if (sink.receives() == null)
                throw new IllegalArgumentException("E_PIPE_MISSING_RECEIVES");
            if (!source.emits().equals(sink.receives()))
                throw new IllegalArgumentException("E_PIPE_TYPE_MISMATCH");
            edges.add(new Edge(from, to, source.id(), sink.id(), source.emits()));
        }
        return new Plan(direction, writtenStages, flow, edges, tenant);
    }

    /**
     * Cross-expression unicast admission. Two spellings of the same connection
     * are two admission attempts, never an implicit duplex or fan-out.
     * Runtime must independently make admission atomic, checked and reversible.
     */
    public static void check_unicast_attachments(List<Plan> plans) {
        Objects.requireNonNull(plans, "E_PIPE_PLANS");
        if (plans.size() > 64) throw new IllegalArgumentException("E_PIPE_PLAN_LIMIT");
        Set<String> outputs = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (Plan p : plans) {
            Objects.requireNonNull(p, "E_PIPE_PLAN");
            for (Edge edge : p.dataEdges()) {
                String source = p.tenant() + "\u0000" + edge.source();
                String pair = source + "\u0000" + edge.sink();
                if (!pairs.add(pair))
                    throw new IllegalArgumentException("E_PIPE_DUPLICATE_EDGE");
                if (!outputs.add(source))
                    throw new IllegalArgumentException("E_PIPE_OUTPUT_OCCUPIED");
            }
        }
    }
}
