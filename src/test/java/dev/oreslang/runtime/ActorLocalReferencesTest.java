package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLocalReferencesTest {
    @Test
    void allocationOutsideActorFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ActorLocalReferences.create(7));
        assertThrows(IllegalStateException.class, ActorLocalReferences::empty);
    }

    @Test
    void strongAndWeakHandlesRemainActorLocalAndTransportIsRejected() throws Exception {
        AtomicReference<ActorLocalReferences.Ref<Object>> escaped = new AtomicReference<>();
        AtomicReference<ActorLocalReferences.WeakRef<Object>> weak = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<String> secondTurn = new CompletableFuture<>();

        try (ActorRuntime actors = new ActorRuntime()) {
            var actor = actors.<String>spawn(() -> (message, context) -> {
                try {
                    if (message.equals("again")) {
                        ActorLocalReferences.Ref<Object> retained = escaped.get();
                        assertTrue(retained.sameIdentity(weak.get().upgrade().orElseThrow()));
                        secondTurn.complete((String) retained.getCopy());
                        return;
                    }
                    ActorLocalReferences.Ref<Object> first = ActorLocalReferences.empty();
                    assertFalse(first.isSet());
                    assertEquals(Optional.empty(), first.take());
                    first.set("hello");
                    assertTrue(first.isSet());
                    assertEquals("hello", first.getCopy());

                    // The stored value is identity-bearing and refers back to
                    // the very same cell; no recursive construction is needed.
                    first.set(List.of(first));
                    assertThrows(IllegalArgumentException.class, first::getCopy);
                    assertSame(first, ((List<?>) first.take().orElseThrow()).getFirst());
                    assertFalse(first.isSet());

                    first.set("again");
                    ActorLocalReferences.WeakRef<Object> downgraded = first.downgrade();
                    ActorLocalReferences.Ref<Object> upgraded = downgraded.upgrade().orElseThrow();
                    assertTrue(first.sameIdentity(upgraded));
                    assertEquals("again", upgraded.getCopy());
                    escaped.set(first);
                    weak.set(downgraded);
                    done.complete(null);
                } catch (Throwable problem) {
                    done.completeExceptionally(problem);
                }
            });

            actor.send("test");
            done.get(10, TimeUnit.SECONDS);
            actor.send("again");
            assertEquals("again", secondTurn.get(10, TimeUnit.SECONDS),
                    "same logical actor must retain access across scheduler/mailbox turns");

            assertThrows(IllegalStateException.class, () -> escaped.get().take());
            assertThrows(IllegalStateException.class, () -> escaped.get().set("outside"));
            assertThrows(IllegalStateException.class, () -> weak.get().upgrade());
            assertThrows(SecurityException.class,
                    () -> ActorRuntime.freeze(List.of(escaped.get())));
            assertThrows(SecurityException.class,
                    () -> ActorRuntime.freeze(List.of(weak.get())));

            CompletableFuture<Throwable> crossActor = new CompletableFuture<>();
            var second = actors.<String>spawn(() -> (message, context) -> {
                try {
                    escaped.get().getCopy();
                    crossActor.complete(new AssertionError("cross-actor access was accepted"));
                } catch (Throwable failure) {
                    crossActor.complete(failure);
                }
            });
            second.send("cross");
            assertInstanceOf(SecurityException.class, crossActor.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void strongCyclesCanBeSeveredAndWeakParentEdgesRemainNonOwning() throws Exception {
        CompletableFuture<Void> verified = new CompletableFuture<>();
        AtomicReference<ActorLocalReferences.Ref<Object>> escaped = new AtomicReference<>();
        try (ActorRuntime actors = new ActorRuntime()) {
            var actor = actors.<String>spawn(() -> (message, context) -> {
                try {
                    ActorLocalReferences.Ref<Object> first = ActorLocalReferences.empty();
                    ActorLocalReferences.Ref<Object> second = ActorLocalReferences.empty();

                    // Two strongly connected cells: legal within one actor.
                    first.set(List.of(second));
                    second.set(List.of(first));
                    assertTrue(first.sameIdentity(
                            (ActorLocalReferences.Ref<?>) ((List<?>) second.take()
                                    .orElseThrow()).getFirst()));
                    assertFalse(second.isSet());
                    second.set(List.of(first));

                    // Clearing either direction breaks the strong cycle
                    // without materializing or copying the old payload.
                    first.clear();
                    assertFalse(first.isSet());
                    first.clear(); // idempotent
                    assertTrue(second.isSet());
                    second.clear();
                    assertFalse(second.isSet());

                    // Preferred tree pattern: parent owns child, while the
                    // child only observes its parent via a weak reference.
                    ActorLocalReferences.Ref<Object> parent = ActorLocalReferences.empty();
                    ActorLocalReferences.Ref<Object> child = ActorLocalReferences.empty();
                    parent.set(List.of(child));
                    child.set(List.of(parent.downgrade()));
                    @SuppressWarnings("unchecked")
                    ActorLocalReferences.WeakRef<Object> backEdge =
                            (ActorLocalReferences.WeakRef<Object>)
                                    ((List<?>) child.take().orElseThrow()).getFirst();
                    assertTrue(parent.sameIdentity(backEdge.upgrade().orElseThrow()));
                    assertFalse(child.isSet());
                    parent.clear();
                    assertFalse(parent.isSet());
                    // Weak upgrades track the lifetime of the cell, not
                    // whether its Option-like payload is currently present.
                    assertTrue(parent.sameIdentity(backEdge.upgrade().orElseThrow()));
                    escaped.set(parent);
                    verified.complete(null);
                } catch (Throwable problem) {
                    verified.completeExceptionally(problem);
                }
            });
            actor.send("verify");
            verified.get(10, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> escaped.get().clear());
        }
    }

    @Test
    void cyclicCellSurvivesAwaitOnItsOwningActorAndReentryKeepsIdentity() throws Exception {
        CompletableFuture<OresFuture<Integer>> started = new CompletableFuture<>();
        CompletableFuture<Void> observedSecondTurn = new CompletableFuture<>();
        OresFuture<Integer> gate = new OresFuture<>();
        AtomicReference<Object> originalDomain = new AtomicReference<>();
        AtomicReference<ActorLocalReferences.Ref<Object>> escaped = new AtomicReference<>();
        AtomicReference<ActorLocalReferences.WeakRef<Object>> observer = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger turnCount =
                new java.util.concurrent.atomic.AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    if (message.equals("resume-mail")) {
                        assertSame(originalDomain.get(),
                                ActorRuntime.currentActorExecutionDomain());
                        assertTrue(escaped.get().isSet());
                        observedSecondTurn.complete(null);
                        return;
                    }
                    var cell = ActorLocalReferences.<Object>empty();
                    cell.set(List.of(cell));
                    escaped.set(cell);
                    observer.set(cell.downgrade());
                    Object domain = ActorRuntime.currentActorExecutionDomain();
                    originalDomain.set(domain);

                    OresFuture<Integer> task = runtime.startActorTask(resume -> {
                        assertSame(domain, ActorRuntime.currentActorExecutionDomain(),
                                "actor scheduler resumes under the same logical ownership domain");
                        if (turnCount.incrementAndGet() == 1) {
                            assertTrue(resume.initial());
                            assertTrue(cell.sameIdentity(
                                    (ActorLocalReferences.Ref<?>) ((List<?>) cell.take()
                                            .orElseThrow()).getFirst()));
                            cell.set(List.of(cell)); // rebuild cycle across await
                            return OresScheduler.await(gate);
                        }
                        assertEquals(2, turnCount.get(),
                                "a completed Future must not resume the actor twice");
                        assertNull(resume.failure());
                        assertEquals(41, resume.value());
                        assertSame(cell, ((List<?>) cell.take().orElseThrow()).getFirst());
                        assertFalse(cell.isSet());
                        assertTrue(cell.sameIdentity(observer.get().upgrade().orElseThrow()));
                        return OresScheduler.done(42);
                    });
                    runtime.ownCurrentActorFuture(task);
                    started.complete(task);
                } catch (Throwable failure) {
                    started.completeExceptionally(failure);
                }
            });

            actor.send("start");
            OresFuture<Integer> task = started.get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gate.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(1, gate.pendingRuntimeWaiterCount());

            // The source task can be pending without pinning a platform
            // carrier or changing the cell's actor identity.
            actor.send("resume-mail");
            observedSecondTurn.get(10, TimeUnit.SECONDS);

            assertTrue(gate.completeFromRuntime(41));
            assertEquals(42, task.get(10, TimeUnit.SECONDS));
            assertEquals(2, turnCount.get());
            assertEquals(0, gate.pendingRuntimeWaiterCount());
            assertThrows(IllegalStateException.class, () -> escaped.get().getCopy());
        }
    }

    @Test
    void cancellingSuspendedActorTaskDetachesWakerAndNeverTouchesCyclicCell() throws Exception {
        CompletableFuture<OresFuture<Integer>> started = new CompletableFuture<>();
        OresFuture<Integer> gate = new OresFuture<>();
        java.util.concurrent.atomic.AtomicInteger turns =
                new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<ActorLocalReferences.Ref<Object>> escaped = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    ActorLocalReferences.Ref<Object> cell = ActorLocalReferences.empty();
                    cell.set(List.of(cell));
                    escaped.set(cell);
                    OresFuture<Integer> task = runtime.startActorTask(resume -> {
                        if (turns.incrementAndGet() != 1) {
                            throw new AssertionError(
                                    "a cancelled suspended continuation must never execute");
                        }
                        assertTrue(cell.isSet());
                        return OresScheduler.await(gate);
                    });
                    runtime.ownCurrentActorFuture(task);
                    started.complete(task);
                } catch (Throwable failure) {
                    started.completeExceptionally(failure);
                }
            });

            actor.send("start");
            OresFuture<Integer> task = started.get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gate.pendingRuntimeWaiterCount() != 1
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(1, gate.pendingRuntimeWaiterCount());
            assertTrue(task.cancel(false));
            assertTrue(task.isCancelled());
            assertEquals(0, gate.pendingRuntimeWaiterCount(),
                    "cancellation must detach the suspended task's registered waiter");
            assertFalse(gate.isCancelled(), "shared producer remains independently owned");
            assertTrue(gate.completeFromRuntime(99));
            assertEquals(1, turns.get());
            assertThrows(IllegalStateException.class, () -> escaped.get().clear());
        }
    }

    @Test
    void bufferedChannelsAndSelectWritesRejectActorLocalHandlesAtRuntime() throws Exception {
        CompletableFuture<Boolean> verified = new CompletableFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    var strong = ActorLocalReferences.create(7);
                    var weak = strong.downgrade();
                    var channel = new ChannelRuntime.Channel<Object>(2);
                    assertThrows(SecurityException.class, () -> channel.tryWrite(strong));
                    assertThrows(SecurityException.class, () -> channel.writeAsync(weak));
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel, List.of(strong)));
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel, java.util.Map.of("weak", weak)));
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel, new Object[]{List.of(strong)}));
                    // Java-facing channel APIs must also inspect wrappers
                    // that are not Iterable, Map, array, or SharedState.
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel, Optional.of(strong)));
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel,
                                    List.of(Optional.of(weak))));
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel, java.util.Map.entry("ref", strong)));
                    // Mutable wrappers have a check/use race when a select
                    // registration waits before committing its payload.
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel,
                                    new AtomicReference<>(strong)));
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel,
                                    new java.lang.ref.WeakReference<>(weak)));
                    assertThrows(IllegalArgumentException.class,
                            () -> ChannelRuntime.write(channel,
                                    java.util.Collections.nCopies(100_001, "bounded")),
                            "very large payload graphs must fail closed before queueing");
                    OresMutex.SharedState wrapped = () -> List.of(weak);
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel, wrapped));
                    java.util.ArrayList<Object> cyclic = new java.util.ArrayList<>();
                    cyclic.add(cyclic);
                    cyclic.add(strong);
                    assertThrows(SecurityException.class,
                            () -> ChannelRuntime.write(channel, cyclic));
                    assertEquals(0, channel.size(), "rejected writes must not enqueue anything");
                    assertTrue(channel.tryWrite("safe"));
                    assertEquals(Optional.of("safe"), channel.tryRead());
                    verified.complete(true);
                } catch (Throwable problem) {
                    verified.completeExceptionally(problem);
                }
            });
            owner.send("verify");
            assertTrue(verified.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void bufferedGraphAdmissionChargesNullSlotsAndRepeatedEdges() {
        // The root itself consumes one occurrence; null slots still cost work.
        assertDoesNotThrow(() -> ActorLocalReferences.rejectBufferedChannelPayload(
                java.util.Collections.nCopies(99_999, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ActorLocalReferences.rejectBufferedChannelPayload(
                        java.util.Collections.nCopies(100_000, null)));
        assertThrows(IllegalArgumentException.class,
                () -> ActorLocalReferences.rejectBufferedChannelPayload(
                        new Object[100_000]));

        // A small cyclic ordinary local collection must terminate; identity
        // tracking prevents re-expansion but never exempts encountered edges.
        java.util.ArrayList<Object> cycle = new java.util.ArrayList<>();
        cycle.add(cycle);
        cycle.add(null);
        assertDoesNotThrow(() -> ActorLocalReferences.rejectBufferedChannelPayload(cycle));
        assertThrows(IllegalArgumentException.class,
                () -> ActorLocalReferences.rejectBufferedChannelPayload(
                        java.util.Collections.nCopies(100_000, cycle)));
    }

    @Test
    void cellPublicationRejectsNestedForeignHandlesBeforeChangingValue() throws Exception {
        var foreign = new CompletableFuture<ActorLocalReferences.Ref<Object>>();
        var weak = new CompletableFuture<ActorLocalReferences.WeakRef<Object>>();
        var verified = new CompletableFuture<Void>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.<String>spawn(() -> (message, context) -> {
                var ref = ActorLocalReferences.<Object>create(7);
                foreign.complete(ref);
                weak.complete(ref.downgrade());
            });
            first.send("create");
            var foreignRef = foreign.get(10, TimeUnit.SECONDS);
            var foreignWeak = weak.get(10, TimeUnit.SECONDS);
            var second = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    var local = ActorLocalReferences.<Object>create(42);
                    assertThrows(SecurityException.class,
                            () -> ActorLocalReferences.create(List.of(Optional.of(foreignRef))));
                    var cyclic = new java.util.ArrayList<Object>();
                    cyclic.add(cyclic);
                    cyclic.add(java.util.Map.of("foreign", foreignWeak));
                    assertThrows(SecurityException.class, () -> local.set(cyclic));
                    assertEquals(42, local.getCopy(), "failed replacement must retain the old value");
                    assertThrows(SecurityException.class,
                            () -> local.set(new AtomicReference<>(foreignRef)));
                    assertEquals(42, local.getCopy());
                    local.set(List.of(local)); // legal local self-edge
                    assertSame(local, ((List<?>) local.take().orElseThrow()).getFirst());
                    verified.complete(null);
                } catch (Throwable failure) {
                    verified.completeExceptionally(failure);
                }
            });
            second.send("verify");
            verified.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorCannotPublishReferencesThroughMailbox() throws Exception {
        CompletableFuture<Boolean> rejected = new CompletableFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Object>spawn(() -> (message, context) -> { });
            var source = runtime.<Object>spawn(() -> (message, context) -> {
                try {
                    var local = ActorLocalReferences.create(42);
                    assertThrows(SecurityException.class, () -> target.send(List.of(local)));
                    rejected.complete(true);
                } catch (Throwable problem) {
                    rejected.completeExceptionally(problem);
                }
            });
            source.send("go");
            assertTrue(rejected.get(10, TimeUnit.SECONDS));
        }
    }
}
