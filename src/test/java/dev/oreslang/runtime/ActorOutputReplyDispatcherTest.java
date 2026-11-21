package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The actor processing these messages is real ActorRuntime with one true
 * output stream; no simulated or per-client output queue is substituted.
 * These are runtime routing tests, NOT NIO2 multi-client guest tests.
 */
@Timeout(20)
final class ActorOutputReplyDispatcherTest {
    private static OresBytes bytes(String s) {
        return OresBytes.copyOf(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void twoClientsShareOneTrueActorOutputWithoutCrossedReplies() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Object>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        List<?> tuple = (List<?>) message;
                        int id = ((Number) tuple.get(0)).intValue();
                        String data = (String) tuple.get(1);
                        context.runtime().emitCurrentActorOutput(
                                List.of(id, "VALUE", bytes(data)));
                    });
            try (var dispatcher = new ActorOutputReplyDispatcher(actor)) {
                dispatcher.start();
                var a = dispatcher.openSession().orElseThrow();
                var b = dispatcher.openSession().orElseThrow();
                for (int i = 0; i < 100; i++) {
                    var pa = dispatcher.tryBegin(a).orElseThrow();
                    var pb = dispatcher.tryBegin(b).orElseThrow();
                    actor.send(List.of(pa.requestId(), "a-" + i));
                    actor.send(List.of(pb.requestId(), "b-" + i));
                    assertEquals(bytes("a-" + i),
                            pa.response().get(5, TimeUnit.SECONDS).payload());
                    assertEquals(bytes("b-" + i),
                            pb.response().get(5, TimeUnit.SECONDS).payload());
                    assertEquals(a.id(),
                            pa.response().get(5, TimeUnit.SECONDS).sessionId());
                    assertEquals(b.id(),
                            pb.response().get(5, TimeUnit.SECONDS).sessionId());
                }
                assertEquals(0, dispatcher.pendingCount());
                assertEquals(2, dispatcher.sessionCount());
            }
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void disconnectRetiresOnlyItsOwnRequestAndDropsStaleOutput() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Object>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        List<?> tuple = (List<?>) message;
                        int id = ((Number) tuple.get(0)).intValue();
                        String data = (String) tuple.get(1);
                        if ("old".equals(data)) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("test actor turn not released");
                        }
                        context.runtime().emitCurrentActorOutput(
                                List.of(id, "VALUE", bytes(data)));
                    });
            try (var dispatcher = new ActorOutputReplyDispatcher(actor)) {
                dispatcher.start();
                var a = dispatcher.openSession().orElseThrow();
                var b = dispatcher.openSession().orElseThrow();
                var pa = dispatcher.tryBegin(a).orElseThrow();
                actor.send(List.of(pa.requestId(), "old"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, dispatcher.closeSession(a));
                var pb = dispatcher.tryBegin(b).orElseThrow();
                actor.send(List.of(pb.requestId(), "new"));
                release.countDown();
                assertEquals(bytes("new"),
                        pb.response().get(5, TimeUnit.SECONDS).payload());
                assertFalse(dispatcher.isClosed(), "late cancelled output must not kill other client");
                assertEquals(0, dispatcher.pendingCount());
                assertEquals(1, dispatcher.sessionCount());
            }
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void actorTerminationClosesRouterAndFailsOutstandingReplies() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Object>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> { });
            var dispatcher = new ActorOutputReplyDispatcher(actor);
            dispatcher.start();
            var session = dispatcher.openSession().orElseThrow();
            var pending = dispatcher.tryBegin(session).orElseThrow();
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 100 && !dispatcher.isClosed(); i++)
                Thread.sleep(5); // test observer only, not actor/NIO carrier
            assertTrue(dispatcher.isClosed());
            assertThrows(java.util.concurrent.CompletionException.class,
                    pending.response()::join);
            assertEquals(0, dispatcher.pendingCount());
            assertTrue(dispatcher.openSession().isEmpty());
        }
    }

    @Test
    void forgedTupleFailsClosedWithoutRoutingToAnySession() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Object>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) ->
                            context.runtime().emitCurrentActorOutput(
                                    List.of(1, "VALUE", "not immutable Bytes")));
            try (var dispatcher = new ActorOutputReplyDispatcher(actor)) {
                dispatcher.start();
                var session = dispatcher.openSession().orElseThrow();
                var pending = dispatcher.tryBegin(session).orElseThrow();
                actor.send(1);
                assertThrows(java.util.concurrent.CompletionException.class,
                        pending.response()::join);
                assertTrue(dispatcher.isClosed());
                assertTrue(dispatcher.failure().isPresent());
                assertEquals(0, dispatcher.pendingCount());
            }
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void duplicateActorRepliesCannotSettleAnotherRequest() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger outputs = new AtomicInteger();
            var actor = runtime.<Object>spawnShared(
                    IsolatePolicy.developer(),
                    () -> (message, context) -> {
                        int id = ((Number) message).intValue();
                        context.runtime().emitCurrentActorOutput(
                                List.of(id, "OK", OresBytes.empty()));
                        context.runtime().emitCurrentActorOutput(
                                List.of(id, "OK", OresBytes.empty()));
                        outputs.incrementAndGet();
                    });
            try (var dispatcher = new ActorOutputReplyDispatcher(actor)) {
                dispatcher.start();
                var s = dispatcher.openSession().orElseThrow();
                var one = dispatcher.tryBegin(s).orElseThrow();
                actor.send(one.requestId());
                one.response().get(5, TimeUnit.SECONDS);
                var two = dispatcher.tryBegin(s).orElseThrow();
                actor.send(two.requestId());
                two.response().get(5, TimeUnit.SECONDS);
                actor.drain().get(5, TimeUnit.SECONDS);
                assertEquals(2, outputs.get());
                assertEquals(0, dispatcher.pendingCount());
            }
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }
}
