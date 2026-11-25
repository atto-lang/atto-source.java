package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Simulates the real actor-output envelope, not a host-owned Redis KV store. */
final class ActorTaggedReplyDispatcherTest {
    private static final class OutputProbe
            implements ActorTaggedReplyDispatcher.OutputSource {
        private final ConcurrentLinkedQueue<OresFuture<
                GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>>> pulls =
                new ConcurrentLinkedQueue<>();
        private final AtomicInteger pullCount = new AtomicInteger();

        @Override public OresFuture<GeneratorRuntime.Step<
                ActorRuntime.ActorOutput<Object>>> next() {
            pullCount.incrementAndGet();
            var result = new OresFuture<
                    GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>>();
            pulls.add(result);
            return result;
        }

        void output(ActorRuntime.ActorId actorId, long sequence, Object payload) {
            var read = pulls.poll();
            assertNotNull(read, "dispatcher must have exactly one outstanding read");
            assertTrue(read.completeFromRuntime(GeneratorRuntime.Step.yielded(
                    new ActorRuntime.ActorOutput<>(actorId, sequence, payload))));
        }

        void end() {
            var read = pulls.poll();
            assertNotNull(read);
            assertTrue(read.completeFromRuntime(GeneratorRuntime.Step.doneStep()));
        }

        void assertSinglePull() {
            assertEquals(1, pulls.size(), "never allow concurrent output readers");
        }
    }

    private static List<Object> tuple(long client, long request, String status) {
        return List.of(client, request, status, OresBytes.empty());
    }

    @Test void interleavedOutputFromTwoClientsRoutesToTheirOwnTickets() throws Exception {
        var actor = ActorRuntime.ActorId.create();
        try (var router = new ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>(2, 1, 2)) {
            var probe = new OutputProbe();
            try (var dispatcher = new ActorTaggedReplyDispatcher(actor, probe, router)) {
                var a = router.tryOpenClient().orElseThrow();
                var b = router.tryOpenClient().orElseThrow();
                var a1 = router.tryAdmit(a).orElseThrow();
                var b1 = router.tryAdmit(b).orElseThrow();
                dispatcher.start();
                probe.assertSinglePull();
                probe.output(actor, 0, tuple(b.id(), b1.requestId(), "NIL"));
                probe.assertSinglePull();
                probe.output(actor, 1, tuple(a.id(), a1.requestId(), "OK"));
                assertEquals("OK", a1.reply().get(1, TimeUnit.SECONDS).status());
                assertEquals("NIL", b1.reply().get(1, TimeUnit.SECONDS).status());
                assertEquals(0, router.inFlight());
            }
        }
    }

    @Test void hundredLogicalClientsShareOneOutputConsumerAndBoundedCredits()
            throws Exception {
        var actor = ActorRuntime.ActorId.create();
        try (var router = new ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>(100, 1, 100)) {
            var probe = new OutputProbe();
            try (var dispatcher = new ActorTaggedReplyDispatcher(actor, probe, router)) {
                var tickets = new ArrayList<ActorReplyRouter.Ticket<
                        ActorTaggedReplyDispatcher.Reply>>();
                var clients = new ArrayList<ActorReplyRouter<
                        ActorTaggedReplyDispatcher.Reply>.Client>();
                for (int i = 0; i < 100; i++) {
                    var client = router.tryOpenClient().orElseThrow();
                    var ticket = router.tryAdmit(client).orElseThrow();
                    assertTrue(router.tryAdmit(client).isEmpty(),
                            "one in-flight operation per connection preserves wire order");
                    clients.add(client);
                    tickets.add(ticket);
                }
                assertTrue(router.tryOpenClient().isEmpty());
                dispatcher.start();
                for (int i = 99; i >= 0; i--) {
                    probe.assertSinglePull();
                    probe.output(actor, 99L - i,
                            tuple(clients.get(i).id(),
                                    tickets.get(i).requestId(), "ONE"));
                }
                for (var ticket : tickets) {
                    assertEquals("ONE", ticket.reply().get(1, TimeUnit.SECONDS).status());
                }
                assertEquals(0, router.inFlight());
            }
        }
    }

    @Test void staleCancelledReplyNeverMovesToReplacementConnection() throws Exception {
        var actor = ActorRuntime.ActorId.create();
        try (var router = new ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>(1, 1, 1)) {
            var probe = new OutputProbe();
            try (var dispatcher = new ActorTaggedReplyDispatcher(actor, probe, router)) {
                var old = router.tryOpenClient().orElseThrow();
                var oldReq = router.tryAdmit(old).orElseThrow();
                dispatcher.start();
                old.close();
                var replacement = router.tryOpenClient().orElseThrow();
                var newReq = router.tryAdmit(replacement).orElseThrow();
                probe.output(actor, 0, tuple(old.id(), oldReq.requestId(), "ERR"));
                assertTrue(oldReq.reply().isCancelled());
                assertFalse(newReq.reply().isDone());
                probe.output(actor, 1,
                        tuple(replacement.id(), newReq.requestId(), "VALUE"));
                assertEquals("VALUE", newReq.reply().get(1, TimeUnit.SECONDS).status());
            }
        }
    }

    @Test void forgedOtherActorOutputFailsClosedAndCancelsAllWaiters() {
        var expected = ActorRuntime.ActorId.create();
        var other = ActorRuntime.ActorId.create();
        try (var router = new ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>(1, 1, 1)) {
            var probe = new OutputProbe();
            var client = router.tryOpenClient().orElseThrow();
            var pending = router.tryAdmit(client).orElseThrow();
            try (var dispatcher = new ActorTaggedReplyDispatcher(expected, probe, router)) {
                dispatcher.start();
                probe.output(other, 0, tuple(client.id(), pending.requestId(), "OK"));
                assertTrue(dispatcher.isClosed());
                assertTrue(pending.reply().isCancelled());
                assertEquals(0, router.inFlight());
            }
        }
    }

    @Test void malformedTupleAndDuplicateSequenceBothFailClosed() throws Exception {
        var actor = ActorRuntime.ActorId.create();
        try (var router = new ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>(1, 1, 1)) {
            var probe = new OutputProbe();
            var client = router.tryOpenClient().orElseThrow();
            var pending = router.tryAdmit(client).orElseThrow();
            try (var dispatcher = new ActorTaggedReplyDispatcher(actor, probe, router)) {
                dispatcher.start();
                probe.output(actor, 0, List.of(client.id(), pending.requestId(), "OK"));
                assertTrue(dispatcher.isClosed());
                assertTrue(pending.reply().isCancelled());
            }
        }
        try (var router = new ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>(1, 1, 1)) {
            var probe = new OutputProbe();
            var client = router.tryOpenClient().orElseThrow();
            var first = router.tryAdmit(client).orElseThrow();
            try (var dispatcher = new ActorTaggedReplyDispatcher(actor, probe, router)) {
                dispatcher.start();
                probe.output(actor, 8, tuple(client.id(), first.requestId(), "OK"));
                assertEquals("OK", first.reply().get(1, TimeUnit.SECONDS).status());
                var next = router.tryAdmit(client).orElseThrow();
                probe.output(actor, 8, tuple(client.id(), next.requestId(), "OK"));
                assertTrue(dispatcher.isClosed());
                assertTrue(next.reply().isCancelled());
            }
        }
    }

    @Test void actorOutputStreamShutdownCancelsPendingAndDoesNotReadAgain() {
        var actor = ActorRuntime.ActorId.create();
        try (var router = new ActorReplyRouter<ActorTaggedReplyDispatcher.Reply>(1, 1, 1)) {
            var probe = new OutputProbe();
            var client = router.tryOpenClient().orElseThrow();
            var pending = router.tryAdmit(client).orElseThrow();
            try (var dispatcher = new ActorTaggedReplyDispatcher(actor, probe, router)) {
                dispatcher.start();
                int before = probe.pullCount.get();
                probe.end();
                assertTrue(dispatcher.isClosed());
                assertTrue(pending.reply().isCancelled());
                assertEquals(before, probe.pullCount.get(),
                        "termination must not start a new stream read");
            }
        }
    }
}
