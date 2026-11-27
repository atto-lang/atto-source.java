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
    // Bound live outer copies even when there are no reference-bearing children.
    private int activeSnapshots;

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
            GraphLease lease = prepareDescendants(maxNodes, maxEdges);
            activate(lease);
            return lease;
        }
    }

    private GraphLease prepareDescendants(int maxNodes, int maxEdges) {
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
            return new GraphLease(domain, List.copyOf(ordered));
    }

    private static void activate(GraphLease lease) {
        // No callback or allocation between the first counter change and publication.
        for (int i = 0; i < lease.protectedStores.size(); i++) {
            lease.protectedStores.get(i).readOnlyLoans++;
        }
    }

    /** Scoped root snapshot for trusted runtime callers. The parent and
     * copy slots are independent, while retained descendants stay protected.
     * This first profile holds the complete lease until close; it does not
     * yet detach individual graph edges or admit guest escapes.
     */
    public static final class ShallowSnapshot implements AutoCloseable {
        private final GuestClassSlots original, copy;
        private final GraphLease lease;
        private boolean closed;
        private ShallowSnapshot(GuestClassSlots original, GuestClassSlots copy, GraphLease lease) {
            this.original = original;
            this.copy = copy;
            this.lease = lease;
        }
        private void requireOpen() {
            if (closed) throw stale();
        }
        public Object get(String key) {
            synchronized (copy.domain) {
                requireOpen();
                return copy.get(key);
            }
        }
        public void replace(String key, Object value) {
            synchronized (copy.domain) {
                requireOpen();
                // Validate the complete replacement graph before publication.
                validateReplacement(value);
                copy.replace(key, value);
            }
        }
        private void validateReplacement(Object value) {
            if (!(value instanceof GuestClassSlots root)) {
                if (value != null && !CopyScalarAdmission.isImmutableScalar(value)) {
                    throw new IllegalArgumentException("E_COPY_UNPROVEN: unsupported replacement payload");
                }
                return;
            }
            var covered = new IdentityHashMap<GuestClassSlots, Boolean>();
            for (GuestClassSlots store : lease.protectedStores) covered.put(store, Boolean.TRUE);
            var seen = new IdentityHashMap<GuestClassSlots, Boolean>();
            var pending = new ArrayDeque<GuestClassSlots>();
            pending.add(root);
            int edges = 0;
            while (!pending.isEmpty()) {
                GuestClassSlots store = pending.removeFirst();
                if (seen.put(store, Boolean.TRUE) != null) continue;
                if (seen.size() > 65536) throw new IllegalArgumentException("E_COPY_BUDGET: replacement node limit");
                if (store.domain != copy.domain) {
                    throw new IllegalArgumentException("E_COPY_DOMAIN: foreign replacement store");
                }
                if (store.initializing) {
                    throw new IllegalStateException("E_COPY_LAYOUT: unpublished replacement store");
                }
                if (store.readOnlyLoans != 0 && !covered.containsKey(store)) {
                    throw new IllegalArgumentException("E_COPY_SHALLOW_ALIAS: replacement depends on an unrelated loan");
                }
                edges = enqueueChildren(store, pending, edges, 262144);
            }
            // Conservative defense, not proof of unique donor ownership.
            // The typed caller still proves move, lifetime and exclusive authority.
        }

        /** Snapshot the current independent outer root, not the original
         * source. Each generation acquires its own descendant lease before
         * publication, so closing an earlier generation cannot thaw a
         * referent retained by a later one. The caller still proves guest
         * ownership, lifetime, and exclusive-loan admission.
         */
        public ShallowSnapshot shallowSnapshot(int maxNodes, int maxEdges) {
            synchronized (copy.domain) {
                requireOpen();
                return copy.shallowSnapshot(maxNodes, maxEdges);
            }
        }

        public int protectedCount() {
            synchronized (copy.domain) {
                requireOpen();
                return lease.protectedCount();
            }
        }
        @Override public void close() {
            synchronized (copy.domain) {
                requireOpen();
                if (original.activeSnapshots <= 0) throw stale();
                lease.close();
                original.activeSnapshots--;
                closed = true;
            }
        }
    }

    /** Capture a fresh root and the exact retained descendants atomically.
     * The caller still must prove type/ownership policy, no escaping handles
     * or outstanding exclusive loans, and correct lexical cleanup.
     */
    public ShallowSnapshot shallowSnapshot(int maxNodes, int maxEdges) {
        if (maxNodes < 0 || maxNodes > 65536 || maxEdges < 0 || maxEdges > 262144) {
            throw new IllegalArgumentException("E_COPY_BUDGET: invalid graph limits");
        }
        synchronized (domain) {
            if (activeSnapshots >= MAX_READ_ONLY_LOANS) {
                throw new IllegalArgumentException("E_COPY_BUDGET: active snapshot limit");
            }
            GraphLease lease = prepareDescendants(maxNodes, maxEdges);
            GuestClassSlots copy = new GuestClassSlots(domain, slots);
            copy.finishInitialization();
            ShallowSnapshot result = new ShallowSnapshot(this, copy, lease);
            activate(lease);
            activeSnapshots++;
            return result;
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
