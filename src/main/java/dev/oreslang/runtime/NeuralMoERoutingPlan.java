package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, deterministic and bounded CPU *planning* primitive for batched
 * Mixture-of-Experts routing. No tensor storage, GPU kernel, or guest actor
 * dispatch is performed here. Native kernels must execute the resulting
 * assignments and verify their tensor leases independently.
 *
 * A token is accepted atomically into its full top-k expert set, or handled
 * under the explicit overflow policy. A partially assigned top-k request
 * cannot silently corrupt expert weighting or training gradients.
 */
public final class NeuralMoERoutingPlan {
    private static final int MAX_TOKENS = 4096;
    private static final int MAX_EXPERTS = 256;
    private static final int MAX_TOP_K = 8;

    private NeuralMoERoutingPlan() {}

    public enum OverflowPolicy { REJECT, DROP_TOKEN }

    public record ExpertScore(int expertIndex, double score) {
        public ExpertScore {
            if (expertIndex < 0 || !Double.isFinite(score) || score < 0) {
                throw new IllegalArgumentException("expert index and score must be finite nonnegative");
            }
        }
    }

    public record TokenScores(long tokenIndex, List<ExpertScore> scores) {
        public TokenScores {
            if (tokenIndex < 0) throw new IllegalArgumentException("invalid token index");
            scores = List.copyOf(Objects.requireNonNull(scores));
        }
    }

    public record Assignment(long tokenIndex, int expertIndex, double normalizedWeight) {
        public Assignment {
            if (tokenIndex < 0 || expertIndex < 0
                    || !Double.isFinite(normalizedWeight) || normalizedWeight < 0
                    || normalizedWeight > 1) {
                throw new IllegalArgumentException("invalid MoE assignment");
            }
        }
    }

    public record Plan(
            NeuralActorSchema.Ticket ticket,
            List<Assignment> assignments,
            List<Long> droppedTokenIndices,
            List<Integer> expertLoads,
            int topK,
            int perExpertCapacity) {
        public Plan {
            Objects.requireNonNull(ticket);
            assignments = List.copyOf(assignments);
            droppedTokenIndices = List.copyOf(droppedTokenIndices);
            expertLoads = List.copyOf(expertLoads);
        }
    }

    /**
     * Scores must cover each configured expert exactly once per token.
     * Deterministic stable ties prefer the lower expert index. Each token
     * is either placed into all its selected experts or dropped/rejected.
     *
     * Admission is capacity-checked before load counters are incremented.
     * This algorithm is not a differentiable router or a GPU all-to-all.
     */
    public static Plan plan(
            NeuralActorSchema.Ticket ticket,
            List<TokenScores> tokens,
            int expertCount, int topK, int perExpertCapacity,
            OverflowPolicy overflowPolicy) {
        Objects.requireNonNull(ticket);
        Objects.requireNonNull(tokens);
        Objects.requireNonNull(overflowPolicy);
        if (tokens.isEmpty() || tokens.size() > MAX_TOKENS) {
            throw new IllegalArgumentException("MoE token batch requires 1..4096 items");
        }
        if (expertCount < 1 || expertCount > MAX_EXPERTS
                || topK < 1 || topK > MAX_TOP_K || topK > expertCount) {
            throw new IllegalArgumentException("invalid MoE expert or top-k configuration");
        }
        if (perExpertCapacity < 1 || perExpertCapacity > MAX_TOKENS) {
            throw new IllegalArgumentException("invalid per-expert token capacity");
        }
        int[] loads = new int[expertCount];
        Set<Long> seenTokens = new HashSet<>();
        List<Assignment> assigned = new ArrayList<>();
        List<Long> dropped = new ArrayList<>();
        Comparator<ExpertScore> order = Comparator
                .comparingDouble(ExpertScore::score).reversed()
                .thenComparingInt(ExpertScore::expertIndex);

        for (TokenScores token : tokens) {
            Objects.requireNonNull(token, "null token scores");
            if (!seenTokens.add(token.tokenIndex())) {
                throw new IllegalArgumentException("duplicate token index in expert batch");
            }
            if (token.scores().size() != expertCount) {
                throw new IllegalArgumentException("every token requires one score per expert");
            }
            Set<Integer> seenExperts = new HashSet<>();
            double maximum = 0;
            for (ExpertScore score : token.scores()) {
                Objects.requireNonNull(score);
                if (score.expertIndex() >= expertCount || !seenExperts.add(score.expertIndex())) {
                    throw new IllegalArgumentException("expert score index out of range or duplicated");
                }
                maximum = Math.max(maximum, score.score());
            }
            if (maximum == 0) {
                throw new IllegalArgumentException("all-zero expert scores cannot define routing weights");
            }
            List<ExpertScore> sorted = token.scores().stream().sorted(order).toList();
            List<ExpertScore> selected = sorted.subList(0, topK);
            boolean available = true;
            for (ExpertScore target : selected) {
                if (loads[target.expertIndex()] >= perExpertCapacity) {
                    available = false;
                    break;
                }
            }
            if (!available) {
                if (overflowPolicy == OverflowPolicy.REJECT) {
                    throw new IllegalStateException("MoE expert routing capacity exceeded");
                }
                dropped.add(token.tokenIndex());
                continue;
            }
            // Scale by the largest score before summing, preventing double
            // overflow even when two valid input scores approach MAX_VALUE.
            double sumScaled = 0;
            for (ExpertScore target : selected) sumScaled += target.score() / maximum;
            if (!Double.isFinite(sumScaled) || sumScaled <= 0) {
                throw new IllegalArgumentException("invalid selected MoE routing weight sum");
            }
            for (ExpertScore target : selected) {
                int expert = target.expertIndex();
                loads[expert]++;
                assigned.add(new Assignment(
                        token.tokenIndex(), expert, (target.score() / maximum) / sumScaled));
            }
        }

        List<Integer> immutableLoads = new ArrayList<>(expertCount);
        for (int count : loads) immutableLoads.add(count);
        return new Plan(ticket, assigned, dropped, immutableLoads, topK, perExpertCapacity);
    }
}
