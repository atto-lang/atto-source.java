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
