package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime physical loan integration test.
 *
 * These host/VM plumbing tests DO NOT claim that normal Graal Context.eval
 * executes namespace actor source or that AttoRMQ accepts TCP traffic.
 */
@Timeout(20)
final class NamespaceActorScalarRegionTest {
    private record Owned(NamespaceActorScalarRegion region)
            implements ActorRuntime.ActorOwnedGuestState {
        @Override public ActorRuntime.ActorId ownerActorId() {
            return region.ownerActorId();
        }
    }

    private static final class Executor implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        final Map<ActorRuntime.ActorId, Long> observations = new ConcurrentHashMap<>();
        final AtomicReference<NamespaceActorScalarRegion> firstRegion =
                new AtomicReference<>();
        final AtomicInteger rejectedCrossActorReads = new AtomicInteger();
        final AtomicInteger retiredRegions = new AtomicInteger();
        final AtomicInteger forgedLiveContextsRejected = new AtomicInteger();
        final AtomicReference<ExecutionContext> firstIssuedTurn = new AtomicReference<>();
        final AtomicReference<ExecutionContext> latestIssuedTurn = new AtomicReference<>();

        private Executor(SharedCodeImageStore.CodeImage image) {
            this.image = image;
        }
        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorName, ActorRuntime.ActorContext<Object> context) {
            if (!"Counter".equals(actorName)
                    || context.codeImage().orElse(null) != image
                    || context.currentMail().isPresent()) {
                throw new SecurityException("namespace init requires image-bound activation");
            }
            var region = NamespaceActorScalarRegion.initialize(context, 0L, false, false);
            firstRegion.compareAndSet(null, region);
            return new Owned(region);
        }

        @Override
        public void receiveActor(
                String actorName,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            if (!"Counter".equals(actorName)
                    || !(state instanceof Owned owned)
                    || !mail.equals(context.currentMail().orElse(null))
                    || !mail.recipient().equals(context.self().id())) {
                throw new SecurityException("forged actor mail or owner");
            }
            var region = owned.region();
            // The runtime, not this executor or the region, issues the
            // unique slot. Reacquiring in this callback preserves identity.
            var turn = ActorRuntime.sourceStateExecution(context, image, 1L);
            var sameTurn = ActorRuntime.sourceStateExecution(context, image, 1L);
            assertEquals(turn.id(), sameTurn.id());
            assertEquals(turn.executionHandle(), sameTurn.executionHandle());
            firstIssuedTurn.compareAndSet(null, turn);
            latestIssuedTurn.set(turn);
            if (!(mail.value() instanceof Long value)) {
                region.retire(context);
                throw new SecurityException("wrongly typed ActorMail<int>");
            }
            if (value == -2L) {
                var impostor = copyContext(context);
                // Even with the correct actor, code image and mailbox, the
                // counterfeit ActorContext has no runtime-issued turn permit.
                assertThrows(SecurityException.class,
                        () -> region.increment(impostor));
                forgedLiveContextsRejected.incrementAndGet();
            }
            if (value == -1L) {
                var foreign = firstRegion.get();
                if (foreign == null || foreign == region) {
                    throw new AssertionError("expected a different actor's region");
                }
                assertThrows(SecurityException.class,
                        () -> foreign.readIntegerInsideTurn(context));
                rejectedCrossActorReads.incrementAndGet();
            }
            region.increment(context);
            observations.put(context.self().id(),
                    region.readIntegerInsideTurn(context));
        }

        @Override
        public void tearDownActor(
                String actorName,
                ActorRuntime.ActorOwnedGuestState state,
                ActorRuntime.ActorContext<Object> context) {
            if (!(state instanceof Owned owned)) throw new SecurityException();
            owned.region().retire(context);
            retiredRegions.incrementAndGet();
        }
    }

    private static ActorRuntime.ActorContext<Object> copyContext(
            ActorRuntime.ActorContext<Object> real) {
        return new ActorRuntime.ActorContext<>() {
            @Override public ActorRuntime.ActorRef<Object> self() { return real.self(); }
            @Override public ActorRuntime runtime() { return real.runtime(); }
            @Override public ActorRuntime.ActorKind kind() { return real.kind(); }
            @Override public IsolatePolicy policy() { return real.policy(); }
            @Override public Optional<ActorRuntime.ActorMemorySlice> privateMemory() {
                return real.privateMemory();
            }
            @Override public Optional<SharedCodeImageStore.CodeImage> codeImage() {
                return real.codeImage();
            }
            @Override public Optional<ActorRuntime.ActorInboxMail<Object>> currentMail() {
                return real.currentMail();
            }
        };
    }

    private static SharedCodeImageStore.CodeImage image(SharedCodeImageStore store) {
        return store.publish("tests/region.atto", Parser.parse(
                "use namespace rmq_test; pub fnc noop(): int { return 0; }"));
    }

    @Test
    void twoRuntimeActorsHaveDistinctPhysicalLoansAndAreIsolatedAcrossTurns()
            throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executor = new Executor(image(store));
            var a = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Counter");
            a.ready().get(3, TimeUnit.SECONDS);
            var b = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Counter");
            b.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(a.id(), b.id());

            a.send(1L);
            a.send(2L);
            a.drain().get(3, TimeUnit.SECONDS);
            b.send(-1L);
            b.drain().get(3, TimeUnit.SECONDS);

            assertEquals(Long.valueOf(2L), executor.observations.get(a.id()));
            assertEquals(Long.valueOf(1L), executor.observations.get(b.id()));
            assertEquals(1, executor.rejectedCrossActorReads.get());
            assertTrue(a.failure().isEmpty());
            assertTrue(b.failure().isEmpty());

            a.stop();
            b.stop();
            assertNull(a.done().get(3, TimeUnit.SECONDS));
            assertNull(b.done().get(3, TimeUnit.SECONDS));
            assertEquals(2, executor.retiredRegions.get(),
                    "both actor-owned loan managers must quiesce on STOP");
        }
    }

    @Test
    void wrongTypedMailFailsOneActorAndSiblingRetainsPhysicalWriteAuthority()
            throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executor = new Executor(image(store));
            var a = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Counter");
            var b = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Counter");
            a.ready().get(3, TimeUnit.SECONDS);
            b.ready().get(3, TimeUnit.SECONDS);

            a.send("forged mail");
            assertThrows(ExecutionException.class,
                    () -> a.done().get(3, TimeUnit.SECONDS));
            assertTrue(a.failure().isPresent());

            b.send(1L);
            b.drain().get(3, TimeUnit.SECONDS);
            assertEquals(Long.valueOf(1L), executor.observations.get(b.id()));
            assertTrue(b.failure().isEmpty());
            b.stop();
            assertNull(b.done().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void liveActorCannotBorrowAgainstForgedContextWithIdenticalVisibleFields()
            throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executor = new Executor(image(store));
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Counter");
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send(-2L);
            actor.drain().get(3, TimeUnit.SECONDS);
            assertEquals(1, executor.forgedLiveContextsRejected.get());
            assertEquals(Long.valueOf(1L), executor.observations.get(actor.id()));
            assertTrue(actor.failure().isEmpty());
            actor.stop();
            assertNull(actor.done().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void sourceExecutionSlotRotatesOnEachReceiveAndCannotReplayAfterRestart()
            throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executor = new Executor(image(store));
            var old = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Counter");
            old.ready().get(3, TimeUnit.SECONDS);
            old.send(1L);
            old.drain().get(3, TimeUnit.SECONDS);
            var oldTurn = executor.latestIssuedTurn.get();
            assertNotNull(oldTurn);
            old.send(2L);
            old.drain().get(3, TimeUnit.SECONDS);
            var nextTurn = executor.latestIssuedTurn.get();
            assertNotNull(nextTurn);
            assertNotEquals(oldTurn.id(), nextTurn.id());
            assertNotEquals(oldTurn.executionHandle(), nextTurn.executionHandle());
            old.stop();
            old.done().get(3, TimeUnit.SECONDS);

            var successor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Counter");
            successor.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(old.id(), successor.id());
            assertThrows(RuntimeException.class, () -> old.send(3L));
            successor.send(4L);
            successor.drain().get(3, TimeUnit.SECONDS);
            var successorTurn = executor.latestIssuedTurn.get();
            assertNotEquals(nextTurn.id(), successorTurn.id());
            assertNotEquals(nextTurn.executionHandle(), successorTurn.executionHandle());
            assertEquals(Long.valueOf(1L), executor.observations.get(successor.id()));
            assertTrue(successor.failure().isEmpty());
            successor.stop();
            assertNull(successor.done().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppedActorCannotAcquireLoanOutsideItsPhysicalRuntimeTurn()
            throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var executor = new Executor(image(store));
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, executor, "Counter");
            actor.ready().get(3, TimeUnit.SECONDS);
            var old = executor.firstRegion.get();
            assertNotNull(old);
            actor.stop();
            actor.done().get(3, TimeUnit.SECONDS);
            var spoofed = new ActorRuntime.ActorContext<Object>() {
                @Override public ActorRuntime.ActorRef<Object> self() { return actor; }
                @Override public ActorRuntime runtime() { return runtime; }
                @Override public ActorRuntime.ActorKind kind() {
                    return ActorRuntime.ActorKind.SHARED;
                }
                @Override public IsolatePolicy policy() {
                    return IsolatePolicy.developer();
                }
                @Override public Optional<ActorRuntime.ActorMemorySlice> privateMemory() {
                    return Optional.empty();
                }
                @Override public Optional<SharedCodeImageStore.CodeImage> codeImage() {
                    return Optional.of(executor.codeImage());
                }
            };
            assertThrows(SecurityException.class, () -> old.increment(spoofed));
            assertThrows(SecurityException.class, () -> old.retire(spoofed));
        }
    }
}
