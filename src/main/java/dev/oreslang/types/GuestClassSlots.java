package dev.oreslang.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Encapsulated guest class slots. Graph leasing is opt-in and requires
 * a trusted, explicit ownership domain. A default store gets its own domain.
 * No authority to share across actors, tasks or foreign code is implied.
 */
public final class GuestClassSlots {
    private final Map<String, Object> slots;
    private final Map<String, Object> view;
    private final Domain domain;
    private boolean initializing = true;
    private static final int MAX_READ_ONLY_LOANS = 65536;
    private int readOnlyLoans;

    /** Monitor shared by an explicitly admitted group of slot stores.
     * No evaluator-global monitor: unrelated default stores remain independent.
     */
    public static final class Domain { }

    public static final class ReadOnlyLease implements AutoCloseable {
        private final GuestClassSlots owner;
        private boolean released;
        private ReadOnlyLease(GuestClassSlots owner) { this.owner = owner; }
        @Override public void close() {
            synchronized (owner.domain) {
                if (released || owner.readOnlyLoans <= 0) throw stale();
                released = true;
                owner.readOnlyLoans--;
            }
        }
    }

    /** A single token protects each distinct reachable descendant once.
     * Only trusted runtime code may retain or release it.
     */
    public static final class GraphLease implements AutoCloseable {
        private final Domain domain;
        private final List<GuestClassSlots> protectedStores;
        private boolean released;
        private GraphLease(Domain domain, List<GuestClassSlots> stores) {
            this.domain = domain;
            this.protectedStores = stores;
        }
        public int protectedCount() { return protectedStores.size(); }
        @Override public void close() {
            synchronized (domain) {
                if (released) throw stale();
                for (GuestClassSlots store : protectedStores) {
                    if (store.readOnlyLoans <= 0) throw stale();
                }
                for (GuestClassSlots store : protectedStores) store.readOnlyLoans--;
                released = true;
            }
        }
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("E_COPY_STALE: released read-only loan");
    }

    private void checkRetain() {
        if (initializing) throw new IllegalStateException("E_COPY_LAYOUT: unpublished class");
        if (readOnlyLoans >= MAX_READ_ONLY_LOANS) throw new IllegalArgumentException("E_COPY_BUDGET: read-only loan limit");
    }

    public ReadOnlyLease retainReadOnly() {
        synchronized (domain) {
            checkRetain();
            ReadOnlyLease lease = new ReadOnlyLease(this);
            readOnlyLoans++;
            return lease;
        }
    }

    /** Transactional preflight of descendant slot stores for a closed,
     * class-only graph. Scalar payloads are exact immutable representations.
     * The root is protected only when a retained edge reaches it. Unknown
     * payloads, foreign domains, unpublished stores and quotas fail closed.
     * This does not check nominal copy policy, guest lifetime or move rights;
     * the caller must establish those independently before publication.
     * This method intentionally has no user callbacks or reflective traversal.
     */
    public GraphLease retainDescendants(int maxNodes, int maxEdges) {
        if (maxNodes < 0 || maxNodes > 65536 || maxEdges < 0 || maxEdges > 262144) {
            throw new IllegalArgumentException("E_COPY_BUDGET: invalid graph limits");
        }
        synchronized (domain) {
            if (initializing) throw new IllegalStateException("E_COPY_LAYOUT: unpublished root");
            var seen = new IdentityHashMap<GuestClassSlots, Boolean>();
            var pending = new ArrayDeque<GuestClassSlots>();
            var ordered = new ArrayList<GuestClassSlots>();
            int edges = enqueueChildren(this, pending, 0, maxEdges);
            while (!pending.isEmpty()) {
                GuestClassSlots store = pending.removeFirst();
                if (seen.put(store, Boolean.TRUE) != null) continue;
                if (ordered.size() >= maxNodes) throw new IllegalArgumentException("E_COPY_BUDGET: graph node limit");
                if (store.domain != domain) throw new IllegalArgumentException("E_COPY_DOMAIN: foreign class slot store");
                store.checkRetain();
                ordered.add(store);
                edges = enqueueChildren(store, pending, edges, maxEdges);
            }
            // Allocate every piece of the token before modifying any counters.
            GraphLease lease = new GraphLease(domain, List.copyOf(ordered));
            for (GuestClassSlots store : ordered) store.readOnlyLoans++;
            return lease;
        }
    }

    private static int enqueueChildren(GuestClassSlots store,
            ArrayDeque<GuestClassSlots> pending, int edges, int maxEdges) {
        for (Object value : store.slots.values()) {
            if (edges >= maxEdges) throw new IllegalArgumentException("E_COPY_BUDGET: graph edge limit");
            edges++;
            if (value instanceof GuestClassSlots child) pending.addLast(child);
            else if (value != null && !CopyScalarAdmission.isImmutableScalar(value)) {
                throw new IllegalArgumentException("E_COPY_UNPROVEN: unsupported graph payload");
            }
        }
        return edges;
    }

    public boolean isReadOnly() {
        synchronized (domain) { return readOnlyLoans != 0; }
    }

    public GuestClassSlots(Map<String, Object> initial) {
        this(new Domain(), initial);
    }

    public GuestClassSlots(Domain domain, Map<String, Object> initial) {
        this.domain = Objects.requireNonNull(domain);
        slots = new LinkedHashMap<>(initial);
        view = Collections.unmodifiableMap(slots);
    }

    public Object get(String key) {
        synchronized (domain) { return slots.get(key); }
    }
    public boolean containsKey(Object key) {
        synchronized (domain) { return slots.containsKey(key); }
    }
    public int size() {
        synchronized (domain) { return slots.size(); }
    }
    /** Live read-only view; callers still need existing ownership/read proof. */
    public Map<String, Object> view() { return view; }

    public void initialize(String key, Object value) {
        synchronized (domain) {
            if (!initializing) throw new IllegalStateException("E_COPY_LAYOUT: class initialization closed");
            slots.put(key, value);
        }
    }

    public void finishInitialization() {
        synchronized (domain) { initializing = false; }
    }

    /** Caller has independently checked declaration mutability, visibility,
     * and initialization authority. No structural change to a class layout.
     */
    public void replace(String key, Object value) {
        synchronized (domain) {
            if (readOnlyLoans != 0) throw new IllegalArgumentException("E_COPY_READONLY: shared class referent");
            if (!slots.containsKey(key)) throw new IllegalArgumentException("E_COPY_LAYOUT: unknown class slot " + key);
            slots.put(key, value);
        }
    }
}
