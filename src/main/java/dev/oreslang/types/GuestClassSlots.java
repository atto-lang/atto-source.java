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
        private List<GuestClassSlots> protectedStores;
        private boolean released;
        private GraphLease(Domain domain, List<GuestClassSlots> stores) {
            this.domain = domain;
            this.protectedStores = stores;
        }
        public int protectedCount() { synchronized (domain) { return protectedStores.size(); } }
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
     * Explicit fresh-field replacement can release no-longer-reachable
     * original descendants. No escaped read handles or untracked owner
     * mutation may be admitted by the trusted caller.
     */
    public static final class ShallowSnapshot implements AutoCloseable {
        private final GuestClassSlots original, copy;
        private final GraphLease lease;
        private boolean closed;
        private final int maxNodes, maxEdges;
        private ShallowSnapshot(GuestClassSlots original, GuestClassSlots copy, GraphLease lease, int maxNodes, int maxEdges) {
            this.original = original;
            this.copy = copy;
            this.lease = lease;
            this.maxNodes = maxNodes;
            this.maxEdges = maxEdges;
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
                // Existing generic path remains conservative; only identity-
                // preserving or scalar replacements may use it in this profile.
                Object old = copy.slots.get(key);
                if (value != old && value != null && !CopyScalarAdmission.isImmutableScalar(value))
                    throw new IllegalArgumentException("E_COPY_UNPROVEN: use fresh owned replacement");
                if (old instanceof GuestClassSlots && value != old) detach(copy, key, value, true);
                else copy.replace(key, value);
            }
        }

        /** New class storage created in this domain, with scalar-only payload.
         * No arbitrary incoming reference or implicit mutable sharing.
         * Caller must have proved slot type, mutability, and no escaped loans.
         */
        public GuestClassSlots replaceWithFresh(String key, Map<String,Object> values) {
            return replaceWithFreshOn(copy, key, values);
        }

        /** Explicit original-side detachment. With more than one live snapshot,
         * retain all remaining leases conservatively: siblings may still share
         * the old identity even after the original loses its last path.
         * Trusted caller must prove no escaped handles or untracked reattachment.
         */
        public GuestClassSlots replaceOriginalWithFresh(String key, Map<String,Object> values) {
            return replaceWithFreshOn(original, key, values);
        }

        private GuestClassSlots replaceWithFreshOn(GuestClassSlots root, String key, Map<String,Object> values) {
            // Trusted callers supply a stable, ordinary scalar map.
            if (values.size() > maxEdges) throw new IllegalArgumentException("E_COPY_BUDGET: fresh fields");
            var initial = new LinkedHashMap<>(values);
            for (var e : initial.entrySet()) {
                if (e.getKey() == null || (e.getValue() != null &&
                    !CopyScalarAdmission.isImmutableScalar(e.getValue())))
                    throw new IllegalArgumentException("E_COPY_UNPROVEN: non-scalar fresh field");
            }
            synchronized (copy.domain) {
                requireOpen();
                GuestClassSlots fresh = new GuestClassSlots(copy.domain, initial);
                fresh.finishInitialization();
                detach(root, key, fresh, root == copy || original.activeSnapshots == 1);
                return fresh;
            }
        }

        private void detach(GuestClassSlots root, String key, Object replacement, boolean releaseUnreachable) {
            if (root.readOnlyLoans != 0) throw new IllegalArgumentException("E_COPY_READONLY: shared class referent");
            if (!root.slots.containsKey(key)) throw new IllegalArgumentException("E_COPY_LAYOUT: unknown class slot");
            // Compute exact prospective root reachability before any mutation.
            var seen = new IdentityHashMap<GuestClassSlots,Boolean>();
            var queue = new ArrayDeque<GuestClassSlots>();
            int edges = 0;
            for (var e : root.slots.entrySet()) {
                if (++edges > maxEdges) throw new IllegalArgumentException("E_COPY_BUDGET: graph edge limit");
                Object value = e.getKey().equals(key) ? replacement : e.getValue();
                if (value instanceof GuestClassSlots child) queue.add(child);
                else if (value != null && !CopyScalarAdmission.isImmutableScalar(value))
                    throw new IllegalArgumentException("E_COPY_UNPROVEN: unsupported graph payload");
            }
            while (!queue.isEmpty()) {
                GuestClassSlots store = queue.removeFirst();
                if (seen.put(store, Boolean.TRUE) != null) continue;
                if (seen.size() > maxNodes) throw new IllegalArgumentException("E_COPY_BUDGET: graph node limit");
                if (store.domain != copy.domain) throw new IllegalArgumentException("E_COPY_DOMAIN: foreign class slot store");
                if (store.initializing) throw new IllegalStateException("E_COPY_LAYOUT: unpublished class");
                edges = enqueueChildren(store, queue, edges, maxEdges);
            }
            var kept = new ArrayList<GuestClassSlots>();
            var removed = new ArrayList<GuestClassSlots>();
            for (GuestClassSlots store : lease.protectedStores) {
                if (!releaseUnreachable || seen.containsKey(store)) kept.add(store);
                else {
                    if (store.readOnlyLoans <= 0) throw stale();
                    removed.add(store);
                }
            }
            List<GuestClassSlots> next = List.copyOf(kept);
            // Existing slot only, no new map entry. The graph checks above
            // precede both the field write and the counter updates.
            root.slots.put(key, replacement);
            for (int i=0; i<removed.size(); i++) removed.get(i).readOnlyLoans--;
            lease.protectedStores = next;
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
            if (activeSnapshots >= MAX_READ_ONLY_LOANS) throw new IllegalArgumentException("E_COPY_BUDGET: snapshot limit");
            GraphLease lease = prepareDescendants(maxNodes, maxEdges);
            GuestClassSlots copy = new GuestClassSlots(domain, slots);
            copy.finishInitialization();
            ShallowSnapshot result = new ShallowSnapshot(this, copy, lease, maxNodes, maxEdges);
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
            Object old = slots.get(key);
            if (activeSnapshots != 0 && old != value && value instanceof GuestClassSlots)
                throw new IllegalArgumentException("E_COPY_UNPROVEN: use checked fresh replacement while snapshots live");
            slots.put(key, value);
        }
    }
}
