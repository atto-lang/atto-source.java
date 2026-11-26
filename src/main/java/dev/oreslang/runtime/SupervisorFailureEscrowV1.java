package dev.oreslang.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Experimental trusted-host CONTROL ledger. Guest supervisor policy actors
 * never own the failure notices or the authority to manufacture deliveries.
 *
 * This is not wired into ActorRuntime or physical retirement and must not be
 * treated as source-level AttoLang supervisor execution.
 */
final class SupervisorFailureEscrowV1 {
    static final int MAX_NODES = 4096;
    static final int MAX_DEPTH = 64;
    static final int MAX_DATA_ADMISSIONS_IN_FLIGHT = 65_536;

    record Notice(long failedId, long failedGeneration, long reservationSequence) {}
    record NodeStatus(long id, long parentId, long generation,
                      boolean alive, boolean fenced, boolean pending) {}

    /** Admission is logically linearized at issuance while holding this
     * ledger's monitor. A physical mailbox write may complete later; it is
     * still an operation that won admission before the ancestor fence. */
    static final class DataAdmission {
        private final Cell owner;
        private DataAdmission(Cell owner) { this.owner = owner; }
    }

    record DataAdmissionStatus(int inFlight, long committed, long aborted) {}

    /** Object identity is an unforgeable, one-use, process-local CONTROL permit. */
    static final class Delivery {
        private final Notice notice;
        private final long controllerId;
        private final long controllerGeneration;

        private Delivery(Notice notice, long controllerId, long controllerGeneration) {
            this.notice = notice;
            this.controllerId = controllerId;
            this.controllerGeneration = controllerGeneration;
        }
        Notice notice() { return notice; }
        long controllerId() { return controllerId; }
        long controllerGeneration() { return controllerGeneration; }
    }

    private static final class Cell implements SupervisorRecoveryWorkspaceV1.Node {
        final long id, parentId, generation, reservation;
        final int depth;
        final Cell parent;
        final Notice reservedNotice;
        final Delivery[] reservedDeliveries;
        boolean alive = true;
        boolean fenced;
        Notice terminal;
        boolean pending;
        Delivery delivery;
        int dataInFlight;
        long dataCommitted;
        long dataAborted;

        public long recoveryId() { return id; }
        public int recoveryDepth() { return depth; }
        public Cell recoveryParent() { return parent; }

        Cell(long id, long parentId, long generation, long reservation, int depth, Cell parent) {
            this.id = id;
            this.parentId = parentId;
            this.generation = generation;
            this.reservation = reservation;
            this.depth = depth;
            this.parent = parent;
            this.reservedNotice = new Notice(id, generation, reservation);
            // Authority can only move up the immutable parent chain. Reserve
            // one immutable delivery per possible ancestor before publication.
            this.reservedDeliveries = new Delivery[depth - 1];
            for (Cell ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
                reservedDeliveries[ancestor.depth - 1] =
                        new Delivery(reservedNotice, ancestor.id, ancestor.generation);
            }
        }
    }

    private final LinkedHashMap<Long, Cell> nodes = new LinkedHashMap<>();
    // Bounded index allocated before admission. Terminal publication does not
    // box long IDs or allocate a map iterator, even for a full ledger.
    private final Cell[] terminalIndex = new Cell[MAX_NODES];
    private int terminalIndexSize;
    private final Set<DataAdmission> dataAdmissions =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private long nextReservation = 1;
    private long rootId;

    /** Admission reserves exactly one terminal notice identity before publish. */
    synchronized void registerRoot(long id, long generation) {
        if (!nodes.isEmpty()) throw new IllegalStateException("root already registered");
        requireIdentity(id, generation);
        Cell root = new Cell(id, 0, generation, reserve(), 1, null);
        nodes.put(id, root);
        terminalIndex[terminalIndexSize++] = root;
        rootId = id;
    }

    synchronized void registerChild(long id, long parentId, long generation) {
        requireIdentity(id, generation);
        if (nodes.isEmpty()) throw new IllegalStateException("register guardian first");
        if (nodes.containsKey(id)) throw new IllegalStateException("actor identity already used");
        if (nodes.size() >= MAX_NODES) throw new IllegalStateException("CONTROL admission exhausted");
        Cell parent = requireNode(parentId);
        if (!parent.alive || parent.fenced || parent.depth >= MAX_DEPTH) {
            throw new IllegalStateException("supervisor cannot admit a child");
        }
        Cell child = new Cell(id, parentId, generation, reserve(), parent.depth + 1, parent);
        nodes.put(id, child);
        terminalIndex[terminalIndexSize++] = child;
    }

    /**
     * Reserve a child's terminal CONTROL identity and publish its runtime
     * records as one linearization point against fail()/completeNormally().
     * The publisher must be runtime-owned, must not invoke user code, and
     * must roll back its own partial registry mutations before throwing.
     * No cancellation scanner can see a ledger node without a published
     * runtime ActorRef after this monitor has been released.
     */
    synchronized void registerChildAtomically(
            long id, long parentId, long generation, Runnable publisher) {
        Objects.requireNonNull(publisher, "publisher");
        registerChild(id, parentId, generation);
        try {
            publisher.run();
        } catch (RuntimeException | Error failure) {
            Cell removed = nodes.get(id);
            if (removed == null || removed.terminal != null || !removed.alive
                    || removed.dataInFlight != 0 || hasDescendants(removed)) {
                throw new IllegalStateException(
                        "unpublished CONTROL child rollback invariant violated", failure);
            }
            nodes.remove(id);
            removeTerminalIndex(removed);
            throw failure;
        }
    }

    /** In-flight descendants may themselves fail after an ancestor is fenced. */
    synchronized Notice fail(long id) {
        Cell cell = requireTerminalNode(id);
        if (id == rootId) throw new IllegalStateException("guardian failure requires external supervisor");
        if (cell.terminal != null) return cell.terminal; // terminal replay is inert
        if (!cell.alive) {
            throw new IllegalStateException("cannot convert a clean STOP into a later failure");
        }
        cell.alive = false;
        cell.fenced = true;
        cell.terminal = cell.reservedNotice;
        cell.pending = true;
        fenceDescendants(id);
        return cell.terminal;
    }

    /** Normal/cancelled finalization never manufactures a failure notice.
     * A supervisor exiting normally still fences descendants; the caller
     * must arrange structured child cleanup outside this pure ledger. */
    synchronized void completeNormally(long id) {
        Cell cell = requireTerminalNode(id);
        if (id == rootId) throw new IllegalStateException("guardian requires external supervisor");
        if (cell.pending || cell.terminal != null) {
            // A real failure may not be laundered into a clean termination.
            throw new IllegalStateException("cannot erase a failed terminal notice");
        }
        cell.alive = false;
        cell.fenced = true;
        fenceDescendants(id);
    }

    /** Returns the nearest healthy ancestor's CONTROL delivery, never guest DATA. */
    synchronized Delivery deliver(long failedId) {
        Cell cell = requireTerminalNode(failedId);
        if (!cell.pending) return null;
        Cell authority = nearestLiveAncestor(cell);
        if (authority == null) throw new IllegalStateException("no healthy CONTROL ancestor");
        if (cell.delivery == null || cell.delivery.controllerId != authority.id
                || cell.delivery.controllerGeneration != authority.generation) {
            cell.delivery = cell.reservedDeliveries[authority.depth - 1];
        }
        return cell.delivery;
    }

    /** Validate a delivery before invoking a potentially external decision sink.
     * This check MUST be repeated after that sink completes. */
    synchronized boolean currentDelivery(Delivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        Cell cell = findTerminalNode(delivery.notice.failedId());
        if (cell == null || !cell.pending || cell.delivery != delivery
                || cell.terminal != delivery.notice
                || cell.generation != delivery.notice.failedGeneration()) return false;
        Cell currentAuthority = nearestLiveAncestor(cell);
        return currentAuthority != null && currentAuthority.id == delivery.controllerId
                && currentAuthority.generation == delivery.controllerGeneration;
    }

    /** A stale delivery cannot be acknowledged even before it is redelivered. */
    synchronized boolean acknowledge(Delivery delivery) {
        if (!currentDelivery(delivery)) return false;
        Cell cell = findTerminalNode(delivery.notice.failedId());
        cell.pending = false;
        cell.delivery = null;
        return true;
    }

    synchronized boolean mayAdmit(long id, long generation) {
        Cell cell = nodes.get(id);
        return cell != null && cell.alive && !cell.fenced && cell.generation == generation;
    }

    /** Reserve one logical DATA admission at a single atomic point shared
     * with fail() and completeNormally(). Returns null for any stale/fenced
     * generation. No transport callback is executed while the ledger is held. */
    synchronized DataAdmission beginDataAdmission(long id, long generation) {
        if (!mayAdmit(id, generation)) return null;
        if (dataAdmissions.size() >= MAX_DATA_ADMISSIONS_IN_FLIGHT) {
            throw new IllegalStateException("CONTROL DATA admission permits exhausted");
        }
        Cell owner = requireNode(id);
        if (owner.dataInFlight == Integer.MAX_VALUE) {
            throw new IllegalStateException("actor DATA admission count exhausted");
        }
        // Any currently in-flight operation may commit OR abort. Reserve
        // enough headroom in BOTH counters before granting another permit,
        // so a valid mailbox write never discovers a fatal accounting
        // overflow while settling after publication.
        long needed = (long) owner.dataInFlight + 1L;
        if (owner.dataCommitted > Long.MAX_VALUE - needed
                || owner.dataAborted > Long.MAX_VALUE - needed) {
            throw new IllegalStateException("actor DATA accounting sequence exhausted");
        }
        DataAdmission permit = new DataAdmission(owner);
        if (!dataAdmissions.add(permit)) {
            throw new IllegalStateException("duplicate DATA admission permit");
        }
        owner.dataInFlight++;
        return permit;
    }

    /** Must be called once after a mailbox write succeeds or is rolled back.
     * It remains legal after an ancestor crashes, because the admission
     * logically preceded the fence. Foreign/replayed permits are rejected. */
    synchronized void finishDataAdmission(DataAdmission permit, boolean committed) {
        Objects.requireNonNull(permit, "permit");
        if (!dataAdmissions.contains(permit) || permit.owner.dataInFlight <= 0) {
            throw new IllegalStateException("foreign or replayed DATA admission");
        }
        // Compute first, then consume identity and decrement the outstanding
        // reservation. An invariant violation must leave the permit pinned,
        // not make an accepted mailbox write look physically unaccounted for.
        long next = committed
                ? Math.addExact(permit.owner.dataCommitted, 1L)
                : Math.addExact(permit.owner.dataAborted, 1L);
        if (!dataAdmissions.remove(permit)) {
            throw new IllegalStateException("DATA admission permit concurrently consumed");
        }
        permit.owner.dataInFlight--;
        if (committed) permit.owner.dataCommitted = next;
        else permit.owner.dataAborted = next;
    }

    /** Descendant retirement must wait until every admission reserved before
     * the fence has settled, even if it was granted to a future descendant. */
    synchronized int dataInFlightInSubtree(long root) {
        requireNode(root);
        int total = 0;
        for (Cell cell : nodes.values()) {
            if (cell.id == root || isDescendantOf(cell, root)) {
                total = Math.addExact(total, cell.dataInFlight);
            }
        }
        return total;
    }

    synchronized DataAdmissionStatus dataAdmissionStatus(long id) {
        Cell cell = requireNode(id);
        return new DataAdmissionStatus(
                cell.dataInFlight, cell.dataCommitted, cell.dataAborted);
    }

    synchronized int pendingCount() {
        int count = 0;
        for (int i = 0; i < terminalIndexSize; i++) if (terminalIndex[i].pending) count++;
        return count;
    }

    /** Capture deepest-first cancellation membership after the root fence.
     * No new descendant can be admitted under a fenced ancestor. The caller
     * owns the reusable output and invokes cancellation outside this monitor. */
    synchronized int copyFencedSubtreeDeepestFirst(long root, long[] output) {
        Cell cell = requireTerminalNode(root);
        if (!cell.fenced) throw new IllegalStateException("recovery root not fenced");
        return SupervisorRecoveryWorkspaceV1.collect(
                terminalIndex, terminalIndexSize, cell, MAX_DEPTH, output);
    }

    synchronized Map<Long, NodeStatus> snapshot() {
        Map<Long, NodeStatus> statuses = new LinkedHashMap<>();
        for (Cell cell : nodes.values()) {
            statuses.put(cell.id, new NodeStatus(cell.id, cell.parentId,
                    cell.generation, cell.alive, cell.fenced, cell.pending));
        }
        return Map.copyOf(statuses);
    }

    private Cell nearestLiveAncestor(Cell cell) {
        for (Cell parent = cell.parent; parent != null; parent = parent.parent) {
            if (parent.alive && !parent.fenced) return parent;
        }
        return null;
    }

    private boolean isDescendantOf(Cell child, long ancestorId) {
        for (Cell parent = child.parent; parent != null; parent = parent.parent) {
            if (parent.id == ancestorId) return true;
        }
        return false;
    }

    private void fenceDescendants(long id) {
        for (int i = 0; i < terminalIndexSize; i++) {
            Cell descendant = terminalIndex[i];
            if (isDescendantOf(descendant, id)) descendant.fenced = true;
        }
    }

    private boolean hasDescendants(Cell cell) {
        for (int i = 0; i < terminalIndexSize; i++) {
            if (isDescendantOf(terminalIndex[i], cell.id)) return true;
        }
        return false;
    }

    private Cell findTerminalNode(long id) {
        for (int i = 0; i < terminalIndexSize; i++) {
            Cell cell = terminalIndex[i];
            if (cell.id == id) return cell;
        }
        return null;
    }

    private Cell requireTerminalNode(long id) {
        Cell cell = findTerminalNode(id);
        if (cell == null) throw new IllegalArgumentException("unknown CONTROL identity");
        return cell;
    }

    private void removeTerminalIndex(Cell cell) {
        for (int i = 0; i < terminalIndexSize; i++) {
            if (terminalIndex[i] == cell) {
                terminalIndex[i] = terminalIndex[--terminalIndexSize];
                terminalIndex[terminalIndexSize] = null;
                return;
            }
        }
        throw new IllegalStateException("missing CONTROL terminal index");
    }

    private Cell requireNode(long id) {
        Cell cell = nodes.get(id);
        if (cell == null) throw new IllegalArgumentException("unknown CONTROL identity");
        return cell;
    }

    private long reserve() {
        if (nextReservation == Long.MAX_VALUE) {
            throw new IllegalStateException("CONTROL sequence exhausted");
        }
        return nextReservation++;
    }

    private static void requireIdentity(long id, long generation) {
        if (id <= 0 || generation <= 0) throw new IllegalArgumentException("invalid identity");
    }
}
