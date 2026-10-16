package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        if (value instanceof String || value instanceof Boolean
                || value instanceof Character || value instanceof Byte
                || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof BigInteger
                || value instanceof BigDecimal || value instanceof PersistentActorMap) {
            return;
        }
        throw new IllegalArgumentException(
                "persistent actor data requires immutable scalar values or PersistentActorMap; "
                        + "mutable containers, foreign references, and capabilities are forbidden");
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
                return Objects.equals(leaf.value, entry.value) ? node : entry;
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
                    if (Objects.equals(old.value, entry.value)) return node;
                    Leaf[] copy = collision.leaves.clone();
                    copy[i] = entry;
                    return new Collision(collision.hash, copy);
                }
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

    private static void visit(Node node, Set<Map.Entry<String, Object>> entries) {
        if (node == null) return;
        if (node instanceof Leaf leaf) {
            entries.add(Map.entry(leaf.key, leaf.value));
        } else if (node instanceof Collision collision) {
            for (Leaf leaf : collision.leaves) entries.add(Map.entry(leaf.key, leaf.value));
        } else {
            for (Node child : ((Branch) node).children) visit(child, entries);
        }
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        LinkedHashSet<Map.Entry<String, Object>> entries = new LinkedHashSet<>();
        visit(root, entries);
        return Collections.unmodifiableSet(entries);
    }
}
