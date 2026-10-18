package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Collection;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Prototype runtime-owned persistent HAMT for actor message data.
 *
 * Only String keys and an explicitly closed set of immutable data values are
 * admitted. This is deliberate: Map.copyOf or an unmodifiable view is NOT a
 * proof of deep immutability, nor is a foreign polyglot object.
 *
 * with/without path-copy at most one 32-way node per trie level; old roots
 * remain valid. This type grants no actor-state capability or FFI lease.
 * Source-language lowering and native backend parity are separate gates.
 */
public final class PersistentActorMap extends AbstractMap<String, Object> {
    private static final PersistentActorMap EMPTY = new PersistentActorMap(null, 0);
    /** Reject pathological String hash floods instead of allowing unbounded linear scans. */
    private static final int MAX_COLLISION_LENGTH = 64;

    private interface Node { }
    private record Leaf(String key, Object value, int hash) implements Node { }
    private record Collision(int hash, Leaf[] leaves) implements Node { }
    private record Branch(int bitmap, Node[] children) implements Node { }
    private static final class Flag { boolean set; }

    private final Node root;
    private final int count;

    private PersistentActorMap(Node root, int count) {
        this.root = root;
        this.count = count;
    }

    public static PersistentActorMap empty() {
        return EMPTY;
    }

    /** Never adopts mutable input, even if it has an unmodifiable wrapper. */
    private static void requireShareableValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("persistent actor map values cannot be null");
        }
        if (value instanceof String || value instanceof Boolean
                || value instanceof Character || value instanceof Byte
                || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float
                || value instanceof Double || value.getClass() == BigInteger.class
                || value.getClass() == BigDecimal.class || value instanceof PersistentActorMap) {
            return;
        }
        throw new IllegalArgumentException(
                "persistent actor data requires immutable scalar values or PersistentActorMap; "
                        + "mutable containers, foreign references, and capabilities are forbidden");
    }

    /**
     * Different immutable persistent roots are not equal-update shortcuts:
     * recursive Map.equals can traverse thousands of nested descendants.
     * Preserve referential O(1) no-ops while bounding mutation work to a
     * HAMT path copy. Ordinary immutable scalars still compare by value.
     */
    private static boolean sameUpdateValue(Object left, Object right) {
        if (left == right) return true;
        if (left instanceof PersistentActorMap || right instanceof PersistentActorMap)
            return false;
        return Objects.equals(left, right);
    }

    /**
     * A persistent root compared with any Map must use an explicit work queue:
     * delegating to AbstractMap.equals when the other map is ordinary Map.of
     * still recurses once per nested PersistentActorMap and can overflow.
     * Map's size/key/value contract is preserved for non-HAMT maps as well.
     */
    private record EqualityPair(PersistentActorMap left, Map<?, ?> right) { }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof Map<?, ?> that)) return false;
        var pending = new ArrayDeque<EqualityPair>();
        var visited = new IdentityHashMap<PersistentActorMap,
                IdentityHashMap<Map<?, ?>, Boolean>>();
        pending.push(new EqualityPair(this, that));
        while (!pending.isEmpty()) {
            EqualityPair pair = pending.pop();
            if (pair.left == pair.right) continue;
            var matches = visited.computeIfAbsent(pair.left, ignored -> new IdentityHashMap<>());
            if (matches.put(pair.right, Boolean.TRUE) != null) continue;
            try {
                if (pair.left.size() != pair.right.size()) return false;
                for (Map.Entry<String, Object> entry : pair.left.entrySet()) {
                    Object rightValue = pair.right.get(entry.getKey());
                    if (rightValue == null) return false; // Null values cannot be stored.
                    Object leftValue = entry.getValue();
                    if (leftValue instanceof PersistentActorMap leftMap
                            && rightValue instanceof Map<?, ?> rightMap) {
                        pending.push(new EqualityPair(leftMap, rightMap));
                    } else if (!Objects.equals(leftValue, rightValue)) {
                        return false;
                    }
                }
            } catch (ClassCastException | NullPointerException ex) {
                // Matches AbstractMap.equals for foreign Maps with restrictive
                // or null-intolerant key lookup implementations.
                return false;
            }
        }
        return true;
    }

    private static final class HashFrame {
        final PersistentActorMap map;
        final Iterator<Map.Entry<String, Object>> entries;
        Map.Entry<String, Object> waiting;
        int sum;

        HashFrame(PersistentActorMap map) {
            this.map = map;
            this.entries = map.entrySet().iterator();
        }
    }

    /** Match Map's entry hash sum, without recursive nested value hashing. */
    @Override
    public int hashCode() {
        var completed = new IdentityHashMap<PersistentActorMap, Integer>();
        var visiting = new IdentityHashMap<PersistentActorMap, Boolean>();
        var pending = new ArrayDeque<HashFrame>();
        visiting.put(this, Boolean.TRUE);
        pending.push(new HashFrame(this));
        while (!pending.isEmpty()) {
            HashFrame frame = pending.peek();
            if (frame.waiting != null) {
                PersistentActorMap child = (PersistentActorMap) frame.waiting.getValue();
                Integer childHash = completed.get(child);
                if (childHash == null) {
                    if (visiting.put(child, Boolean.TRUE) != null)
                        throw new IllegalStateException("cycle in sealed persistent map graph");
                    pending.push(new HashFrame(child));
                    continue;
                }
                frame.sum += frame.waiting.getKey().hashCode() ^ childHash;
                frame.waiting = null;
                continue;
            }
            if (!frame.entries.hasNext()) {
                completed.put(frame.map, frame.sum);
                visiting.remove(frame.map);
                pending.pop();
                continue;
            }
            Map.Entry<String, Object> entry = frame.entries.next();
            Object value = entry.getValue();
            if (value instanceof PersistentActorMap child) {
                Integer childHash = completed.get(child);
                if (childHash == null) {
                    frame.waiting = entry;
                    if (visiting.put(child, Boolean.TRUE) != null)
                        throw new IllegalStateException("cycle in sealed persistent map graph");
                    pending.push(new HashFrame(child));
                    continue;
                }
                frame.sum += entry.getKey().hashCode() ^ childHash;
            } else {
                frame.sum += entry.getKey().hashCode() ^ value.hashCode();
            }
        }
        return completed.get(this);
    }

    private static int hash(String key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int bit(int hash, int shift) {
        return 1 << ((hash >>> shift) & 31);
    }

    private static int offset(int bitmap, int bit) {
        return Integer.bitCount(bitmap & (bit - 1));
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String s && find(root, s, hash(s), 0) != null;
    }

    @Override
    public Object get(Object key) {
        return key instanceof String s ? find(root, s, hash(s), 0) : null;
    }

    public PersistentActorMap with(String key, Object value) {
        Objects.requireNonNull(key, "persistent map key");
        requireShareableValue(value);
        Flag added = new Flag();
        Node next = put(root, new Leaf(key, value, hash(key)), 0, added);
        if (next == root) return this;
        return new PersistentActorMap(next, Math.addExact(count, added.set ? 1 : 0));
    }

    public PersistentActorMap without(String key) {
        Objects.requireNonNull(key, "persistent map key");
        Flag removed = new Flag();
        Node next = delete(root, key, hash(key), 0, removed);
        if (!removed.set) return this;
        return next == null ? EMPTY : new PersistentActorMap(next, count - 1);
    }

    // AbstractMap and Map default mutators may silently accept no-op writes
    // or invoke callbacks before throwing. Reject the operation up front.
    private static UnsupportedOperationException immutable() {
        return new UnsupportedOperationException("PersistentActorMap is immutable; use with/without");
    }

    @Override public Object put(String key, Object value) { throw immutable(); }
    @Override public Object remove(Object key) { throw immutable(); }
    @Override public void putAll(Map<? extends String, ? extends Object> map) { throw immutable(); }
    @Override public void clear() { throw immutable(); }
    @Override public Object putIfAbsent(String key, Object value) { throw immutable(); }
    @Override public boolean remove(Object key, Object value) { throw immutable(); }
    @Override public boolean replace(String key, Object oldValue, Object newValue) { throw immutable(); }
    @Override public Object replace(String key, Object value) { throw immutable(); }
    @Override public void replaceAll(BiFunction<? super String, ? super Object, ? extends Object> f) {
        throw immutable();
    }
    @Override public Object computeIfAbsent(String key, Function<? super String, ? extends Object> f) {
        throw immutable();
    }
    @Override public Object computeIfPresent(String key,
            BiFunction<? super String, ? super Object, ? extends Object> f) {
        throw immutable();
    }
    @Override public Object compute(String key,
            BiFunction<? super String, ? super Object, ? extends Object> f) {
        throw immutable();
    }
    @Override public Object merge(String key, Object value,
            BiFunction<? super Object, ? super Object, ? extends Object> f) {
        throw immutable();
    }

    private static Object find(Node node, String key, int hash, int shift) {
        if (node == null) return null;
        if (node instanceof Leaf leaf) {
            return leaf.hash == hash && leaf.key.equals(key) ? leaf.value : null;
        }
        if (node instanceof Collision collision) {
            if (collision.hash != hash) return null;
            for (Leaf leaf : collision.leaves)
                if (leaf.key.equals(key)) return leaf.value;
            return null;
        }
        Branch branch = (Branch) node;
        int bit = bit(hash, shift);
        if ((branch.bitmap & bit) == 0) return null;
        return find(branch.children[offset(branch.bitmap, bit)], key, hash, shift + 5);
    }

    private static int commonHash(Node node) {
        return node instanceof Leaf leaf ? leaf.hash : ((Collision) node).hash;
    }

    /** Merge different full hashes; matching prefixes introduce one path node. */
    private static Node merge(Node existing, Node incoming, int shift) {
        int leftHash = commonHash(existing);
        int rightHash = commonHash(incoming);
        int leftBit = bit(leftHash, shift);
        int rightBit = bit(rightHash, shift);
        if (leftBit == rightBit) {
            return new Branch(leftBit, new Node[]{merge(existing, incoming, shift + 5)});
        }
        return Integer.compareUnsigned(leftBit, rightBit) < 0
                ? new Branch(leftBit | rightBit, new Node[]{existing, incoming})
                : new Branch(leftBit | rightBit, new Node[]{incoming, existing});
    }

    private static Node put(Node node, Leaf entry, int shift, Flag added) {
        if (node == null) {
            added.set = true;
            return entry;
        }
        if (node instanceof Leaf leaf) {
            if (leaf.hash == entry.hash && leaf.key.equals(entry.key)) {
                return sameUpdateValue(leaf.value, entry.value) ? node : entry;
            }
            added.set = true;
            if (leaf.hash == entry.hash) {
                return new Collision(entry.hash, new Leaf[]{leaf, entry});
            }
            return merge(leaf, entry, shift);
        }
        if (node instanceof Collision collision) {
            if (collision.hash != entry.hash) {
                added.set = true;
                return merge(collision, entry, shift);
            }
            for (int i = 0; i < collision.leaves.length; i++) {
                Leaf old = collision.leaves[i];
                if (old.key.equals(entry.key)) {
                    if (sameUpdateValue(old.value, entry.value)) return node;
                    Leaf[] copy = collision.leaves.clone();
                    copy[i] = entry;
                    return new Collision(collision.hash, copy);
                }
            }
            if (collision.leaves.length >= MAX_COLLISION_LENGTH) {
                throw new IllegalArgumentException("persistent map hash collision bucket exceeds "
                        + MAX_COLLISION_LENGTH + " entries");
            }
            Leaf[] grown = java.util.Arrays.copyOf(collision.leaves, collision.leaves.length + 1);
            grown[grown.length - 1] = entry;
            added.set = true;
            return new Collision(collision.hash, grown);
        }
        Branch branch = (Branch) node;
        int bit = bit(entry.hash, shift);
        int index = offset(branch.bitmap, bit);
        if ((branch.bitmap & bit) == 0) {
            Node[] grown = new Node[branch.children.length + 1];
            System.arraycopy(branch.children, 0, grown, 0, index);
            grown[index] = entry;
            System.arraycopy(branch.children, index, grown, index + 1,
                    branch.children.length - index);
            added.set = true;
            return new Branch(branch.bitmap | bit, grown);
        }
        Node replaced = put(branch.children[index], entry, shift + 5, added);
        if (replaced == branch.children[index]) return node;
        Node[] copy = branch.children.clone();
        copy[index] = replaced;
        return new Branch(branch.bitmap, copy);
    }

    private static Node delete(Node node, String key, int hash, int shift, Flag removed) {
        if (node == null) return null;
        if (node instanceof Leaf leaf) {
            if (leaf.hash != hash || !leaf.key.equals(key)) return node;
            removed.set = true;
            return null;
        }
        if (node instanceof Collision collision) {
            if (collision.hash != hash) return node;
            int index = -1;
            for (int i = 0; i < collision.leaves.length; i++) {
                if (collision.leaves[i].key.equals(key)) { index = i; break; }
            }
            if (index < 0) return node;
            removed.set = true;
            if (collision.leaves.length == 2) return collision.leaves[1 - index];
            Leaf[] copy = new Leaf[collision.leaves.length - 1];
            System.arraycopy(collision.leaves, 0, copy, 0, index);
            System.arraycopy(collision.leaves, index + 1, copy, index,
                    collision.leaves.length - index - 1);
            return new Collision(hash, copy);
        }
        Branch branch = (Branch) node;
        int bit = bit(hash, shift);
        if ((branch.bitmap & bit) == 0) return node;
        int index = offset(branch.bitmap, bit);
        Node replaced = delete(branch.children[index], key, hash, shift + 5, removed);
        if (!removed.set) return node;
        if (replaced != null) {
            Node[] copy = branch.children.clone();
            copy[index] = replaced;
            return new Branch(branch.bitmap, copy);
        }
        if (branch.children.length == 1) return null;
        Node[] shrunk = new Node[branch.children.length - 1];
        System.arraycopy(branch.children, 0, shrunk, 0, index);
        System.arraycopy(branch.children, index + 1, shrunk, index,
                branch.children.length - index - 1);
        // Only a leaf/collision can be hoisted across a trie level.
        if (shrunk.length == 1 && !(shrunk[0] instanceof Branch)) return shrunk[0];
        return new Branch(branch.bitmap & ~bit, shrunk);
    }

    // AbstractMap keySet()/values() inherit view mutators that can return
    // false on an absent element, or run a removeIf predicate before throwing.
    // Wrap both views to reject mutation attempts without invoking user code.
    @Override
    public Set<String> keySet() {
        return Collections.unmodifiableSet(super.keySet());
    }

    @Override
    public Collection<Object> values() {
        return Collections.unmodifiableCollection(super.values());
    }

    /**
     * Iteration uses a bounded HAMT-depth stack, not an eagerly materialized
     * second Map/Set. Actor transport still verifies every logical member,
     * but does not allocate O(n) temporary entries merely to walk the map.
     */
    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        return Collections.unmodifiableSet(new AbstractSet<>() {
            @Override
            public int size() {
                return count;
            }

            @Override
            public Iterator<Map.Entry<String, Object>> iterator() {
                return new Iterator<>() {
                    private final ArrayDeque<Node> pending = new ArrayDeque<>();
                    private Leaf ready;
                    {
                        if (root != null) pending.push(root);
                    }

                    private void prepare() {
                        while (ready == null && !pending.isEmpty()) {
                            Node node = pending.pop();
                            if (node instanceof Leaf leaf) {
                                ready = leaf;
                            } else if (node instanceof Collision collision) {
                                for (int i = collision.leaves.length - 1; i >= 0; i--)
                                    pending.push(collision.leaves[i]);
                            } else {
                                Node[] children = ((Branch) node).children;
                                for (int i = children.length - 1; i >= 0; i--)
                                    pending.push(children[i]);
                            }
                        }
                    }

                    @Override
                    public boolean hasNext() {
                        prepare();
                        return ready != null;
                    }

                    @Override
                    public Map.Entry<String, Object> next() {
                        prepare();
                        if (ready == null) throw new NoSuchElementException();
                        Leaf leaf = ready;
                        ready = null;
                        return Map.entry(leaf.key, leaf.value);
                    }
                };
            }
        });
    }
}
