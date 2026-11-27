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
    private final Domain domain;
    private boolean initializing = true;
    private static final int MAX_READ_ONLY_LOANS = 65536;
    private int readOnlyLoans;
    private int activeSnapshots;
    private boolean snapshotRoot;

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
            GraphLease lease = prepareDescendants(maxNodes, maxEdges, false);
            activate(lease);
            return lease;
        }
    }

    private GraphLease prepareDescendants(int maxNodes, int maxEdges, boolean rejectRootBackEdge) {
            if (initializing) throw new IllegalStateException("E_COPY_LAYOUT: unpublished root");
            var seen = new IdentityHashMap<GuestClassSlots, Boolean>();
            var pending = new ArrayDeque<GuestClassSlots>();
            var ordered = new ArrayList<GuestClassSlots>();
            int edges = enqueueChildren(this, pending, 0, maxEdges);
            while (!pending.isEmpty()) {
                GuestClassSlots store = pending.removeFirst();
                if (store.domain != domain) throw new IllegalArgumentException("E_COPY_DOMAIN: foreign class slot store");
                // Diagnose a forbidden source back-edge before charging it as
                // a descendant or scanning its already-visited root slots.
                if (rejectRootBackEdge && (store == this || store.activeSnapshots != 0 || store.snapshotRoot)) {
                    throw new IllegalArgumentException("E_COPY_SHALLOW_ALIAS: live snapshot root reachable from shared descendant");
                }
                if (seen.put(store, Boolean.TRUE) != null) continue;
                if (ordered.size() >= maxNodes) throw new IllegalArgumentException("E_COPY_BUDGET: graph node limit");
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
     * Generic replacement holds the complete lease until close. Explicit
     * fresh-slot detachment can retire unreachable nodes when the trusted
     * caller proves no escaping read handles or untracked aliases.
     */
    public static final class ShallowSnapshot implements AutoCloseable {
        private final GuestClassSlots copy;
        private final GraphLease lease;
        private final GuestClassSlots source;
        private IdentityHashMap<GuestClassSlots, Boolean> retained;
        private final int maxNodes;
        private final int maxEdges;
        private boolean closed;
        private ShallowSnapshot(GuestClassSlots source, GuestClassSlots copy,
                GraphLease lease, int maxNodes, int maxEdges) {
            this.source = source;
            this.copy = copy;
            this.lease = lease;
            this.maxNodes = maxNodes;
            this.maxEdges = maxEdges;
            retained = new IdentityHashMap<>();
            for (GuestClassSlots store : lease.protectedStores) retained.put(store, Boolean.TRUE);
        }

        /** Reject known unsafe replacement graphs before any slot write.
         * Existing externally leased nodes cannot be imported unless this
         * snapshot independently retains them. Uniqueness of other nodes and
         * lifetime of raw host references remain caller proof obligations.
         * The domain monitor must be held throughout traversal and publication.
         */
        private void validateReplacement(GuestClassSlots child, GuestClassSlots opposite) {
            var seen = new IdentityHashMap<GuestClassSlots, Boolean>();
            var pending = new ArrayDeque<GuestClassSlots>();
            pending.add(child);
            int edges = 0;
            while (!pending.isEmpty()) {
                GuestClassSlots store = pending.removeFirst();
                if (store.domain != copy.domain) {
                    throw new IllegalArgumentException("E_COPY_DOMAIN: foreign replacement store");
                }
                if (seen.put(store, Boolean.TRUE) != null) continue;
                if (seen.size() > maxNodes) {
                    throw new IllegalArgumentException("E_COPY_BUDGET: replacement graph node limit");
                }
                if (store.initializing) {
                    throw new IllegalStateException("E_COPY_LAYOUT: unpublished replacement store");
                }
                if (store == source || store == copy || store.activeSnapshots != 0 || store.snapshotRoot) {
                    throw new IllegalArgumentException("E_COPY_SHALLOW_ALIAS: replacement reaches a snapshot root");
                }
                if (store.readOnlyLoans != 0 && !retained.containsKey(store)) {
                    throw new IllegalArgumentException("E_COPY_SHALLOW_ALIAS: replacement depends on an external loan");
                }
                edges = enqueueChildren(store, pending, edges, maxEdges);
            }
            // A retired loan must not be silently resurrected by reattaching
            // a value still reachable from the opposite root. Check identity,
            // not structural equality. All traversal is under the domain lock.
            var otherSeen = new IdentityHashMap<GuestClassSlots, Boolean>();
            int otherEdges = enqueueChildren(opposite, pending, 0, maxEdges);
            while (!pending.isEmpty()) {
                GuestClassSlots store = pending.removeFirst();
                if (store.domain != copy.domain) {
                    throw new IllegalArgumentException("E_COPY_DOMAIN: foreign opposite-root store");
                }
                if (otherSeen.put(store, Boolean.TRUE) != null) continue;
                if (otherSeen.size() > maxNodes) {
                    throw new IllegalArgumentException("E_COPY_BUDGET: opposite graph node limit");
                }
                if (store.initializing) throw new IllegalStateException("E_COPY_LAYOUT: unpublished class");
                if (seen.containsKey(store) && !retained.containsKey(store)) {
                    throw new IllegalArgumentException("E_COPY_SHALLOW_ALIAS: reattachment would share an unprotected referent");
                }
                otherEdges = enqueueChildren(store, pending, otherEdges, maxEdges);
            }
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
            replaceOn(copy, source, key, value);
        }

        /** Checked original-side replacement; does not retire loans. The
         * typed caller must route source writes through this handle whenever
         * it uses the early-detachment profile. Multiple live source handles
         * require the ordinary ownership proof, not this single-token API.
         */
        public void replaceOriginal(String key, Object value) {
            replaceOn(source, copy, key, value);
        }

        private void replaceOn(GuestClassSlots root, GuestClassSlots opposite, String key, Object value) {
            synchronized (copy.domain) {
                requireOpen();
                if (root == source && (source.snapshotRoot || source.activeSnapshots != 1)) {
                    throw new IllegalArgumentException("E_COPY_SHALLOW_ALIAS: original replacement requires sole ordinary source handle");
                }
                // This class-only snapshot never accepts a foreign, unpublished
                // or opaque replacement root. The caller still proves transitive
                // ownership and lifetime; this check cannot mint write authority.
                if (value instanceof GuestClassSlots child) {
                    validateReplacement(child, opposite);
                } else if (value != null && !CopyScalarAdmission.isImmutableScalar(value)) {
                    throw new IllegalArgumentException("E_COPY_UNPROVEN: unsupported replacement payload");
                }
                root.replace(key, value);
            }
        }
        /**
         * Trusted, proof-gated copy-side detachment. The caller must establish
         * writable slot type, no surviving untracked read aliases, and that
         * future reattachment follows the ordinary ownership rules. Only
         * fresh scalar-backed class storage is constructed by this operation.
         * Unlike generic replace(), it can retire this snapshot's own loans.
         */
        public GuestClassSlots replaceWithFresh(String key, Map<String, Object> values) {
            return replaceWithFreshOn(copy, key, values);
        }

        /** Trusted original-side replacement. Retire only this token's loans
         * when this is the sole direct snapshot of an ordinary source root.
         * Other generations and independent loans remain protected. Snapshot
         * sources must be edited through their own live snapshot handle.
         */
        public GuestClassSlots replaceOriginalWithFresh(String key, Map<String, Object> values) {
            return replaceWithFreshOn(source, key, values);
        }

        private GuestClassSlots replaceWithFreshOn(GuestClassSlots root, String key, Map<String, Object> values) {
            // Stable ordinary map supplied by trusted runtime code. No caller
            // map methods are invoked under the domain monitor.
            if (values.size() > maxEdges) throw new IllegalArgumentException("E_COPY_BUDGET: fresh fields");
            var initial = new LinkedHashMap<>(values);
            if (initial.size() > maxEdges) throw new IllegalArgumentException("E_COPY_BUDGET: fresh fields");
            for (var e : initial.entrySet()) {
                if (e.getKey() == null ||
                    (e.getValue() != null && !CopyScalarAdmission.isImmutableScalar(e.getValue()))) {
                    throw new IllegalArgumentException("E_COPY_UNPROVEN: non-scalar fresh field");
                }
            }
            synchronized (copy.domain) {
                requireOpen();
                if (root == source && source.snapshotRoot) {
                    throw new IllegalArgumentException("E_COPY_SHALLOW_ALIAS: edit source through its own live snapshot handle");
                }
                if (root.readOnlyLoans != 0) throw new IllegalArgumentException("E_COPY_READONLY: shared class referent");
                if (!root.slots.containsKey(key)) throw new IllegalArgumentException("E_COPY_LAYOUT: unknown class slot");
                boolean releaseUnreachable = root == copy || source.activeSnapshots == 1;
                var fresh = new GuestClassSlots(copy.domain, initial);
                fresh.finishInitialization();
                var seen = new IdentityHashMap<GuestClassSlots, Boolean>();
                var pending = new ArrayDeque<GuestClassSlots>();
                int edges = 0;
                for (var e : root.slots.entrySet()) {
                    if (edges >= maxEdges) throw new IllegalArgumentException("E_COPY_BUDGET: graph edge limit");
                    edges++;
                    Object value = Objects.equals(e.getKey(), key) ? fresh : e.getValue();
                    if (value instanceof GuestClassSlots child) pending.addLast(child);
                    else if (value != null && !CopyScalarAdmission.isImmutableScalar(value)) {
                        throw new IllegalArgumentException("E_COPY_UNPROVEN: unsupported graph payload");
                    }
                }
                while (!pending.isEmpty()) {
                    GuestClassSlots store = pending.removeFirst();
                    if (store.domain != copy.domain) throw new IllegalArgumentException("E_COPY_DOMAIN: foreign class slot store");
                    if (seen.put(store, Boolean.TRUE) != null) continue;
                    if (seen.size() > maxNodes) throw new IllegalArgumentException("E_COPY_BUDGET: graph node limit");
                    if (store.initializing) throw new IllegalStateException("E_COPY_LAYOUT: unpublished class");
                    if (store == source || store == copy || store.snapshotRoot || store.activeSnapshots != 0) {
                        throw new IllegalArgumentException("E_COPY_SHALLOW_ALIAS: detachment reaches a snapshot root");
                    }
                    edges = enqueueChildren(store, pending, edges, maxEdges);
                }
                var nextRetained = new IdentityHashMap<GuestClassSlots, Boolean>();
                var kept = new ArrayList<GuestClassSlots>();
                var retired = new ArrayList<GuestClassSlots>();
                for (GuestClassSlots store : lease.protectedStores) {
                    if (store.readOnlyLoans <= 0) throw stale();
                    if (!releaseUnreachable || seen.containsKey(store)) {
                        kept.add(store);
                        nextRetained.put(store, Boolean.TRUE);
                    } else {
                        retired.add(store);
                    }
                }
                List<GuestClassSlots> next = List.copyOf(kept);
                // All checks and allocations above; publish the slot and retire
                // only this token's unreachable nodes under the same monitor.
                root.slots.put(key, fresh);
                for (int i = 0; i < retired.size(); i++) retired.get(i).readOnlyLoans--;
                lease.protectedStores = next;
                retained = nextRetained;
                return fresh;
            }
        }

        /** Independent descendant retention; no reuse of the parent's lease. */
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
                if (source.activeSnapshots <= 0) throw stale();
                lease.close();
                source.activeSnapshots--;
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
            GraphLease lease = prepareDescendants(maxNodes, maxEdges, true);
            GuestClassSlots copy = new GuestClassSlots(domain, slots);
            copy.finishInitialization();
            copy.snapshotRoot = true;
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
    /** Stable, shallow read-only slot census. A live unmodifiable wrapper
     * still races with slot replacement and leaks changing Map.Entry values
     * into diagnostics, graph inspection and evaluator traversal. Capture
     * under the domain monitor; never grant authority over the referents.
     * This is not an atomic multi-object graph snapshot or a guest copy.
     */
    public Map<String, Object> view() {
        synchronized (domain) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        }
    }

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
