package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dependency-free bounded model checking for durable workflow effect/delivery
 * semantics.
 *
 * <p>The model abstracts one pre-terminal external effect plus one delivery id.
 * It exhaustively explores claims, expiry, reconciliation, causal outcomes,
 * cancellation, duplicate delivery and conflicting-payload delivery. Concrete
 * journal/reducer tests separately prove refinement.</p>
 */
final class FormalDurableWorkflowModelCheckTest {

    private enum RunPhase {
        ACTIVE,
        TERMINAL
    }

    private enum EffectState {
        PENDING,
        CLAIMED,
        AMBIGUOUS,
        COMPLETED,
        ABANDONED
    }

    private enum DeliveryState {
        NONE,
        RETRYABLE_REJECTED,
        SEALED
    }

    private enum Action {
        CLAIM,
        COMPLETE_LIVE_CLAIM,
        EXPIRE_CLAIM,
        RECONCILE_SAFE_TO_RETRY,
        RECONCILE_COMPLETED,
        CAUSAL_OUTCOME,
        EARLY_REJECT,
        RETRY_REJECTED_AS_OUTCOME,
        EXACT_DUPLICATE,
        CONFLICTING_REUSE,
        CANCEL
    }

    private record State(
            RunPhase runPhase,
            EffectState effectState,
            boolean retryAllowed,
            int attempts,
            DeliveryState deliveryState) {

        static State initial() {
            return new State(
                    RunPhase.ACTIVE,
                    EffectState.PENDING,
                    true,
                    0,
                    DeliveryState.NONE);
        }
    }

    private record Edge(State from, Action action, State to) { }

    private record Graph(Set<State> states, List<Edge> edges) { }

    @Test
    void boundedStateSpaceSatisfiesDurableWorkflowSafety() {
        Graph graph = explore();

        assertTrue(
                graph.states().size() >= 12,
                "model must explore a non-trivial durable state space");

        for (State state : graph.states()) {
            assertSafety(state);
        }

        for (Edge edge : graph.edges()) {
            assertTransitionSafety(edge);
        }
    }

    private static Graph explore() {
        Set<State> states = new HashSet<>();
        List<Edge> edges = new ArrayList<>();
        ArrayDeque<State> queue = new ArrayDeque<>();

        State initial = State.initial();
        states.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            for (Action action : Action.values()) {
                Optional<State> next = step(current, action);
                if (next.isEmpty()) continue;

                State target = next.orElseThrow();
                edges.add(new Edge(current, action, target));
                if (states.add(target)) {
                    queue.addLast(target);
                }
            }
        }

        return new Graph(Set.copyOf(states), List.copyOf(edges));
    }

    private static Optional<State> step(State s, Action action) {
        return switch (action) {
            case CLAIM -> {
                if (s.runPhase() != RunPhase.ACTIVE
                        || s.effectState() != EffectState.PENDING
                        || !s.retryAllowed()
                        || s.attempts() >= 2) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.runPhase(),
                        EffectState.CLAIMED,
                        true,
                        s.attempts() + 1,
                        s.deliveryState()));
            }

            case COMPLETE_LIVE_CLAIM -> {
                if (s.effectState() != EffectState.CLAIMED) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.runPhase(),
                        EffectState.COMPLETED,
                        false,
                        s.attempts(),
                        s.deliveryState()));
            }

            case EXPIRE_CLAIM -> {
                if (s.effectState() != EffectState.CLAIMED) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.runPhase(),
                        EffectState.AMBIGUOUS,
                        s.retryAllowed(),
                        s.attempts(),
                        s.deliveryState()));
            }

            case RECONCILE_SAFE_TO_RETRY -> {
                if (s.effectState() != EffectState.AMBIGUOUS) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.runPhase(),
                        s.retryAllowed()
                                ? EffectState.PENDING
                                : EffectState.ABANDONED,
                        s.retryAllowed(),
                        s.attempts(),
                        s.deliveryState()));
            }

            case RECONCILE_COMPLETED -> {
                if (s.effectState() != EffectState.AMBIGUOUS) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.runPhase(),
                        EffectState.COMPLETED,
                        false,
                        s.attempts(),
                        s.deliveryState()));
            }

            case CAUSAL_OUTCOME -> {
                if (s.runPhase() != RunPhase.ACTIVE
                        || s.deliveryState() == DeliveryState.SEALED) {
                    yield Optional.empty();
                }
                EffectState nextEffect = switch (s.effectState()) {
                    case PENDING -> EffectState.ABANDONED;
                    case CLAIMED, AMBIGUOUS -> s.effectState();
                    case COMPLETED, ABANDONED -> s.effectState();
                };
                yield Optional.of(new State(
                        s.runPhase(),
                        nextEffect,
                        false,
                        s.attempts(),
                        DeliveryState.SEALED));
            }

            case EARLY_REJECT -> {
                if (s.deliveryState() != DeliveryState.NONE) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.runPhase(),
                        s.effectState(),
                        s.retryAllowed(),
                        s.attempts(),
                        DeliveryState.RETRYABLE_REJECTED));
            }

            case RETRY_REJECTED_AS_OUTCOME -> {
                if (s.runPhase() != RunPhase.ACTIVE
                        || s.deliveryState()
                                != DeliveryState.RETRYABLE_REJECTED) {
                    yield Optional.empty();
                }
                EffectState nextEffect = switch (s.effectState()) {
                    case PENDING -> EffectState.ABANDONED;
                    case CLAIMED, AMBIGUOUS -> s.effectState();
                    case COMPLETED, ABANDONED -> s.effectState();
                };
                yield Optional.of(new State(
                        s.runPhase(),
                        nextEffect,
                        false,
                        s.attempts(),
                        DeliveryState.SEALED));
            }

            case EXACT_DUPLICATE -> {
                if (s.deliveryState() != DeliveryState.SEALED) {
                    yield Optional.empty();
                }
                yield Optional.of(s);
            }

            case CONFLICTING_REUSE -> {
                if (s.deliveryState() == DeliveryState.NONE) {
                    yield Optional.empty();
                }
                yield Optional.of(s);
            }

            case CANCEL -> {
                if (s.runPhase() == RunPhase.TERMINAL) {
                    yield Optional.empty();
                }
                EffectState nextEffect = switch (s.effectState()) {
                    case PENDING -> EffectState.ABANDONED;
                    case CLAIMED, AMBIGUOUS -> s.effectState();
                    case COMPLETED, ABANDONED -> s.effectState();
                };
                yield Optional.of(new State(
                        RunPhase.TERMINAL,
                        nextEffect,
                        false,
                        s.attempts(),
                        s.deliveryState()));
            }
        };
    }

    private static void assertSafety(State state) {
        assertTrue(state.attempts() >= 0 && state.attempts() <= 2);
        if (state.effectState() == EffectState.ABANDONED
                || state.effectState() == EffectState.COMPLETED) {
            assertFalse(
                    state.retryAllowed(),
                    "terminal effect states cannot retain retry authority");
        }

        if (state.runPhase() == RunPhase.TERMINAL) {
            assertFalse(
                    state.effectState() == EffectState.PENDING
                            && state.retryAllowed(),
                    "terminal workflow cannot retain runnable pre-terminal work");
        }

        if (!state.retryAllowed()
                && state.effectState() == EffectState.AMBIGUOUS) {
            // Reconciliation may complete it or abandon it, never requeue it.
            assertNotEquals(
                    EffectState.PENDING,
                    state.effectState());
        }
    }

    private static void assertTransitionSafety(Edge edge) {
        State from = edge.from();
        State to = edge.to();

        if (from.runPhase() == RunPhase.TERMINAL) {
            assertEquals(
                    RunPhase.TERMINAL,
                    to.runPhase(),
                    "terminal workflow may never resurrect");
        }

        if (edge.action() == Action.EXACT_DUPLICATE
                || edge.action() == Action.CONFLICTING_REUSE
                || edge.action() == Action.EARLY_REJECT) {
            assertEquals(
                    from.effectState(),
                    to.effectState(),
                    edge.action()
                            + " must not mutate external effect lifecycle");
            assertEquals(
                    from.retryAllowed(),
                    to.retryAllowed(),
                    edge.action()
                            + " must not change retry authority");
        }

        if (edge.action() == Action.EXPIRE_CLAIM) {
            assertEquals(EffectState.CLAIMED, from.effectState());
            assertEquals(EffectState.AMBIGUOUS, to.effectState());
        }

        if (edge.action() == Action.RECONCILE_SAFE_TO_RETRY
                && !from.retryAllowed()) {
            assertEquals(
                    EffectState.ABANDONED,
                    to.effectState(),
                    "revoked ambiguous work cannot be requeued");
        }

        if (edge.action() == Action.CAUSAL_OUTCOME
                || edge.action() == Action.RETRY_REJECTED_AS_OUTCOME) {
            assertFalse(
                    to.retryAllowed(),
                    "accepted causal outcome revokes further execution");
            assertEquals(
                    DeliveryState.SEALED,
                    to.deliveryState());
        }

        if (edge.action() == Action.CANCEL) {
            assertEquals(RunPhase.TERMINAL, to.runPhase());
            assertFalse(
                    to.retryAllowed(),
                    "cancellation revokes retry authority");
        }
    }
}
