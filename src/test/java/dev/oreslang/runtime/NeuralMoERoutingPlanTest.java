package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class NeuralMoERoutingPlanTest {
    private static NeuralActorSchema.Ticket ticket() {
        return new NeuralActorSchema.Ticket(UUID.randomUUID(),
                new NeuralActorSchema.ModelVersion("llm", "ckpt-17", 4), 2, 0, -1, 0);
    }

    private static NeuralMoERoutingPlan.TokenScores token(long id, double a, double b, double c) {
        return new NeuralMoERoutingPlan.TokenScores(id, List.of(
                new NeuralMoERoutingPlan.ExpertScore(0, a),
                new NeuralMoERoutingPlan.ExpertScore(1, b),
                new NeuralMoERoutingPlan.ExpertScore(2, c)));
    }

    @Test
    void deterministicTopKRankingAndNormalizedWeights() {
        var input = ticket();
        var plan = NeuralMoERoutingPlan.plan(input,
                List.of(token(5, 3, 2, 1)), 3, 2, 3,
                NeuralMoERoutingPlan.OverflowPolicy.REJECT);
        assertEquals(input, plan.ticket());
        assertEquals(2, plan.assignments().size());
        assertEquals(0, plan.assignments().getFirst().expertIndex());
        assertEquals(1, plan.assignments().getLast().expertIndex());
        assertEquals(.6, plan.assignments().getFirst().normalizedWeight(), 1e-12);
        assertEquals(.4, plan.assignments().getLast().normalizedWeight(), 1e-12);
        assertEquals(List.of(1, 1, 0), plan.expertLoads());
        assertTrue(plan.droppedTokenIndices().isEmpty());
    }

    @Test
    void scoreTiesPreferLowerExpertIndexAndSourceOrderDoesNotMatter() {
        var plan = NeuralMoERoutingPlan.plan(ticket(),
                List.of(new NeuralMoERoutingPlan.TokenScores(0, List.of(
                        new NeuralMoERoutingPlan.ExpertScore(2, 5),
                        new NeuralMoERoutingPlan.ExpertScore(1, 5),
                        new NeuralMoERoutingPlan.ExpertScore(0, 5)))),
                3, 2, 1, NeuralMoERoutingPlan.OverflowPolicy.REJECT);
        assertEquals(List.of(0, 1), plan.assignments().stream()
                .map(NeuralMoERoutingPlan.Assignment::expertIndex).toList());
        assertEquals(.5, plan.assignments().getFirst().normalizedWeight(), 1e-12);
    }

    @Test
    void capacityOverflowDropsEntireTokenNotPartialTopK() {
        var tokens = List.of(token(0, 7, 6, 1), token(1, 8, 7, 1));
        var plan = NeuralMoERoutingPlan.plan(ticket(), tokens, 3, 2, 1,
                NeuralMoERoutingPlan.OverflowPolicy.DROP_TOKEN);
        assertEquals(2, plan.assignments().size());
        assertEquals(List.of(1L), plan.droppedTokenIndices());
        assertEquals(List.of(1, 1, 0), plan.expertLoads());
        assertThrows(IllegalStateException.class, () ->
                NeuralMoERoutingPlan.plan(ticket(), tokens, 3, 2, 1,
                        NeuralMoERoutingPlan.OverflowPolicy.REJECT));
    }

    @Test
    void untrustedScoresAndDuplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new NeuralMoERoutingPlan.ExpertScore(0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuralMoERoutingPlan.ExpertScore(0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuralMoERoutingPlan.ExpertScore(0, -1));
        assertThrows(IllegalArgumentException.class, () ->
                NeuralMoERoutingPlan.plan(ticket(), List.of(token(0, 0, 0, 0)),
                        3, 2, 2, NeuralMoERoutingPlan.OverflowPolicy.REJECT));
        assertThrows(IllegalArgumentException.class, () ->
                NeuralMoERoutingPlan.plan(ticket(), List.of(token(0, 1, 2, 3), token(0, 4, 5, 6)),
                        3, 2, 2, NeuralMoERoutingPlan.OverflowPolicy.REJECT));
        assertThrows(IllegalArgumentException.class, () ->
                NeuralMoERoutingPlan.plan(ticket(), List.of(new NeuralMoERoutingPlan.TokenScores(
                        0, List.of(new NeuralMoERoutingPlan.ExpertScore(1, 1),
                                new NeuralMoERoutingPlan.ExpertScore(1, 2),
                                new NeuralMoERoutingPlan.ExpertScore(2, 3)))),
                        3, 2, 2, NeuralMoERoutingPlan.OverflowPolicy.REJECT));
    }

    @Test
    void scaleBeforeNormalizationAvoidsFloatingPointSumOverflow() {
        var max = Double.MAX_VALUE;
        var plan = NeuralMoERoutingPlan.plan(ticket(),
                List.of(token(0, max, max, max / 2)), 3, 2, 1,
                NeuralMoERoutingPlan.OverflowPolicy.REJECT);
        assertEquals(.5, plan.assignments().getFirst().normalizedWeight(), 1e-12);
        assertEquals(.5, plan.assignments().getLast().normalizedWeight(), 1e-12);
    }

    @Test
    void plansAndInputScoresAreImmutable() {
        var mutable = new java.util.ArrayList<>(List.of(
                new NeuralMoERoutingPlan.ExpertScore(0, 3),
                new NeuralMoERoutingPlan.ExpertScore(1, 2),
                new NeuralMoERoutingPlan.ExpertScore(2, 1)));
        var token = new NeuralMoERoutingPlan.TokenScores(1, mutable);
        mutable.clear();
        assertEquals(3, token.scores().size());
        var plan = NeuralMoERoutingPlan.plan(ticket(), List.of(token), 3, 2, 1,
                NeuralMoERoutingPlan.OverflowPolicy.REJECT);
        assertThrows(UnsupportedOperationException.class, () -> plan.assignments().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.expertLoads().set(0, 99));
    }

    @Test
    void boundedBatchAndTopologyRejectUnboundedRouting() {
        var one = List.of(token(0, 2, 1, .5));
        assertThrows(IllegalArgumentException.class, () ->
                NeuralMoERoutingPlan.plan(ticket(), one, 0, 1, 1,
                        NeuralMoERoutingPlan.OverflowPolicy.REJECT));
        assertThrows(IllegalArgumentException.class, () ->
                NeuralMoERoutingPlan.plan(ticket(), one, 3, 4, 1,
                        NeuralMoERoutingPlan.OverflowPolicy.REJECT));
        assertThrows(IllegalArgumentException.class, () ->
                NeuralMoERoutingPlan.plan(ticket(), one, 3, 2, 0,
                        NeuralMoERoutingPlan.OverflowPolicy.REJECT));
    }
}
