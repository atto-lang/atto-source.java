package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class ActorUnaryReplyBridgeTest {
    private static ActorMiddlewarePipeline.Envelope req() {
        return new ActorMiddlewarePipeline.Envelope("original", Map.of());
    }

    private static ActorMiddlewarePipeline.Key key(String id) {
        return new ActorMiddlewarePipeline.Key(id, "v1");
    }

    @Test
    void duplicateDispatchIsRejectedBeforeExecutingTerminalTwice() {
        var actor = ActorRuntime.ActorId.create();
        var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                actor, 3, UUID.randomUUID());
        var pending = new CompletableFuture<Integer>();
        var terminalCalls = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(), req -> {
                    terminalCalls.incrementAndGet();
                    return pending;
                });
        var bridge = new ActorUnaryReplyBridge<>(pipeline);
        var first = bridge.dispatch(actor, 3, slot, req(), () -> false);
        assertEquals(1, terminalCalls.get());
        assertThrows(IllegalStateException.class,
                () -> bridge.dispatch(actor, 3, slot, req(), () -> false));
        assertEquals(1, terminalCalls.get());
        pending.complete(19);
        assertEquals(19, first.toCompletableFuture().join().value());
        assertEquals(ActorUnaryReplySlot.State.REPLIED, slot.state());
    }

    @Test
    void overlappingDispatchCallsEnterTheUnaryChainAtMostOnce() throws Exception {
        var actor = ActorRuntime.ActorId.create();
        var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                actor, 8, UUID.randomUUID());
        var terminalCalls = new AtomicInteger();
        var bridge = new ActorUnaryReplyBridge<>(new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(), req -> {
                    terminalCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(12);
                }));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var accepted = new AtomicInteger();
        var rejected = new AtomicInteger();
        Runnable contestant = () -> {
            ready.countDown();
            try {
                if (!start.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("concurrent dispatch setup timed out");
                try {
                    bridge.dispatch(actor, 8, slot, req(), () -> false);
                    accepted.incrementAndGet();
                } catch (IllegalStateException alreadyClaimed) {
                    rejected.incrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        Thread a = new Thread(contestant);
        Thread b = new Thread(contestant);
        a.start();
        b.start();
        assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        a.join(2000);
        b.join(2000);
        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
        assertEquals(1, accepted.get());
        assertEquals(1, rejected.get());
        assertEquals(1, terminalCalls.get());
        assertEquals(ActorUnaryReplySlot.State.REPLIED, slot.state());
    }

    @Test
    void preCancelledRequestNeverInvokesMiddlewareTerminal() {
        var actor = ActorRuntime.ActorId.create();
        var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                actor, 4, UUID.randomUUID());
        var terminalCalls = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(), request -> {
                    terminalCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(8);
                });
        var bridge = new ActorUnaryReplyBridge<>(pipeline);
        var response = bridge.dispatch(actor, 4, slot, req(), () -> true);
        assertEquals(0, terminalCalls.get());
        assertEquals(ActorUnaryReplySlot.State.CANCELLED, slot.state());
        assertThrows(RuntimeException.class,
                () -> response.toCompletableFuture().join());
    }

    @Test
    void typedContinueSettlesSingleTicketWithResponseMetadata() {
        var owner = ActorRuntime.ActorId.create();
        var id = UUID.randomUUID();
        var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                owner, 3, id);
        var calls = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(
                        key("trace"), ActorMiddlewarePipeline.Phase.SUPERVISOR,
                        (request, draft) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Continue<>(
                                        request.withMessage("ready"),
                                        draft.withStatus(201).withHeader("Content-Type", "application/json"))),
                        false)),
                List.of(), Set.of(),
                request -> {
                    calls.incrementAndGet();
                    assertEquals("ready", request.message());
                    return CompletableFuture.completedFuture(73);
                });
        var bridge = new ActorUnaryReplyBridge<>(pipeline);
        var completed = bridge.dispatch(owner, 3, slot, req(), () -> false)
                .toCompletableFuture().join();
        assertEquals(73, completed.value());
        assertEquals(201, completed.response().status());
        assertEquals("application/json", completed.response().headers().get("content-type"));
        assertEquals(ActorUnaryReplySlot.State.REPLIED, slot.state());
        assertEquals(1, calls.get());
        assertFalse(slot.reply(owner, 3, id,
                new ActorMiddlewareValuePipeline.Reply<>(44)));
    }

    @Test
    void badActorGenerationPreventsMiddlewareInvocation() {
        var owner = ActorRuntime.ActorId.create();
        var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                owner, 4, UUID.randomUUID());
        var entered = new AtomicInteger();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(),
                input -> {
                    entered.incrementAndGet();
                    return CompletableFuture.completedFuture(42);
                });
        var bridge = new ActorUnaryReplyBridge<>(pipeline);
        assertThrows(SecurityException.class,
                () -> bridge.dispatch(owner, 3, slot, req(), () -> false));
        assertEquals(0, entered.get());
        assertEquals(ActorUnaryReplySlot.State.PENDING, slot.state());
    }

    @Test
    void middlewareRejectionCannotBeDeliveredAsTypedApplicationReply() {
        var owner = ActorRuntime.ActorId.create();
        var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                owner, 2, UUID.randomUUID());
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(ActorMiddlewareValuePipeline.Stage.<Integer>value(
                        key("auth"), ActorMiddlewarePipeline.Phase.SUPERVISOR,
                        (input, draft) -> CompletableFuture.completedFuture(
                                new ActorMiddlewareValuePipeline.Reject<>(403, "denied")),
                        false)),
                List.of(), Set.of(),
                input -> { fail("denied request reached actor"); return null; });
        var bridge = new ActorUnaryReplyBridge<>(pipeline);
        assertThrows(CompletionException.class,
                () -> bridge.dispatch(owner, 2, slot, req(), () -> false)
                        .toCompletableFuture().join());
        assertEquals(ActorUnaryReplySlot.State.REJECTED, slot.state());
    }

    @Test
    void cancellationWinsBeforeLateAsyncActorValueCompletes() {
        var owner = ActorRuntime.ActorId.create();
        var slot = new ActorUnaryReplySlot<ActorMiddlewareValuePipeline.Reply<Integer>>(
                owner, 6, UUID.randomUUID());
        var pending = new CompletableFuture<Integer>();
        var pipeline = new ActorMiddlewareValuePipeline<Integer>(
                List.of(), List.of(), Set.of(), input -> pending);
        var bridge = new ActorUnaryReplyBridge<>(pipeline);
        var response = bridge.dispatch(owner, 6, slot, req(), () -> false);
        assertTrue(slot.cancel());
        pending.complete(10);
        assertEquals(ActorUnaryReplySlot.State.CANCELLED, slot.state());
        assertThrows(RuntimeException.class,
                () -> response.toCompletableFuture().join());
    }
}
