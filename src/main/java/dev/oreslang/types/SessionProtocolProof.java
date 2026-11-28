package dev.oreslang.types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A static, finite, per-session actor protocol proof over a compiler CFG.
 *
 * <p>Every incoming state to a block must admit every instruction in that
 * block. A join is the union of all possible phases, not a guess at the
 * "best" phase. Accepted work consumes the old phase immediately; timeout
 * yields indeterminate ownership and never restores a consumed phase.
 *
 * <p>This is a typed-IR helper, not yet source syntax, runtime admission,
 * full actor crash semantics or a Native Image executable proof.
 */
public final class SessionProtocolProof {
    private SessionProtocolProof() { }

    public enum Phase {
        UNMINTED, CREATED, AUTH_PENDING, AUTH_UNCERTAIN, AUTHORIZED,
        CAPTURE_PENDING, CAPTURED, CLOSED
    }

    public enum Action {
        MINT, ADMIT_AUTH, AUTH_TIMEOUT, SETTLE_AUTH, ADMIT_CAPTURE,
        SETTLE_CAPTURE, CLOSE, REUSE_CREATED, REUSE_AUTHORIZED,
        COPY_HANDLE, CROSS_GENERATION
    }

    public record Block(List<Action> actions, List<Integer> successors) {
        public Block {
            actions = List.copyOf(actions);
            successors = List.copyOf(successors);
        }
    }

    public record Graph(long sessionEpoch, long compiledGeneration, List<Block> blocks) {
        public Graph {
            blocks = List.copyOf(blocks);
            if (sessionEpoch <= 0 || compiledGeneration <= 0
                    || blocks.isEmpty() || blocks.size() > 4096) {
                throw new IllegalArgumentException("protocol CFG needs bounded authenticated identities");
            }
            int edges = 0;
            for (Block block : blocks) {
                edges += block.successors().size();
                for (int successor : block.successors()) {
                    if (successor < 0 || successor >= blocks.size()) {
                        throw new IllegalArgumentException("protocol CFG edge is out of range");
                    }
                }
            }
            if (edges > 16384) {
                throw new IllegalArgumentException("protocol CFG edge budget exceeded");
            }
        }
    }

    public record Result(List<Set<Phase>> incoming, List<Set<Phase>> outgoing) {
        public Result {
            incoming = copyStates(incoming);
            outgoing = copyStates(outgoing);
        }
    }

    private static List<Set<Phase>> copyStates(List<Set<Phase>> states) {
        return states.stream().map(Set::copyOf).toList();
    }

    public static Result verify(Graph graph) {
        Objects.requireNonNull(graph, "graph");
        var incoming = new ArrayList<EnumSet<Phase>>();
        var outgoing = new ArrayList<EnumSet<Phase>>();
        for (int i = 0; i < graph.blocks().size(); ++i) {
            incoming.add(EnumSet.noneOf(Phase.class));
            outgoing.add(EnumSet.noneOf(Phase.class));
        }
        incoming.get(0).add(Phase.UNMINTED);
        var work = new ArrayDeque<Integer>();
        var queued = new HashSet<Integer>();
        work.add(0);
        queued.add(0);
        while (!work.isEmpty()) {
            int blockId = work.removeFirst();
            queued.remove(blockId);
            EnumSet<Phase> next = EnumSet.copyOf(incoming.get(blockId));
            for (Action action : graph.blocks().get(blockId).actions()) {
                EnumSet<Phase> updated = EnumSet.noneOf(Phase.class);
                for (Phase phase : next) {
                    updated.add(transition(action, phase));
                }
                next = updated;
            }
            if (!next.equals(outgoing.get(blockId))) {
                outgoing.set(blockId, next);
                for (int successor : graph.blocks().get(blockId).successors()) {
                    if (incoming.get(successor).addAll(next) && queued.add(successor)) {
                        work.addLast(successor);
                    }
                }
            }
        }
        return new Result(
                incoming.stream().map(s -> (Set<Phase>) s).toList(),
                outgoing.stream().map(s -> (Set<Phase>) s).toList());
    }

    private static Phase transition(Action action, Phase phase) {
        return switch (action) {
            case MINT -> require(action, phase, Phase.UNMINTED, Phase.CREATED);
            case ADMIT_AUTH -> require(action, phase, Phase.CREATED, Phase.AUTH_PENDING);
            case AUTH_TIMEOUT -> require(action, phase, Phase.AUTH_PENDING, Phase.AUTH_UNCERTAIN);
            case SETTLE_AUTH -> {
                if (phase != Phase.AUTH_PENDING && phase != Phase.AUTH_UNCERTAIN) {
                    throw new IllegalArgumentException("protocol action SETTLE_AUTH invalid in " + phase);
                }
                yield Phase.AUTHORIZED;
            }
            case ADMIT_CAPTURE -> require(action, phase, Phase.AUTHORIZED, Phase.CAPTURE_PENDING);
            case SETTLE_CAPTURE -> require(action, phase, Phase.CAPTURE_PENDING, Phase.CAPTURED);
            case CLOSE -> {
                if (phase != Phase.CREATED && phase != Phase.AUTHORIZED
                        && phase != Phase.CAPTURED) {
                    throw new IllegalArgumentException("protocol action CLOSE invalid in " + phase);
                }
                yield Phase.CLOSED;
            }
            case REUSE_CREATED -> require(action, phase, Phase.CREATED, Phase.CREATED);
            case REUSE_AUTHORIZED -> require(action, phase, Phase.AUTHORIZED, Phase.AUTHORIZED);
            case COPY_HANDLE -> throw new IllegalArgumentException(
                    "affine protocol capability cannot be duplicated");
            case CROSS_GENERATION -> throw new IllegalArgumentException(
                    "a session handle cannot be rebound to a new generation without migration proof");
        };
    }

    private static Phase require(Action action, Phase have, Phase needed, Phase next) {
        if (have != needed) {
            throw new IllegalArgumentException("protocol action " + action
                    + " requires " + needed + ", possibly " + have + " at CFG join");
        }
        return next;
    }
}
