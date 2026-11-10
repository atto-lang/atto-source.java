package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    record Notice(long failedId, long failedGeneration, long reservationSequence) {}
    record NodeStatus(long id, long parentId, long generation,
                      boolean alive, boolean fenced, boolean pending) {}

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

    private static final class Cell {
        final long id, parentId, generation, reservation;
        final int depth;
        boolean alive = true;
        boolean fenced;
        Notice terminal;
        boolean pending;
        Delivery delivery;

        Cell(long id, long parentId, long generation, long reservation, int depth) {
            this.id = id;
            this.parentId = parentId;
            this.generation = generation;
            this.reservation = reservation;
            this.depth = depth;
        }
    }

    private final LinkedHashMap<Long, Cell> nodes = new LinkedHashMap<>();
    private long nextReservation = 1;
    private long rootId;

    /** Admission reserves exactly one terminal notice identity before publish. */
    synchronized void registerRoot(long id, long generation) {
        if (!nodes.isEmpty()) throw new IllegalStateException("root already registered");
        requireIdentity(id, generation);
        Cell root = new Cell(id, 0, generation, reserve(), 1);
        nodes.put(id, root);
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
        nodes.put(id, new Cell(id, parentId, generation, reserve(), parent.depth + 1));
    }

    /** In-flight descendants may themselves fail after an ancestor is fenced. */
    synchronized Notice fail(long id) {
        Cell cell = requireNode(id);
        if (id == rootId) throw new IllegalStateException("guardian failure requires external supervisor");
        if (cell.terminal != null) return cell.terminal; // terminal replay is inert
        if (!cell.alive) {
            throw new IllegalStateException("cannot convert a clean STOP into a later failure");
        }
        cell.alive = false;
        cell.fenced = true;
        cell.terminal = new Notice(id, cell.generation, cell.reservation);
        cell.pending = true;
        for (Cell descendant : nodes.values()) {
            if (isDescendantOf(descendant, id)) descendant.fenced = true;
        }
        return cell.terminal;
    }

    /** Normal/cancelled finalization never manufactures a failure notice.
     * A supervisor exiting normally still fences descendants; the caller
     * must arrange structured child cleanup outside this pure ledger. */
    synchronized void completeNormally(long id) {
        Cell cell = requireNode(id);
        if (id == rootId) throw new IllegalStateException("guardian requires external supervisor");
        if (cell.pending || cell.terminal != null) {
            // A real failure may not be laundered into a clean termination.
            throw new IllegalStateException("cannot erase a failed terminal notice");
        }
        cell.alive = false;
        cell.fenced = true;
        for (Cell descendant : nodes.values()) {
            if (isDescendantOf(descendant, id)) descendant.fenced = true;
        }
    }

    /** Returns the nearest healthy ancestor's CONTROL delivery, never guest DATA. */
    synchronized Delivery deliver(long failedId) {
        Cell cell = requireNode(failedId);
        if (!cell.pending) return null;
        Cell authority = nearestLiveAncestor(cell);
        if (authority == null) throw new IllegalStateException("no healthy CONTROL ancestor");
        if (cell.delivery == null || cell.delivery.controllerId != authority.id
                || cell.delivery.controllerGeneration != authority.generation) {
            cell.delivery = new Delivery(cell.terminal, authority.id, authority.generation);
        }
        return cell.delivery;
    }

    /** Validate a delivery before invoking a potentially external decision sink.
     * This check MUST be repeated after that sink completes. */
    synchronized boolean currentDelivery(Delivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        Cell cell = nodes.get(delivery.notice.failedId());
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
        Cell cell = nodes.get(delivery.notice.failedId());
        cell.pending = false;
        cell.delivery = null;
        return true;
    }

    synchronized boolean mayAdmit(long id, long generation) {
        Cell cell = nodes.get(id);
        return cell != null && cell.alive && !cell.fenced && cell.generation == generation;
    }

    synchronized int pendingCount() {
        int count = 0;
        for (Cell cell : nodes.values()) if (cell.pending) count++;
        return count;
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
        long parentId = cell.parentId;
        while (parentId != 0) {
            Cell parent = requireNode(parentId);
            if (parent.alive && !parent.fenced) return parent;
            parentId = parent.parentId;
        }
        return null;
    }

    private boolean isDescendantOf(Cell child, long ancestorId) {
        long parentId = child.parentId;
        while (parentId != 0) {
            if (parentId == ancestorId) return true;
            parentId = requireNode(parentId).parentId;
        }
        return false;
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
