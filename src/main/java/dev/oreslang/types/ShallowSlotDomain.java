package dev.oreslang.types;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded, serialized ownership substrate for shallow copy and slot replacement.
 * All graph writes go through this domain; Node is an opaque identity, not a
 * writable host container. This is not yet wired into the guest evaluator.
 */
public final class ShallowSlotDomain {
    public static final class Node {
        private final boolean dynamic;
        private final boolean noCopy;
        private final Map<String, Slot> slots;
        private Node(boolean dynamic, boolean noCopy, Map<String, Slot> slots) {
            this.dynamic = dynamic;
            this.noCopy = noCopy;
            this.slots = slots;
        }
    }
    public static final class Owner { private Owner() { } }
    public static final class Loan { private Loan() { } }
    private record Slot(Object value, boolean writable) { }
    private record Root(Node node, Set<Node> reach) { }
    private record Edit(Node node, String key, Slot next) { }
    private final Map<Owner, Root> roots = new IdentityHashMap<>();
    private final Map<Loan, Set<Node>> loans = new IdentityHashMap<>();
    private final Map<Node, Integer> holders = new IdentityHashMap<>();
    private final Map<Node, Integer> readers = new IdentityHashMap<>();
    private final int maxRoots, maxLoans, maxWork;

    public ShallowSlotDomain(int maxRoots, int maxLoans, int maxWork) {
        if (maxRoots <= 0 || maxLoans < 0 || maxWork <= 0) throw error("E_COPY_BUDGET");
        this.maxRoots = maxRoots;
        this.maxLoans = maxLoans;
        this.maxWork = maxWork;
    }
    private static IllegalArgumentException error(String code) { return new IllegalArgumentException(code); }
    private static void scalar(Object value) {
        if (value != null && !(value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Double)) {
            throw error("E_COPY_TYPE");
        }
    }
    private Root root(Owner owner) {
        Root root = roots.get(owner);
        if (root == null) throw error("E_COPY_STALE");
        return root;
    }
    private void reachable(Root root, Node node) {
        if (!root.reach().contains(node)) throw error("E_COPY_DOMAIN");
    }
    private void writable(Root root, Node node) {
        reachable(root, node);
        if (holders.getOrDefault(node, 0) != 1) throw error("E_COPY_READONLY");
        if (readers.containsKey(node)) throw error("E_COPY_LOAN");
    }
    private static void adjust(Map<Node, Integer> counts, Set<Node> nodes, int delta) {
        for (Node node : nodes) {
            int count = counts.getOrDefault(node, 0) + delta;
            if (count == 0) counts.remove(node); else counts.put(node, count);
        }
    }
    private Set<Node> walk(Node start, Edit edit) {
        Set<Node> seen = new HashSet<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(start);
        int work = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (++work > maxWork) throw error("E_COPY_BUDGET");
            if (!seen.add(node)) continue;
            for (var entry : node.slots.entrySet()) {
                if (++work > maxWork) throw error("E_COPY_BUDGET");
                if (edit != null && node == edit.node() && entry.getKey().equals(edit.key())) continue;
                if (entry.getValue().value() instanceof Node child) pending.add(child);
            }
            if (edit != null && node == edit.node() && edit.next() != null) {
                if (++work > maxWork) throw error("E_COPY_BUDGET");
                if (edit.next().value() instanceof Node child) pending.add(child);
            }
        }
        return seen;
    }
    private Owner publish(Node node, Set<Node> reach) {
        if (roots.size() >= maxRoots) throw error("E_COPY_BUDGET");
        Owner owner = new Owner();
        roots.put(owner, new Root(node, reach));
        adjust(holders, reach, 1);
        return owner;
    }
    public synchronized Owner create(boolean dynamic, boolean noCopy, Map<String, ?> values, Set<String> readonly) {
        if (values.size() >= maxWork || !values.keySet().containsAll(readonly)) throw error("E_COPY_LAYOUT");
        Map<String, Slot> slots = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null) throw error("E_COPY_LAYOUT");
            scalar(entry.getValue());
            slots.put(entry.getKey(), new Slot(entry.getValue(), !readonly.contains(entry.getKey())));
        }
        Node node = new Node(dynamic, noCopy, slots);
        return publish(node, walk(node, null));
    }
    public synchronized Node node(Owner owner) { return root(owner).node(); }
    public synchronized Object read(Owner owner, Node node, String key) {
        reachable(root(owner), node);
        Slot slot = node.slots.get(key);
        return slot == null ? null : slot.value();
    }
    public synchronized Owner shallowCopy(Owner owner) {
        Root source = root(owner);
        for (Node node : source.reach()) if (node.noCopy) throw error("E_COPY_FORBIDDEN");
        Node clone = new Node(source.node().dynamic, false, new LinkedHashMap<>(source.node().slots));
        return publish(clone, walk(clone, null));
    }
    private Slot replacement(Root root, Node node, String key, Object value) {
        writable(root, node);
        if (key == null) throw error("E_COPY_LAYOUT");
        Slot old = node.slots.get(key);
        if (old == null && !node.dynamic) throw error("E_COPY_LAYOUT");
        if (old != null && !old.writable()) throw error("E_COPY_READONLY_SLOT");
        return new Slot(value, true);
    }
    private void commit(Owner owner, Root root, Edit edit, Set<Node> after) {
        // All semantic checks and bounded traversal precede the first write.
        if (edit.next() == null) edit.node().slots.remove(edit.key());
        else edit.node().slots.put(edit.key(), edit.next());
        adjust(holders, root.reach(), -1);
        adjust(holders, after, 1);
        roots.put(owner, new Root(root.node(), after));
    }
    private void replace(Owner owner, Root root, Node node, String key, Object value) {
        Edit edit = new Edit(node, key, replacement(root, node, key, value));
        commit(owner, root, edit, walk(root.node(), edit));
    }
    public synchronized void setScalar(Owner owner, Node node, String key, Object value) {
        scalar(value);
        replace(owner, root(owner), node, key, value);
    }
    public synchronized void linkWithin(Owner owner, Node node, String key, Node value) {
        Root root = root(owner);
        reachable(root, value);
        replace(owner, root, node, key, value);
    }
    public synchronized void moveInto(Owner owner, Node node, String key, Owner donor) {
        Root root = root(owner), source = root(donor);
        if (owner == donor) throw error("E_COPY_DOMAIN");
        Edit edit = new Edit(node, key, replacement(root, node, key, source.node()));
        for (Node child : source.reach()) writable(source, child);
        Set<Node> after = walk(root.node(), edit);
        // Consume the donor only after all admission checks succeed.
        commit(owner, root, edit, after);
        roots.remove(donor);
        adjust(holders, source.reach(), -1);
    }
    public synchronized void remove(Owner owner, Node node, String key) {
        Root root = root(owner);
        replacement(root, node, key, null);
        if (!node.dynamic) throw error("E_COPY_LAYOUT");
        Edit edit = new Edit(node, key, null);
        commit(owner, root, edit, walk(root.node(), edit));
    }
    public synchronized Loan readLoan(Owner owner, Node node) {
        reachable(root(owner), node);
        if (loans.size() >= maxLoans) throw error("E_COPY_BUDGET");
        Set<Node> reach = walk(node, null);
        Loan loan = new Loan();
        loans.put(loan, reach);
        adjust(readers, reach, 1);
        return loan;
    }
    public synchronized void release(Loan loan) {
        Set<Node> reach = loans.remove(loan);
        if (reach == null) throw error("E_COPY_STALE");
        adjust(readers, reach, -1);
    }
    public synchronized void drop(Owner owner) {
        Root root = root(owner);
        roots.remove(owner);
        adjust(holders, root.reach(), -1);
    }
    public synchronized boolean canWrite(Owner owner, Node node) {
        Root root = root(owner);
        return root.reach().contains(node) && holders.getOrDefault(node, 0) == 1 && !readers.containsKey(node);
    }
}
