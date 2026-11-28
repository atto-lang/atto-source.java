package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Conservation properties of supervisor CONTROL (#1223), seeded and
 * reproducible:
 *
 * <ol>
 *   <li>No loss, no duplication: under a concurrent failure storm with mixed
 *       graceful stops and ordinary user mail, the parent handles exactly one
 *       notice per failed child, none for gracefully stopped children, and
 *       the root fallback receives nothing.</li>
 *   <li>Conservation across a parent stop: when the parent stops while
 *       notices are pending or still arriving, every failed child's notice is
 *       either handled by the parent or delivered to the root fallback,
 *       exactly once in total.</li>
 *   <li>Notices carry immutable metadata only: no raw child diagnostic text,
 *       unique sequences, and the failed child's own identity.</li>
 * </ol>
 */
final class ActorSupervisorControlConservationTest {

    /** A parent that spawns children on request and records every CONTROL notice. */
    private static final class Parent implements ActorRuntime.Behavior<String> {
        final List<ActorRuntime.ActorRef<String>> children = new CopyOnWriteArrayList<>();
        final Map<ActorRuntime.ActorId, AtomicInteger> handled = new ConcurrentHashMap<>();
        final List<ActorRuntime.ActorFailureNotice> notices = new CopyOnWriteArrayList<>();
        final AtomicInteger userMail = new AtomicInteger();
        final CountDownLatch spawned = new CountDownLatch(1);
        volatile CountDownLatch holdTurn;
        volatile CountDownLatch holding;

        @Override
        public void onMessage(String message, ActorRuntime.ActorContext<String> context)
                throws Exception {
            if (message.startsWith("spawn:")) {
                int n = Integer.parseInt(message.substring(6));
                for (int i = 0; i < n; i++) {
                    // PRIVATE children never share the SHARED parent's lane.
                    children.add(context.runtime().spawnPrivate(childContext -> (m, c) -> {
                        if ("fail".equals(m)) {
                            throw new IllegalStateException("secret child diagnostic");
                        }
                    }));
                }
                spawned.countDown();
            } else if ("hold".equals(message)) {
                holding.countDown();
                assertTrue(holdTurn.await(30, TimeUnit.SECONDS));
            } else {
                userMail.incrementAndGet();
            }
        }

        @Override
        public void onChildFailure(ActorRuntime.ActorFailureNotice notice,
                ActorRuntime.ActorContext<String> context) {
            notices.add(notice);
            handled.computeIfAbsent(notice.actor(), id -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    private static void assertNoticeShape(ActorRuntime.ActorFailureNotice notice,
            Set<ActorRuntime.ActorId> failedIds, Set<Long> sequences) {
        assertTrue(failedIds.contains(notice.actor()),
                "notice for a child that did not fail: " + notice.actor());
        assertTrue(sequences.add(notice.sequence()), "duplicate CONTROL sequence");
        assertFalse(String.valueOf(notice.diagnosticType()).contains("secret"),
                "notice leaked raw child diagnostic text");
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 9L, 77L, 4242L})
    void failureStormIsDeliveredExactlyOncePerFailedChild(long seed) throws Exception {
        Random random = new Random(seed);
        final int children = 48;
        try (ActorRuntime runtime = new ActorRuntime()) {
            Parent parent = new Parent();
            var parentRef = runtime.<String>spawnShared(() -> parent);
            parentRef.send("spawn:" + children);
            assertTrue(parent.spawned.await(10, TimeUnit.SECONDS));
            awaitTrue(() -> parent.children.size() == children, "children");

            List<ActorRuntime.ActorRef<String>> shuffled = new ArrayList<>(parent.children);
            java.util.Collections.shuffle(shuffled, random);
            Set<ActorRuntime.ActorId> failed = ConcurrentHashMap.newKeySet();
            Set<ActorRuntime.ActorId> stopped = ConcurrentHashMap.newKeySet();
            List<Thread> threads = new ArrayList<>();
            int userSent = 0;
            for (int t = 0; t < 4; t++) {
                List<ActorRuntime.ActorRef<String>> slice = new ArrayList<>();
                for (int i = t; i < shuffled.size(); i += 4) slice.add(shuffled.get(i));
                long threadSeed = random.nextLong();
                threads.add(Thread.ofPlatform().start(() -> {
                    Random r = new Random(threadSeed);
                    for (var child : slice) {
                        if (r.nextInt(4) == 0) {
                            stopped.add(child.id());
                            child.stop();
                        } else {
                            failed.add(child.id());
                            child.send("fail");
                        }
                        if (r.nextBoolean()) Thread.onSpinWait();
                    }
                }));
            }
            for (int i = 0; i < 40; i++) {
                parentRef.send("user");
                userSent++;
            }
            for (Thread t : threads) t.join(10_000);

            awaitTrue(() -> parent.handled.size() == failed.size(), "all failure notices");
            awaitTrue(() -> parent.userMail.get() == 40, "user mail");
            Thread.sleep(50); // a duplicate or late notice would show up here

            Set<Long> sequences = new HashSet<>();
            for (var notice : parent.notices) assertNoticeShape(notice, failed, sequences);
            for (var id : failed) {
                AtomicInteger count = parent.handled.get(id);
                assertTrue(count != null && count.get() == 1,
                        "seed " + seed + ": failed child " + id + " handled "
                                + (count == null ? 0 : count.get()) + " times");
            }
            for (var id : stopped) {
                assertNull(parent.handled.get(id),
                        "seed " + seed + ": gracefully stopped child produced a notice");
            }
            var stats = runtime.supervisionStatsFor(parentRef);
            assertEquals(failed.size(), stats.published(), "seed " + seed + ": published");
            assertEquals(failed.size(), stats.acknowledged(), "seed " + seed + ": acknowledged");
            assertEquals(0, stats.pending(), "seed " + seed + ": pending");
            assertEquals(0, stats.duplicatePublishRejected(), "seed " + seed + ": duplicates");
            assertTrue(runtime.pollRootSupervisionControl().isEmpty(),
                    "seed " + seed + ": a handled notice also reached the root fallback");
            assertEquals(40, userSent);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {2L, 31L, 500L, 8080L})
    void noticesSurviveAParentStopExactlyOnceAcrossParentAndRoot(long seed) throws Exception {
        Random random = new Random(seed);
        final int children = 32;
        try (ActorRuntime runtime = new ActorRuntime()) {
            Parent parent = new Parent();
            var parentRef = runtime.<String>spawnShared(() -> parent);
            parentRef.send("spawn:" + children);
            assertTrue(parent.spawned.await(10, TimeUnit.SECONDS));
            awaitTrue(() -> parent.children.size() == children, "children");

            // Hold the parent inside a user turn so CONTROL notices queue up.
            parent.holdTurn = new CountDownLatch(1);
            parent.holding = new CountDownLatch(1);
            parentRef.send("hold");
            assertTrue(parent.holding.await(10, TimeUnit.SECONDS));

            List<ActorRuntime.ActorRef<String>> order = new ArrayList<>(parent.children);
            java.util.Collections.shuffle(order, random);
            int beforeStop = random.nextInt(children + 1);
            for (int i = 0; i < beforeStop; i++) order.get(i).send("fail");
            // Some notices are pending on the parent; stop it mid-storm. stop() is
            // synchronous (it joins finalization), so it runs on its own thread
            // while the held turn keeps the parent busy.
            java.util.concurrent.atomic.AtomicReference<Throwable> stopFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    parentRef.stop();
                } catch (Throwable t) {
                    stopFailure.set(t);
                }
            });
            awaitTrue(() -> runtime.actorDiagnostics(parentRef).lifecycle()
                    != ActorRuntime.ActorLifecycleState.RUNNING, "parent STOP admitted");
            // A parent STOP cascades to its children, so later sends may find a
            // child already sealed or terminated; that is expected, not a loss.
            for (int i = beforeStop; i < children; i++) {
                if (!random.nextBoolean()) continue;
                try {
                    order.get(i).send("fail");
                } catch (ActorRuntime.ActorTerminatedException
                         | ActorRuntime.ActorSealedException alreadyStopping) {
                    // not a failure: the child never ran the failing turn
                }
            }
            parent.holdTurn.countDown();
            stopper.join(30_000);
            assertNull(stopFailure.get(), "parent stop failed");
            assertTrue(parentRef.awaitTermination(10, TimeUnit.SECONDS));
            for (var child : parent.children) child.awaitTermination(10, TimeUnit.SECONDS);

            // Ground truth: which children actually terminated with a failure
            // (a failing turn or a cancellation), read from each child itself.
            Set<ActorRuntime.ActorId> failed = new HashSet<>();
            for (var child : parent.children) {
                if (child.terminalFailureNotice().isPresent()) failed.add(child.id());
            }
            for (var child : parent.children) {
                if (!failed.contains(child.id())) {
                    assertNull(parent.handled.get(child.id()),
                            "seed " + seed + ": a child without a terminal failure was handled");
                }
            }

            // Drain the root fallback, acknowledging every lease.
            Map<ActorRuntime.ActorId, Integer> atRoot = new ConcurrentHashMap<>();
            List<ActorRuntime.ActorFailureNotice> rootNotices = new ArrayList<>();
            awaitTrue(() -> {
                for (var lease = runtime.pollRootSupervisionControl(); lease.isPresent();
                        lease = runtime.pollRootSupervisionControl()) {
                    try (var held = lease.get()) {
                        // Every root notice must belong to a failed child; the
                        // parent's own graceful stop publishes none.
                        rootNotices.add(held.notice());
                        atRoot.merge(held.notice().actor(), 1, Integer::sum);
                        held.ack();
                    }
                }
                int parentHandled = parent.handled.size();
                return parentHandled + atRoot.size() >= failed.size();
            }, "every failed child accounted for at the parent or the root");

            Set<Long> sequences = new HashSet<>();
            for (var notice : parent.notices) assertNoticeShape(notice, failed, sequences);
            for (var notice : rootNotices) assertNoticeShape(notice, failed, sequences);
            for (var id : failed) {
                int atParent = parent.handled.getOrDefault(id, new AtomicInteger()).get();
                int root = atRoot.getOrDefault(id, 0);
                assertEquals(1, atParent + root, "seed " + seed + ": child " + id
                        + " handled " + atParent + "x by parent, " + root + "x by root");
            }
            assertTrue(runtime.pollRootSupervisionControl().isEmpty(),
                    "seed " + seed + ": root CONTROL not fully drained");
        }
    }
}
