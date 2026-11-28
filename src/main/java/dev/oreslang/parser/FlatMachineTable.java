package dev.oreslang.parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.OptionalInt;
import java.util.TreeSet;

/** Graph-local event slots only; not nominal event typing, persistent IDs or runtime authority. */
public final class FlatMachineTable {
    public record Edge(String event, int target) {}
    public record Node(String name, boolean terminal, List<Edge> edges) {
        public Node { edges = List.copyOf(edges); }
    }
    public record Transition(int eventSlot, int target) {}
    public record Row(boolean terminal, List<Transition> transitions) {
        public Row { transitions = List.copyOf(transitions); }
    }

    private static final int LIMIT = 256;
    private static final Comparator<String> ORDER = (a, b) -> Arrays.compareUnsigned(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    private final int initial;
    private final List<String> names;
    private final List<String> events;
    private final List<Row> rows;

    private FlatMachineTable(int initial, List<String> names, List<String> events, List<Row> rows) {
        this.initial = initial;
        this.names = List.copyOf(names);
        this.events = List.copyOf(events);
        this.rows = List.copyOf(rows);
    }

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalArgumentException("E_MACHINE_TABLE: " + reason);
    }

    private static boolean validName(String name) {
        return name != null && !name.isEmpty()
                && StandardCharsets.UTF_8.newEncoder().canEncode(name);
    }

    public static FlatMachineTable verify(int initial, List<Node> nodes) {
        nodes = List.copyOf(nodes);
        require(!nodes.isEmpty() && nodes.size() <= LIMIT, "node bound");
        require(initial >= 0 && initial < nodes.size(), "initial");
        var names = new ArrayList<String>();
        var eventNames = new TreeSet<String>(ORDER);
        int count = 0;
        for (Node node : nodes) {
            require(validName(node.name()), "node name");
            require(names.isEmpty() || ORDER.compare(names.get(names.size() - 1), node.name()) < 0, "node order");
            names.add(node.name());
            require(!node.terminal() || node.edges().isEmpty(), "terminal edges");
            String prev = null;
            for (Edge edge : node.edges()) {
                require(++count <= LIMIT, "edge bound");
                require(validName(edge.event()), "event name");
                require(prev == null || ORDER.compare(prev, edge.event()) < 0, "event order");
                require(edge.target() >= 0 && edge.target() < nodes.size(), "target");
                eventNames.add(edge.event());
                prev = edge.event();
            }
        }
        var pending = new ArrayDeque<Integer>();
        var seen = new boolean[nodes.size()];
        pending.push(initial);
        int visited = 0;
        while (!pending.isEmpty()) {
            int node = pending.pop();
            if (seen[node]) continue;
            seen[node] = true;
            visited++;
            for (Edge edge : nodes.get(node).edges()) pending.push(edge.target());
        }
        require(visited == nodes.size(), "unreachable node");
        var events = List.copyOf(eventNames);
        var slots = new HashMap<String, Integer>();
        for (int i = 0; i < events.size(); i++) slots.put(events.get(i), i);
        var rows = new ArrayList<Row>();
        for (Node node : nodes) {
            var edges = new ArrayList<Transition>();
            for (Edge edge : node.edges()) edges.add(new Transition(slots.get(edge.event()), edge.target()));
            rows.add(new Row(node.terminal(), edges));
        }
        return new FlatMachineTable(initial, names, events, rows);
    }

    public int initial() { return initial; }
    public List<String> names() { return names; }
    public List<String> events() { return events; }
    public List<Row> rows() { return rows; }

    /** Pure inspection, not permission to deliver a message or mutate an actor. */
    public OptionalInt successor(int node, int slot) {
        require(node >= 0 && node < rows.size(), "node index");
        require(slot >= 0 && slot < events.size(), "event slot");
        for (Transition edge : rows.get(node).transitions()) {
            if (edge.eventSlot() == slot) return OptionalInt.of(edge.target());
        }
        return OptionalInt.empty();
    }
}
