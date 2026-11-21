package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Aggregate observers never acquire cancellation authority over their inputs. */
public class OresFuturesOwnershipTest {
    @Test void cancellingAllDoesNotCancelSharedInputs() {
        OresFuture<Integer> a = new OresFuture<>();
        OresFuture<Integer> b = new OresFuture<>();
        OresFuture<List<Integer>> aggregate = OresFutures.all(List.of(a, b));
        assertTrue(aggregate.cancel(false));
        assertTrue(aggregate.isCancelled());
        assertFalse(a.isCancelled());
        assertFalse(b.isCancelled());
        assertEquals(0, a.pendingRuntimeWaiterCount());
        assertEquals(0, b.pendingRuntimeWaiterCount());
        assertTrue(a.completeFromRuntime(1));
        assertTrue(b.completeFromRuntime(2));
    }

    @Test void cancellingRaceDoesNotCancelLosingActorReply() {
        OresFuture<Integer> a = new OresFuture<>();
        OresFuture<Integer> b = new OresFuture<>();
        OresFuture<Integer> race = OresFutures.race(List.of(a, b));
        assertTrue(race.cancel(false));
        assertFalse(a.isCancelled());
        assertFalse(b.isCancelled());
        assertEquals(0, a.pendingRuntimeWaiterCount());
        assertEquals(0, b.pendingRuntimeWaiterCount());
    }

    @Test void cancellingAllSettledDoesNotCancelInputWork() {
        OresFuture<String> a = new OresFuture<>();
        OresFuture<String> b = new OresFuture<>();
        OresFuture<List<OresFutures.Settled<String>>> settled =
                OresFutures.allSettled(List.of(a, b));
        assertTrue(settled.cancel(false));
        assertFalse(a.isCancelled());
        assertFalse(b.isCancelled());
        assertEquals(0, a.pendingRuntimeWaiterCount());
        assertEquals(0, b.pendingRuntimeWaiterCount());
        assertTrue(a.completeFromRuntime("ok"));
    }

    @Test void authenticInputCancellationPropagatesButDoesNotCancelSibling() {
        OresFuture<Integer> a = new OresFuture<>();
        OresFuture<Integer> sibling = new OresFuture<>();
        var aggregate = OresFutures.<Integer>all(List.of(a, sibling));
        a.cancel(false);
        assertTrue(aggregate.isCancelled());
        assertFalse(sibling.isCancelled());
        assertEquals(0, sibling.pendingRuntimeWaiterCount());
    }

    @Test void userRaisedCancellationExceptionPreservesFailureIdentity()
            throws Exception {
        OresFuture<Integer> a = new OresFuture<>();
        CancellationException guestRaised = new CancellationException("guest error");
        OresFuture<List<Integer>> aggregate = OresFutures.all(List.of(a));
        assertTrue(a.failFromRuntime(guestRaised));
        assertFalse(aggregate.isCancelled());
        assertSame(guestRaised, assertThrows(ExecutionException.class,
                () -> aggregate.get(3, TimeUnit.SECONDS)).getCause());
    }

    @Test void oneFailingInputDoesNotEraseSuccessfulSiblingOrCancelIt()
            throws Exception {
        OresFuture<Integer> failing = new OresFuture<>();
        OresFuture<Integer> unrelated = new OresFuture<>();
        IllegalStateException original = new IllegalStateException("source failed");
        OresFuture<List<Integer>> aggregate =
                OresFutures.all(List.of(failing, unrelated));
        failing.failFromRuntime(original);
        assertSame(original, assertThrows(ExecutionException.class,
                () -> aggregate.get(3, TimeUnit.SECONDS)).getCause());
        assertFalse(unrelated.isCancelled());
        assertTrue(unrelated.completeFromRuntime(13));
    }

    @Test void successfulVoidLifecycleInputsRetainBothNullSlots()
            throws Exception {
        var all = OresFutures.<Void>all(List.of(
                OresFuture.<Void>completed(null),
                OresFuture.<Void>completed(null)));
        assertEquals(java.util.Arrays.asList(null, null),
                all.get(3, TimeUnit.SECONDS));
    }

    @Test void detailedSettledSeparatesRealCancellationFromThrownException()
            throws Exception {
        OresFuture<Integer> successful = new OresFuture<>();
        OresFuture<Integer> raised = new OresFuture<>();
        OresFuture<Integer> cancelled = new OresFuture<>();
        CancellationException guestRaised = new CancellationException("guest raised");
        var detailed = OresFutures.<Integer>allSettledDetailed(
                List.of(successful, raised, cancelled));
        successful.completeFromRuntime(101);
        raised.failFromRuntime(guestRaised);
        cancelled.cancel(false);
        var outcomes = detailed.get(3, TimeUnit.SECONDS);
        assertEquals(FutureCombinators.Status.SUCCESS, outcomes.get(0).status());
        assertEquals(Integer.valueOf(101), outcomes.get(0).value());
        assertEquals(FutureCombinators.Status.FAILURE, outcomes.get(1).status());
        assertSame(guestRaised, outcomes.get(1).failure());
        assertEquals(FutureCombinators.Status.CANCELLED, outcomes.get(2).status());
        assertInstanceOf(CancellationException.class, outcomes.get(2).failure());
    }

    @Test void cancellingDetailedObserverDoesNotCancelUnderlyingActors()
            throws Exception {
        OresFuture<String> actorA = new OresFuture<>();
        OresFuture<String> actorB = new OresFuture<>();
        var observer = OresFutures.<String>allSettledDetailed(List.of(actorA, actorB));
        assertTrue(observer.cancel(false));
        assertFalse(actorA.isCancelled());
        assertFalse(actorB.isCancelled());
        assertEquals(0, actorA.pendingRuntimeWaiterCount());
        assertEquals(0, actorB.pendingRuntimeWaiterCount());
    }

    @Test void excessiveFanInRejectedBeforeRegisteringObservers() {
        OresFuture<Integer> shared = new OresFuture<>();
        List<OresFuture<Integer>> tooMany = java.util.Collections.nCopies(
                OresFutures.MAX_FAN_IN + 1, shared);
        assertThrows(IllegalArgumentException.class,
                () -> OresFutures.all(tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> OresFutures.race(tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> OresFutures.allSettled(tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> OresFutures.allSettledDetailed(tooMany));
        assertEquals(0, shared.pendingRuntimeWaiterCount());
    }

    @Test void futureInputsAreSnapshottedBeforeObservation() throws Exception {
        OresFuture<Integer> first = new OresFuture<>();
        OresFuture<Integer> second = new OresFuture<>();
        java.util.ArrayList<OresFuture<Integer>> input = new java.util.ArrayList<>();
        input.add(first);
        var gathered = OresFutures.<Integer>all(input);
        input.set(0, second);
        first.completeFromRuntime(3);
        assertEquals(List.of(3), gathered.get(3, TimeUnit.SECONDS));
        assertEquals(0, second.pendingRuntimeWaiterCount());
    }
}
