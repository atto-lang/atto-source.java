package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ReplySlotRegistryTest {
    private static final ReplySlotRegistry.Endpoint ORIGIN =
            new ReplySlotRegistry.Endpoint("parent-supervisor", 7, 3);
    private static final ReplySlotRegistry.AuthenticatedPeer CHILD =
            new ReplySlotRegistry.AuthenticatedPeer(
                    new ReplySlotRegistry.Endpoint("child", 12, 2), 0x1005L);
    private static final ReplySlotRegistry.AuthenticatedPeer IMPOSTOR =
            new ReplySlotRegistry.AuthenticatedPeer(
                    new ReplySlotRegistry.Endpoint("other-child", 12, 2), 0x1005L);

    @Test
    void boundedAllocationAndReuseRejectsLateReplies() throws Exception {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 2)) {
            var a = registry.reserve(String.class, "sha256:result-v1", CHILD);
            var b = registry.reserve(String.class, "sha256:result-v1", CHILD);
            assertEquals(2, registry.pendingCount());
            assertEquals(2, registry.highWaterMark());
            assertThrows(IllegalStateException.class,
                    () -> registry.reserve(String.class, "sha256:result-v1", CHILD));

            assertTrue(registry.complete(ORIGIN, a.handle(), CHILD, "sha256:result-v1", "a"));
            assertEquals("a", a.future().get(1, TimeUnit.SECONDS));
            assertEquals(1, registry.pendingCount());
            var c = registry.reserve(String.class, "sha256:result-v1", CHILD);
            assertEquals(a.handle() >>> 32, c.handle() >>> 32,
                    "freelist reuses the index");
            assertNotEquals(a.handle(), c.handle(), "generation must advance");
            assertFalse(registry.complete(ORIGIN, a.handle(), CHILD, "sha256:result-v1", "stale"));
            assertFalse(registry.complete(ORIGIN, c.handle(), CHILD, "sha256:result-v1", null));
            assertTrue(registry.complete(ORIGIN, c.handle(), CHILD, "sha256:result-v1", "c"));
            assertTrue(registry.complete(ORIGIN, b.handle(), CHILD, "sha256:result-v1", "b"));
            assertEquals("c", c.future().get(1, TimeUnit.SECONDS));
            assertEquals("b", b.future().get(1, TimeUnit.SECONDS));
            assertEquals(0, registry.pendingCount());
        }
    }

    @Test
    void forgedOrMisroutedRepliesDoNotConsumeLegitimateSlot() throws Exception {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 1)) {
            var pending = registry.reserve(Integer.class, "sha256:int-v4", CHILD);
            long handle = pending.handle();
            var staleOrigin = new ReplySlotRegistry.Endpoint(ORIGIN.id(), 6, 3);
            var upgradedOrigin = new ReplySlotRegistry.Endpoint(ORIGIN.id(), 7, 4);
            var staleChild = new ReplySlotRegistry.AuthenticatedPeer(
                    new ReplySlotRegistry.Endpoint("child", 11, 2), CHILD.sessionId());
            var sessionImpostor = new ReplySlotRegistry.AuthenticatedPeer(
                    CHILD.endpoint(), CHILD.sessionId() + 1);
            assertFalse(registry.complete(staleOrigin, handle, CHILD, "sha256:int-v4", 10));
            assertFalse(registry.complete(upgradedOrigin, handle, CHILD, "sha256:int-v4", 10));
            assertFalse(registry.complete(ORIGIN, handle, IMPOSTOR, "sha256:int-v4", 10));
            assertFalse(registry.complete(ORIGIN, handle, staleChild, "sha256:int-v4", 10));
            assertFalse(registry.complete(ORIGIN, handle, sessionImpostor, "sha256:int-v4", 10));
            assertFalse(registry.complete(ORIGIN, handle, CHILD, "sha256:int-v3", 10));
            assertFalse(registry.complete(ORIGIN, handle, CHILD, "sha256:int-v4", "wrong-type"));
            assertFalse(pending.future().isDone(), "forgery must not poison the real request");
            assertTrue(registry.complete(ORIGIN, handle, CHILD, "sha256:int-v4", 42));
            assertEquals(Integer.valueOf(42), pending.future().get(1, TimeUnit.SECONDS));
            assertFalse(registry.complete(ORIGIN, handle, CHILD, "sha256:int-v4", 45));
        }
    }

    @Test
    void callerCancellationReleasesSlotAndDropsLaterReply() {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 1)) {
            var pending = registry.reserve(String.class, "sha256:str-v1", CHILD);
            assertTrue(pending.future().cancel(false));
            assertEquals(0, registry.pendingCount());
            assertFalse(registry.complete(ORIGIN, pending.handle(), CHILD, "sha256:str-v1", "late"));
            assertTrue(pending.future().isCancelled());
            var next = registry.reserve(String.class, "sha256:str-v1", CHILD);
            assertNotEquals(pending.handle(), next.handle());
        }
    }

    @Test
    void monotonicTimerTimeoutAndRemoteFailureHaveSingleWinner() throws Exception {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 2)) {
            var timed = registry.reserve(String.class, "sha256:str-v1", CHILD);
            assertTrue(registry.timeout(timed.handle()));
            assertInstanceOf(TimeoutException.class,
                    assertThrows(ExecutionException.class,
                            () -> timed.future().get(1, TimeUnit.SECONDS)).getCause());
            assertFalse(registry.timeout(timed.handle()));
            assertFalse(registry.complete(ORIGIN, timed.handle(), CHILD, "sha256:str-v1", "late"));

            var failed = registry.reserve(String.class, "sha256:str-v1", CHILD);
            var cause = new IllegalArgumentException("callee failed");
            assertFalse(registry.fail(ORIGIN, failed.handle(), IMPOSTOR, "sha256:str-v1", cause));
            assertTrue(registry.fail(ORIGIN, failed.handle(), CHILD, "sha256:str-v1", cause));
            assertSame(cause,
                    assertThrows(ExecutionException.class,
                            () -> failed.future().get(1, TimeUnit.SECONDS)).getCause());
            assertFalse(registry.fail(ORIGIN, failed.handle(), CHILD, "sha256:str-v1", cause));
            assertEquals(0, registry.pendingCount());
        }
    }

    @Test
    void terminationFencesOldOriginEvenIfNewIncarnationReusesIdenticalNumber() throws Exception {
        var old = new ReplySlotRegistry(ORIGIN, 1);
        var oldCall = old.reserve(String.class, "sha256:str-v1", CHILD);
        var stop = new IllegalStateException("supervisor restarted");
        old.terminate(stop);
        assertSame(stop,
                assertThrows(ExecutionException.class,
                        () -> oldCall.future().get(1, TimeUnit.SECONDS)).getCause());
        assertEquals(0, old.pendingCount());
        assertThrows(IllegalStateException.class,
                () -> old.reserve(String.class, "sha256:str-v1", CHILD));

        var newOrigin = new ReplySlotRegistry.Endpoint(ORIGIN.id(), 8, 3);
        try (ReplySlotRegistry fresh = new ReplySlotRegistry(newOrigin, 1)) {
            var next = fresh.reserve(String.class, "sha256:str-v1", CHILD);
            assertEquals(oldCall.handle(), next.handle(),
                    "handle alone is intentionally only caller-incarnation-local");
            assertFalse(fresh.complete(ORIGIN, next.handle(), CHILD, "sha256:str-v1", "stale"));
            assertTrue(fresh.complete(newOrigin, next.handle(), CHILD, "sha256:str-v1", "valid"));
            assertEquals("valid", next.future().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void outOfOrderHundredCallsAndBudgetHighWater() throws Exception {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 100)) {
            List<ReplySlotRegistry.Pending<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                calls.add(registry.reserve(Integer.class, "sha256:int-v4", CHILD));
            }
            assertEquals(100, registry.pendingCount());
            assertEquals(100, registry.highWaterMark());
            for (int i = 99; i >= 0; i--) {
                assertTrue(registry.complete(
                        ORIGIN, calls.get(i).handle(), CHILD, "sha256:int-v4", i));
            }
            for (int i = 0; i < 100; i++) {
                assertEquals(Integer.valueOf(i), calls.get(i).future().get(1, TimeUnit.SECONDS));
            }
            assertEquals(0, registry.pendingCount());
        }
    }

    @Test
    void raceOfCancellationVsReplySettlesOnlyOnceAndDoesNotLeakSlots()
            throws Exception {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 1);
             ExecutorService workers = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 100; i++) {
                var pending = registry.reserve(Integer.class, "sha256:int-v4", CHILD);
                var gate = new CountDownLatch(1);
                var cancelled = workers.submit(() -> {
                    gate.await();
                    return pending.future().cancel(false);
                });
                var replied = workers.submit(() -> {
                    gate.await();
                    return registry.complete(
                            ORIGIN, pending.handle(), CHILD, "sha256:int-v4", 42);
                });
                gate.countDown();
                boolean cancelledWon = cancelled.get(2, TimeUnit.SECONDS);
                boolean repliedWon = replied.get(2, TimeUnit.SECONDS);
                assertNotEquals(cancelledWon, repliedWon,
                        "exactly one terminal event must win");
                assertEquals(0, registry.pendingCount(), "no pending slot leak");
                assertTrue(pending.future().isDone());
                assertFalse(registry.complete(
                        ORIGIN, pending.handle(), CHILD, "sha256:int-v4", 99));
            }
        }
    }

    @Test
    void rejectInvalidAdmissionBeforeAnySlotReservation() {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 1)) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.reserve(Object.class, "sha256:any", CHILD));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.reserve(String.class, "", CHILD));
            assertEquals(0, registry.pendingCount());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ReplySlotRegistry(ORIGIN, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplySlotRegistry(ORIGIN, 1_000_001));
    }

    @Test
    void remoteTicketPreventsBlindHandleForgeryAndCrossRequestReply() throws Exception {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 2)) {
            var first = registry.reserve(String.class, "sha256:str-v1", CHILD);
            var second = registry.reserve(String.class, "sha256:str-v1", CHILD);
            byte[] firstTicket = registry.issueRemoteTicket(
                    first.handle(), CHILD, "sha256:str-v1");
            byte[] secondTicket = registry.issueRemoteTicket(
                    second.handle(), CHILD, "sha256:str-v1");
            assertEquals(ReplyTicketAuthenticator.TICKET_BYTES, firstTicket.length);
            assertFalse(java.util.Arrays.equals(firstTicket, secondTicket));
            assertFalse(registry.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v1", firstTicket, "forged"));
            assertFalse(registry.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v1", new byte[16], "guessed"));
            assertFalse(registry.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v1", new byte[0], "empty"));
            assertFalse(registry.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v1", null, "null"));
            byte[] tampered = secondTicket.clone();
            tampered[tampered.length - 1] ^= 1;
            assertFalse(registry.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v1", tampered, "tampered"));
            assertFalse(registry.completeRemote(
                    ORIGIN, second.handle(), IMPOSTOR, "sha256:str-v1", secondTicket, "impostor"));
            assertFalse(registry.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v2", secondTicket, "abi"));
            assertFalse(second.future().isDone());
            assertTrue(registry.completeRemote(
                    ORIGIN, first.handle(), CHILD, "sha256:str-v1", firstTicket, "first"));
            assertTrue(registry.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v1", secondTicket, "second"));
            assertEquals("first", first.future().get(1, TimeUnit.SECONDS));
            assertEquals("second", second.future().get(1, TimeUnit.SECONDS));
            assertFalse(registry.completeRemote(
                    ORIGIN, first.handle(), CHILD, "sha256:str-v1", firstTicket, "duplicate"));
            assertThrows(IllegalStateException.class,
                    () -> registry.issueRemoteTicket(first.handle(), CHILD, "sha256:str-v1"));
        }
    }

    @Test
    void ticketsAreFencedAcrossOriginRestartSessionAndSlotReuse() throws Exception {
        byte[] issued;
        long oldHandle;
        try (ReplySlotRegistry old = new ReplySlotRegistry(ORIGIN, 1)) {
            var first = old.reserve(String.class, "sha256:str-v1", CHILD);
            oldHandle = first.handle();
            issued = old.issueRemoteTicket(oldHandle, CHILD, "sha256:str-v1");
            assertTrue(first.future().cancel(false));
            var second = old.reserve(String.class, "sha256:str-v1", CHILD);
            assertFalse(old.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v1", issued, "late"));
            assertTrue(old.completeRemote(
                    ORIGIN, second.handle(), CHILD, "sha256:str-v1",
                    old.issueRemoteTicket(second.handle(), CHILD, "sha256:str-v1"), "ok"));
        }
        var nextOrigin = new ReplySlotRegistry.Endpoint(ORIGIN.id(), ORIGIN.incarnation()+1,
                ORIGIN.routeEpoch());
        try (ReplySlotRegistry restarted = new ReplySlotRegistry(nextOrigin, 1)) {
            var newCall = restarted.reserve(String.class, "sha256:str-v1", CHILD);
            assertEquals(oldHandle, newCall.handle(), "same numeric slot is safe across incarnation");
            assertFalse(restarted.completeRemote(
                    nextOrigin, newCall.handle(), CHILD, "sha256:str-v1", issued, "replay"));
            var valid = restarted.issueRemoteTicket(newCall.handle(), CHILD, "sha256:str-v1");
            assertTrue(restarted.completeRemote(
                    nextOrigin, newCall.handle(), CHILD, "sha256:str-v1", valid, "fresh"));
            assertEquals("fresh", newCall.future().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void remoteFailureRequiresSameAuthorityAsSuccess() throws Exception {
        try (ReplySlotRegistry registry = new ReplySlotRegistry(ORIGIN, 1)) {
            var pending = registry.reserve(String.class, "sha256:str-v1", CHILD);
            var ticket = registry.issueRemoteTicket(
                    pending.handle(), CHILD, "sha256:str-v1");
            var cause = new IllegalStateException("sanitized remote error");
            assertFalse(registry.failRemote(
                    ORIGIN, pending.handle(), IMPOSTOR, "sha256:str-v1", ticket, cause));
            assertFalse(registry.failRemote(
                    ORIGIN, pending.handle(), CHILD, "sha256:str-v1", new byte[16], cause));
            assertFalse(pending.future().isDone());
            assertTrue(registry.failRemote(
                    ORIGIN, pending.handle(), CHILD, "sha256:str-v1", ticket, cause));
            assertSame(cause,
                    assertThrows(ExecutionException.class,
                            () -> pending.future().get(1, TimeUnit.SECONDS)).getCause());
            assertFalse(registry.failRemote(
                    ORIGIN, pending.handle(), CHILD, "sha256:str-v1", ticket, cause));
        }
    }
}
