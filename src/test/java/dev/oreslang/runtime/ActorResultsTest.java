package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Fan-in of actor-correlated reply Futures must never grant lifecycle rights
 * to the collecting observer.
 */
public class ActorResultsTest {
    private static ActorRuntime.ActorAddress address() {
        return new ActorRuntime.ActorAddress(
                UUID.randomUUID(), ActorRuntime.ActorId.create(),
                1, ActorRuntime.ActorKind.PRIVATE, "local-process");
    }

    private static <R> ActorResults.Pending<R> reply(
            ActorRuntime.ActorAddress address,
            UUID requestId, OresFuture<R> future) {
        return new ActorResults.Pending<>(address, requestId, future);
    }

    @Test void resultsAreInInputOrderNotCompletionOrder() throws Exception {
        var a = address();
        var b = address();
        UUID aid = UUID.randomUUID();
        UUID bid = UUID.randomUUID();
        OresFuture<String> first = new OresFuture<>();
        OresFuture<String> second = new OresFuture<>();
        var gathered = ActorResults.gather(List.of(
                reply(a, aid, first), reply(b, bid, second)));
        second.completeFromRuntime("second");
        assertFalse(gathered.isDone());
        first.completeFromRuntime("first");
        assertEquals(List.of(
                new ActorResults.Reply<>(a, aid, "first"),
                new ActorResults.Reply<>(b, bid, "second")),
                gathered.get(3, TimeUnit.SECONDS));
    }

    @Test void observerCancellationNeverCancelsEitherActorReplyProducer() {
        OresFuture<Integer> one = new OresFuture<>();
        OresFuture<Integer> two = new OresFuture<>();
        var gathered = ActorResults.gather(List.of(
                reply(address(), UUID.randomUUID(), one),
                reply(address(), UUID.randomUUID(), two)));
        assertTrue(gathered.cancel(false));
        assertTrue(gathered.isCancelled());
        assertFalse(one.isCancelled());
        assertFalse(two.isCancelled());
        assertEquals(0, one.pendingRuntimeWaiterCount());
        assertEquals(0, two.pendingRuntimeWaiterCount());
        assertTrue(one.completeFromRuntime(1));
        assertTrue(two.completeFromRuntime(2));
    }

    @Test void firstFailurePreservesCauseAndDetachesOtherObservers()
            throws Exception {
        OresFuture<Integer> failed = new OresFuture<>();
        OresFuture<Integer> slow = new OresFuture<>();
        IllegalArgumentException original = new IllegalArgumentException("worker failed");
        var gathered = ActorResults.gather(List.of(
                reply(address(), UUID.randomUUID(), failed),
                reply(address(), UUID.randomUUID(), slow)));
        failed.failFromRuntime(original);
        assertSame(original, assertThrows(ExecutionException.class,
                () -> gathered.get(3, TimeUnit.SECONDS)).getCause());
        assertFalse(slow.isCancelled());
        assertEquals(0, slow.pendingRuntimeWaiterCount());
    }

    @Test void settledResultsAttributeEachActorAndAuthenticateCancellation()
            throws Exception {
        var a = address();
        var b = address();
        var c = address();
        UUID aid = UUID.randomUUID();
        UUID bid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        OresFuture<Integer> success = new OresFuture<>();
        OresFuture<Integer> failure = new OresFuture<>();
        OresFuture<Integer> cancelled = new OresFuture<>();
        CancellationException thrown = new CancellationException("guest threw this");
        var gathered = ActorResults.gatherSettled(List.of(
                reply(a, aid, success),
                reply(b, bid, failure),
                reply(c, cid, cancelled)));
        success.completeFromRuntime(50);
        failure.failFromRuntime(thrown);
        cancelled.cancel(false);
        var outcomes = gathered.get(3, TimeUnit.SECONDS);
        assertEquals(3, outcomes.size());
        assertEquals(FutureCombinators.Status.SUCCESS, outcomes.get(0).status());
        assertEquals(Integer.valueOf(50), outcomes.get(0).value());
        assertEquals(a, outcomes.get(0).address());
        assertEquals(aid, outcomes.get(0).requestId());
        assertEquals(FutureCombinators.Status.FAILURE, outcomes.get(1).status());
        assertSame(thrown, outcomes.get(1).error());
        assertEquals(bid, outcomes.get(1).requestId());
        assertEquals(FutureCombinators.Status.CANCELLED, outcomes.get(2).status());
        assertInstanceOf(CancellationException.class, outcomes.get(2).error());
        assertEquals(c, outcomes.get(2).address());
        assertEquals(cid, outcomes.get(2).requestId());
    }

    @Test void settledObserverCancellationLeavesAllProducersLive() throws Exception {
        OresFuture<Integer> left = new OresFuture<>();
        OresFuture<Integer> right = new OresFuture<>();
        var gathered = ActorResults.gatherSettled(List.of(
                reply(address(), UUID.randomUUID(), left),
                reply(address(), UUID.randomUUID(), right)));
        assertTrue(gathered.cancel(false));
        assertFalse(left.isCancelled());
        assertFalse(right.isCancelled());
        assertEquals(0, left.pendingRuntimeWaiterCount());
        assertEquals(0, right.pendingRuntimeWaiterCount());
    }

    @Test void repeatActorAllowedButDuplicateRequestIdRejected() throws Exception {
        var actor = address();
        UUID firstRequest = UUID.randomUUID();
        var sameActor = ActorResults.gather(List.of(
                reply(actor, firstRequest, OresFuture.completed("a")),
                reply(actor, UUID.randomUUID(), OresFuture.completed("b"))));
        assertEquals(2, sameActor.get(3, TimeUnit.SECONDS).size());

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> ActorResults.gather(List.of(
                        reply(actor, firstRequest, OresFuture.completed("a")),
                        reply(address(), firstRequest, OresFuture.completed("b")))));
        assertTrue(duplicate.getMessage().contains("duplicate"));
    }

    @Test void emptyFanInSettlesImmediately() throws Exception {
        assertEquals(List.of(),
                ActorResults.<Integer>gather(List.of()).get(3, TimeUnit.SECONDS));
        assertEquals(List.of(),
                ActorResults.<Integer>gatherSettled(List.of()).get(3, TimeUnit.SECONDS));
    }

    @Test void voidActorLifecycleFuturesRemainSuccessfulNullPayloads() throws Exception {
        var actor = address();
        UUID id = UUID.randomUUID();
        var result = ActorResults.gather(List.of(
                reply(actor, id, OresFuture.<Void>completed(null))));
        assertEquals(List.of(new ActorResults.Reply<Void>(actor, id, null)),
                result.get(3, TimeUnit.SECONDS));
    }

    @Test void cancelledActorReplyCancelsAggregateNotSiblingActorProducer()
            throws Exception {
        OresFuture<Integer> stopped = new OresFuture<>();
        OresFuture<Integer> unrelated = new OresFuture<>();
        var gathered = ActorResults.gather(List.of(
                reply(address(), UUID.randomUUID(), stopped),
                reply(address(), UUID.randomUUID(), unrelated)));
        stopped.cancel(false);
        assertTrue(gathered.isCancelled());
        assertFalse(unrelated.isCancelled());
        assertTrue(unrelated.completeFromRuntime(7));
    }

    @Test void actorLifecycleFailureIsNotMistakenForCleanVoidResult() throws Exception {
        IllegalStateException failedInit = new IllegalStateException("on_start failed");
        var actor = address();
        UUID request = UUID.randomUUID();
        OresFuture<Void> ready = OresFuture.failed(failedInit);
        var gathered = ActorResults.gatherSettled(List.of(
                reply(actor, request, ready)));
        var outcome = gathered.get(3, TimeUnit.SECONDS).get(0);
        assertEquals(actor, outcome.address());
        assertEquals(request, outcome.requestId());
        assertEquals(FutureCombinators.Status.FAILURE, outcome.status());
        assertSame(failedInit, outcome.error());
    }

    @Test void mutableActorInputListCannotChangeAnInFlightCollection()
            throws Exception {
        var a = address();
        var b = address();
        UUID originalId = UUID.randomUUID();
        OresFuture<Integer> original = new OresFuture<>();
        OresFuture<Integer> later = new OresFuture<>();
        java.util.ArrayList<ActorResults.Pending<Integer>> input =
                new java.util.ArrayList<>();
        input.add(reply(a, originalId, original));
        var gathered = ActorResults.gather(input);
        input.set(0, reply(b, UUID.randomUUID(), later));
        original.completeFromRuntime(12);
        assertEquals(List.of(new ActorResults.Reply<>(a, originalId, 12)),
                gathered.get(3, TimeUnit.SECONDS));
        assertEquals(0, later.pendingRuntimeWaiterCount());
    }

    @Test void actorFanInRejectsOverLimitBeforeSubscribing()
            throws Exception {
        var source = new OresFuture<Integer>();
        var same = reply(address(), UUID.randomUUID(), source);
        var tooMany = java.util.Collections.nCopies(
                ActorResults.MAX_FAN_IN + 1, same);
        assertThrows(IllegalArgumentException.class,
                () -> ActorResults.gather(tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> ActorResults.gatherSettled(tooMany));
        assertEquals(0, source.pendingRuntimeWaiterCount());
    }
}
