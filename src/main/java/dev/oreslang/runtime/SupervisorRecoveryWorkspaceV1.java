package dev.oreslang.runtime;

/** Reserved worker-local traversal buffer and bounded primitive reference index.
 * The ledger owns topology; callers serialize the index and traversal separately.
 * No guest or runtime callbacks execute under the ledger monitor. */
final class SupervisorRecoveryWorkspaceV1 {
    interface Node {
        long recoveryId();
        int recoveryDepth();
        Node recoveryParent();
    }

    static int collect(Node[] nodes, int size, Node root, int maxDepth, long[] output) {
        if (size < 1 || size > nodes.length || output.length < size
                || root.recoveryDepth() < 1 || root.recoveryDepth() > maxDepth) {
            throw new IllegalArgumentException("invalid recovery workspace");
        }
        int count = 0;
        // Bounded scan by depth, then original slot order. No sort, boxed ID,
        // temporary map, or recursive host call stack is needed.
        for (int depth = maxDepth; depth >= root.recoveryDepth(); depth--) {
            for (int i = 0; i < size; i++) {
                Node node = nodes[i];
                if (node.recoveryDepth() != depth) continue;
                Node ancestor = node;
                for (int remaining = depth; ancestor != null && remaining > 0; remaining--) {
                    if (ancestor == root) {
                        output[count++] = node.recoveryId();
                        break;
                    }
                    ancestor = ancestor.recoveryParent();
                }
            }
        }
        if (count == 0 || output[count - 1] != root.recoveryId()) {
            throw new IllegalStateException("missing recovery root");
        }
        return count;
    }

    static final class RefIndex<T> {
        private final long[] ids;
        private final Object[] refs;
        private int size;
        RefIndex(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("invalid ref capacity");
            ids = new long[capacity];
            refs = new Object[capacity];
        }
        @SuppressWarnings("unchecked")
        T get(long id) {
            for (int i = 0; i < size; i++) if (ids[i] == id) return (T) refs[i];
            return null;
        }
        T putIfAbsent(long id, T ref) {
            if (id <= 0 || ref == null) throw new IllegalArgumentException("invalid recovery ref");
            T previous = get(id);
            if (previous != null) return previous;
            if (size == ids.length) throw new IllegalStateException("recovery ref capacity exhausted");
            ids[size] = id;
            refs[size++] = ref;
            return null;
        }
        boolean remove(long id, T ref) {
            for (int i = 0; i < size; i++) {
                if (ids[i] == id && refs[i] == ref) {
                    int last = --size;
                    ids[i] = ids[last];
                    refs[i] = refs[last];
                    ids[last] = 0;
                    refs[last] = null;
                    return true;
                }
            }
            return false;
        }
    }
}
