package dev.oreslang.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, acyclic, typed-CFG oracle for affine protocol handles and request
 * leases. This checks submitted compiler IR; it does not authenticate source,
 * runtime EOF, actor generations, cancellation, or backend code generation.
 * No successful result from this class may enable executable protocol lowering.
 */
public final class ActorProtocolAffineCfgV1 {
    private ActorProtocolAffineCfgV1() { }

    public static final int MAX_BLOCKS = 1024;
    public static final int MAX_EDGES = 4096;
    public static final int MAX_OPERATIONS = 16384;
    public static final int MAX_HANDLES = 128;
    public static final int MAX_LEASES = 256;
    public static final int MAX_LOANS = 1024;

    public enum LoanKind { READ, EXCLUSIVE }

    public sealed interface Op permits Init, Take, Copy, Borrow, EndBorrow,
            Begin, MoveLease, AwaitUnary, AwaitStreamStep, CancelLease,
            DisposeTerminal, ReturnHandle, Suspend, Return { }

    /** Must be sourced from compiler-verified actor spawn, not a guest call. */
    public record Init(int place, String protocol) implements Op { }
    public record Take(int from, int into) implements Op { }
    /** Deliberately invalid for affine protocol authority. */
    public record Copy(int from, int into) implements Op { }
    public record Borrow(int place, int loan, LoanKind kind) implements Op { }
    public record EndBorrow(int loan) implements Op { }
    /** Moves state authority into a request session before any suspension. */
    public record Begin(int from, int lease, String message,
                        String payloadType, ActorProtocolTypestateV1.Mode mode,
                        String responseType) implements Op { }
    /** Moves a session handle, e.g. into an awaiting task frame. */
    public record MoveLease(int from, int into) implements Op { }
    /** Four successors: [SUCCESS, ERROR, TIMEOUT, CANCEL]. */
    public record AwaitUnary(int lease, int into) implements Op { }
    /** Five successors: [ITEM, EOF, ERROR, TIMEOUT, CANCEL]. */
    public record AwaitStreamStep(int lease, int into) implements Op { }
    /** Explicitly abandons a lease: NEVER returns successor authority. */
    public record CancelLease(int lease) implements Op { }
    public record DisposeTerminal(int place) implements Op { }
    /** Consumes the capability into the caller; caller return typing is external. */
    public record ReturnHandle(int place) implements Op { }
    public record Suspend() implements Op { }
    public record Return() implements Op { }

    public record Block(int id, List<Op> operations, List<Integer> successors) {
        public Block {
            operations = List.copyOf(operations);
            successors = List.copyOf(successors);
        }
    }
    public record Program(int entry, boolean suspending, List<Block> blocks) {
        public Program { blocks = List.copyOf(blocks); }
    }
    public record Proof(int verifiedBlocks, int verifiedOperations) { }

    private record Owner(String protocol, Set<String> states, boolean maybeAbsent) {
        private Owner { states = Set.copyOf(states); }
        private Owner moved() { return new Owner(protocol, Set.of(), true); }
    }
    private record Lease(String protocol, Set<String> successors,
                         ActorProtocolTypestateV1.Mode mode, String payloadType,
                         String responseType) {
        private Lease { successors = Set.copyOf(successors); }
    }
    private record Loan(int place, LoanKind kind) { }
    private static final class Facts {
        Map<Integer, Owner> owners = new LinkedHashMap<>();
        Map<Integer, Lease> leases = new LinkedHashMap<>();
        Map<Integer, Loan> loans = new LinkedHashMap<>();
        Facts copy() {
            Facts result = new Facts();
            result.owners.putAll(owners);
            result.leases.putAll(leases);
            result.loans.putAll(loans);
            return result;
        }
    }

    private static IllegalArgumentException error(String code, int block, int op) {
        return new IllegalArgumentException(code + " at block " + block + " op " + op);
    }
    private static Owner live(Facts facts, int id, int block, int op) {
        Owner owner = facts.owners.get(id);
        if (owner == null || owner.maybeAbsent() || owner.states().isEmpty())
            throw error("E_PROTOCOL_HANDLE_NOT_LIVE", block, op);
        return owner;
    }
    private static Lease lease(Facts facts, int id, int block, int op) {
        Lease session = facts.leases.get(id);
        if (session == null) throw error("E_PROTOCOL_LEASE_NOT_LIVE", block, op);
        return session;
    }
    private static boolean loanOn(Facts facts, int place) {
        return facts.loans.values().stream().anyMatch(loan -> loan.place() == place);
    }
    private static void target(Facts facts, int place, int block, int op) {
        Owner previous = facts.owners.get(place);
        if (previous != null && (!previous.maybeAbsent() || !previous.states().isEmpty()))
            throw error("E_PROTOCOL_TARGET_LIVE", block, op);
        if (previous == null && facts.owners.size() >= MAX_HANDLES)
            throw error("E_PROTOCOL_HANDLE_BUDGET", block, op);
    }
    private static void requireNoLoans(Facts facts, int block, int op) {
        if (!facts.loans.isEmpty()) throw error("E_PROTOCOL_LOAN_ACROSS_AWAIT", block, op);
    }

    private static Facts join(Facts left, Facts right, int block) {
        if (!left.loans.equals(right.loans))
            throw error("E_PROTOCOL_LOAN_JOIN", block, 0);
        if (!left.leases.equals(right.leases))
            throw error("E_PROTOCOL_LEASE_JOIN", block, 0);
        Facts result = left.copy();
        Set<Integer> ids = new LinkedHashSet<>(left.owners.keySet());
        ids.addAll(right.owners.keySet());
        if (ids.size() > MAX_HANDLES)
            throw error("E_PROTOCOL_HANDLE_BUDGET", block, 0);
        for (int id : ids) {
            Owner a = left.owners.get(id), b = right.owners.get(id);
            if (a == null && b == null) continue;
            if (a == null || b == null) {
                Owner present = a == null ? b : a;
                result.owners.put(id, new Owner(present.protocol(), present.states(), true));
            } else {
                if (!a.protocol().equals(b.protocol()))
                    throw error("E_PROTOCOL_HANDLE_JOIN", block, 0);
                Set<String> states = new LinkedHashSet<>(a.states());
                states.addAll(b.states());
                result.owners.put(id, new Owner(a.protocol(), states,
                        a.maybeAbsent() || b.maybeAbsent()));
            }
        }
        return result;
    }

    /**
     * Prefer this link from production source analysis: it rechecks that all
     * declarations have a real nominal unary/stream message proof. The CFG
     * operation/event provenance must STILL come from typed MIR; this method
     * does not authorize guest lowering or runtime-issued handle creation.
     */
    public static Proof verifyNominallyResolved(
            ActorProtocolNominalResolverV1.ResolvedDeclarations declarations, Program program) {
        Objects.requireNonNull(declarations, "nominal declarations");
        declarations.protocol().verifyResolvedMessages(declarations.messages());
        return verify(declarations.protocol(), program);
    }

    /**
     * This is a proof of only the *submitted* source-derived CFG. Every edge,
     * init provenance, source operation and authenticated terminal event must
     * be independently matched to typed compiler IR and eventual runtime ABI.
     */
    public static Proof verify(ActorProtocolTypestateV1.Catalog catalog, Program program) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(program, "program");
        if (program.blocks().isEmpty() || program.blocks().size() > MAX_BLOCKS)
            throw error("E_PROTOCOL_CFG_BUDGET", program.entry(), 0);
        Map<Integer, Block> blocks = new LinkedHashMap<>();
        Map<Integer, Integer> indegree = new HashMap<>();
        int totalOps = 0, totalEdges = 0;
        Set<Integer> initIds = new HashSet<>();
        Set<Integer> issuedLeaseIds = new HashSet<>();
        Set<Integer> issuedLoanIds = new HashSet<>();
        for (Block block : program.blocks()) {
            if (blocks.putIfAbsent(block.id(), block) != null)
                throw error("E_PROTOCOL_CFG_DUPLICATE", block.id(), 0);
            indegree.put(block.id(), 0);
            totalOps += block.operations().size();
            totalEdges += block.successors().size();
            if (block.operations().size() > 1024 || totalOps > MAX_OPERATIONS
                    || totalEdges > MAX_EDGES)
                throw error("E_PROTOCOL_CFG_BUDGET", block.id(), 0);
            if (block.successors().isEmpty()) {
                if (block.operations().isEmpty()
                        || !(block.operations().getLast() instanceof Return))
                    throw error("E_PROTOCOL_IMPLICIT_EXIT", block.id(), block.operations().size());
            }
            for (int i = 0; i < block.operations().size(); i++) {
                Op op = block.operations().get(i);
                if (op instanceof Init init) {
                    if (block.id() != program.entry() || !initIds.add(init.place())
                            || initIds.size() > MAX_HANDLES)
                        throw error("E_PROTOCOL_INIT_PROVENANCE", block.id(), i);
                }
                if (op instanceof Begin begin) {
                    if (!issuedLeaseIds.add(begin.lease()) || issuedLeaseIds.size() > MAX_LEASES)
                        throw error("E_PROTOCOL_LEASE_ID", block.id(), i);
                }
                if (op instanceof MoveLease move) {
                    if (!issuedLeaseIds.add(move.into()) || issuedLeaseIds.size() > MAX_LEASES)
                        throw error("E_PROTOCOL_LEASE_ID", block.id(), i);
                }
                if (op instanceof Borrow borrow) {
                    if (!issuedLoanIds.add(borrow.loan()) || issuedLoanIds.size() > MAX_LOANS)
                        throw error("E_PROTOCOL_LOAN_ID", block.id(), i);
                }
                if (op instanceof Return && (i + 1 != block.operations().size()
                        || !block.successors().isEmpty()))
                    throw error("E_PROTOCOL_CFG_SHAPE", block.id(), i);
                if (op instanceof AwaitUnary || op instanceof AwaitStreamStep) {
                    int expected = op instanceof AwaitUnary ? 4 : 5;
                    if (i + 1 != block.operations().size()
                            || block.successors().size() != expected)
                        throw error("E_PROTOCOL_OUTCOME_SHAPE", block.id(), i);
                }
            }
        }
        if (!blocks.containsKey(program.entry()))
            throw error("E_PROTOCOL_CFG_ENTRY", program.entry(), 0);
        for (Block block : program.blocks()) {
            Set<Integer> distinct = new HashSet<>();
            for (int to : block.successors()) {
                if (!distinct.add(to) || !indegree.containsKey(to))
                    throw error("E_PROTOCOL_CFG_EDGE", block.id(), 0);
                indegree.merge(to, 1, Integer::sum);
            }
        }
        Set<Integer> reached = new HashSet<>();
        ArrayDeque<Integer> discovery = new ArrayDeque<>(List.of(program.entry()));
        while (!discovery.isEmpty()) {
            int id = discovery.remove();
            if (reached.add(id)) discovery.addAll(blocks.get(id).successors());
        }
        if (reached.size() != blocks.size())
            throw error("E_PROTOCOL_CFG_UNREACHABLE", program.entry(), 0);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (var entry : indegree.entrySet()) if (entry.getValue() == 0) queue.add(entry.getKey());
        List<Integer> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            int id = queue.remove();
            order.add(id);
            for (int next : blocks.get(id).successors()) {
                int degree = indegree.merge(next, -1, Integer::sum);
                if (degree == 0) queue.add(next);
            }
        }
        if (order.size() != blocks.size())
            throw error("E_PROTOCOL_CFG_CYCLE", program.entry(), 0);

        Map<Integer, Facts> incoming = new LinkedHashMap<>();
        incoming.put(program.entry(), new Facts());
        for (int id : order) {
            Block block = blocks.get(id);
            Facts facts = incoming.remove(id);
            if (facts == null) throw error("E_PROTOCOL_CFG_UNREACHABLE", id, 0);
            int pendingLease = -1, successInto = -1;
            boolean streamStep = false, hasOutcome = false;
            for (int i = 0; i < block.operations().size(); i++) {
                Op op = block.operations().get(i);
                if (op instanceof Init init) {
                    var p = catalog.protocols().stream().filter(v -> v.name().equals(init.protocol()))
                            .findFirst().orElse(null);
                    if (p == null) throw error("E_PROTOCOL_UNKNOWN", id, i);
                    target(facts, init.place(), id, i);
                    facts.owners.put(init.place(), new Owner(init.protocol(), Set.of(p.initial()), false));
                } else if (op instanceof Take take) {
                    if (take.from() == take.into())
                        throw error("E_PROTOCOL_ALIAS_TAKE", id, i);
                    Owner own = live(facts, take.from(), id, i);
                    if (loanOn(facts, take.from())) throw error("E_PROTOCOL_LOAN_CONFLICT", id, i);
                    target(facts, take.into(), id, i);
                    facts.owners.put(take.into(), own);
                    facts.owners.put(take.from(), own.moved());
                } else if (op instanceof Copy copy) {
                    live(facts, copy.from(), id, i);
                    throw error("E_PROTOCOL_AFFINE_COPY", id, i);
                } else if (op instanceof Borrow borrow) {
                    live(facts, borrow.place(), id, i);
                    if (facts.loans.containsKey(borrow.loan())
                            || facts.loans.values().stream().anyMatch(v -> v.place() == borrow.place()
                            && (borrow.kind() == LoanKind.EXCLUSIVE || v.kind() == LoanKind.EXCLUSIVE)))
                        throw error("E_PROTOCOL_LOAN_CONFLICT", id, i);
                    facts.loans.put(borrow.loan(), new Loan(borrow.place(), borrow.kind()));
                } else if (op instanceof EndBorrow end) {
                    if (facts.loans.remove(end.loan()) == null)
                        throw error("E_PROTOCOL_UNKNOWN_LOAN", id, i);
                } else if (op instanceof Begin begin) {
                    Owner own = live(facts, begin.from(), id, i);
                    if (loanOn(facts, begin.from()))
                        throw error("E_PROTOCOL_LOAN_CONFLICT", id, i);
                    var checked = catalog.checkSend(own.protocol(), new ArrayList<>(own.states()),
                            begin.message());
                    if (checked.mode() != begin.mode())
                        throw error("E_PROTOCOL_RESPONSE_MODE", id, i);
                    if (!Objects.equals(checked.responseType(), begin.responseType()))
                        throw error("E_PROTOCOL_RESPONSE_TYPE", id, i);
                    if (!Objects.equals(checked.payloadType(), begin.payloadType()))
                        throw error("E_PROTOCOL_MESSAGE_PAYLOAD", id, i);
                    if (facts.leases.containsKey(begin.lease()))
                        throw error("E_PROTOCOL_LEASE_ID", id, i);
                    facts.leases.put(begin.lease(), new Lease(own.protocol(),
                            new LinkedHashSet<>(checked.nextStates()), checked.mode(),
                            checked.payloadType(), checked.responseType()));
                    facts.owners.put(begin.from(), own.moved());
                } else if (op instanceof MoveLease move) {
                    if (move.from() == move.into())
                        throw error("E_PROTOCOL_ALIAS_TAKE", id, i);
                    Lease source = lease(facts, move.from(), id, i);
                    if (facts.leases.containsKey(move.into()))
                        throw error("E_PROTOCOL_LEASE_ID", id, i);
                    facts.leases.remove(move.from());
                    facts.leases.put(move.into(), source);
                } else if (op instanceof CancelLease cancel) {
                    lease(facts, cancel.lease(), id, i);
                    facts.leases.remove(cancel.lease());
                    // Cancellation grants no state proof or successor handle.
                } else if (op instanceof AwaitUnary await) {
                    if (!program.suspending()) throw error("E_PROTOCOL_AWAIT_CONTEXT", id, i);
                    requireNoLoans(facts, id, i);
                    Lease session = lease(facts, await.lease(), id, i);
                    if (session.mode() != ActorProtocolTypestateV1.Mode.UNARY)
                        throw error("E_PROTOCOL_RESPONSE_MODE", id, i);
                    target(facts, await.into(), id, i);
                    pendingLease = await.lease(); successInto = await.into(); hasOutcome = true;
                } else if (op instanceof AwaitStreamStep await) {
                    if (!program.suspending()) throw error("E_PROTOCOL_AWAIT_CONTEXT", id, i);
                    requireNoLoans(facts, id, i);
                    Lease session = lease(facts, await.lease(), id, i);
                    if (session.mode() != ActorProtocolTypestateV1.Mode.STREAM)
                        throw error("E_PROTOCOL_RESPONSE_MODE", id, i);
                    target(facts, await.into(), id, i);
                    pendingLease = await.lease(); successInto = await.into();
                    streamStep = true; hasOutcome = true;
                } else if (op instanceof Suspend) {
                    if (!program.suspending()) throw error("E_PROTOCOL_AWAIT_CONTEXT", id, i);
                    requireNoLoans(facts, id, i);
                } else if (op instanceof DisposeTerminal dispose) {
                    Owner owner = live(facts, dispose.place(), id, i);
                    if (loanOn(facts, dispose.place()))
                        throw error("E_PROTOCOL_LOAN_CONFLICT", id, i);
                    var p = catalog.protocols().stream().filter(v -> v.name().equals(owner.protocol()))
                            .findFirst().orElse(null);
                    if (p == null) throw error("E_PROTOCOL_UNKNOWN", id, i);
                    if (!p.terminalStates().containsAll(owner.states()))
                        throw error("E_PROTOCOL_NONTERMINAL_DISPOSE", id, i);
                    facts.owners.put(dispose.place(), owner.moved());
                } else if (op instanceof ReturnHandle returned) {
                    Owner owner = live(facts, returned.place(), id, i);
                    if (loanOn(facts, returned.place()))
                        throw error("E_PROTOCOL_LOAN_CONFLICT", id, i);
                    facts.owners.put(returned.place(), owner.moved());
                } else if (op instanceof Return) {
                    if (!facts.loans.isEmpty()) throw error("E_PROTOCOL_LOAN_ESCAPE", id, i);
                    if (!facts.leases.isEmpty()) throw error("E_PROTOCOL_LEASE_ESCAPE", id, i);
                    if (facts.owners.values().stream().anyMatch(v -> !v.maybeAbsent()))
                        throw error("E_PROTOCOL_HANDLE_ESCAPE", id, i);
                } else {
                    throw error("E_PROTOCOL_UNVERIFIED_OP", id, i);
                }
            }
            for (int edge = 0; edge < block.successors().size(); edge++) {
                int next = block.successors().get(edge);
                Facts outgoing = facts.copy();
                if (hasOutcome) {
                    Lease session = lease(outgoing, pendingLease, id, block.operations().size());
                    boolean successful = streamStep ? edge == 1 : edge == 0;
                    boolean item = streamStep && edge == 0;
                    if (!item) outgoing.leases.remove(pendingLease);
                    if (successful) {
                        outgoing.owners.put(successInto, new Owner(session.protocol(),
                                session.successors(), false));
                    }
                    // Error, timeout and cancellation retire the lease but
                    // never manufacture a successor state authority.
                }
                if (incoming.containsKey(next))
                    incoming.put(next, join(incoming.get(next), outgoing, next));
                else incoming.put(next, outgoing);
            }
        }
        return new Proof(blocks.size(), totalOps);
    }
}
