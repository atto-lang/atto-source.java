package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class SourceActorHotUpgradeRuntimeTest {
    private static String hash(char ch) { return String.valueOf(ch).repeat(64); }

    private static final class State implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorId owner;
        private long count;
        private State(ActorRuntime.ActorId owner, long count) {
            this.owner = owner;
            this.count = count;
        }
        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }
    }

    private static class Executor implements ActorRuntime.ActorCodeExecutor {
        private final SharedCodeImageStore.CodeImage image;
        private final String code;
        private final String schema;
        private final String messageAbi;
        private final boolean absolute;
        private final AtomicLong observed = new AtomicLong(Long.MIN_VALUE);
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private Executor(
                SharedCodeImageStore.CodeImage image,
                char code,
                char schema,
                char messageAbi,
                boolean absolute,
                CountDownLatch entered,
                CountDownLatch release) {
            this.image = image;
            this.code = hash(code);
            this.schema = hash(schema);
            this.messageAbi = hash(messageAbi);
            this.absolute = absolute;
            this.entered = entered;
            this.release = release;
        }

        @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
        @Override public Object actorUpgradeDomain() {
            return SourceActorHotUpgradeRuntimeTest.class;
        }
        @Override public String actorCodeSha256(String actorTypeName) { return code; }
        @Override public String actorStateSchemaSha256(String actorTypeName) { return schema; }
        @Override public String actorMessageAbiSha256(String actorTypeName) { return messageAbi; }

        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorTypeName,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals("Counter", actorTypeName);
            assertSame(image, context.codeImage().orElseThrow());
            return new State(context.self().id(), 1);
        }

        @Override
        public void receiveActor(
                String actorTypeName,
                ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) throws Exception {
            State state = assertInstanceOf(State.class, raw);
            if (entered != null) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("test actor release timed out");
            }
            if (mail.value() instanceof Long delta) {
                state.count = Math.addExact(
                        state.count,
                        absolute ? Math.abs(delta) : delta);
                observed.set(state.count);
            } else if (Objects.equals(mail.value(), "stop")) {
                context.self().stop();
            }
        }

        @Override
        public byte[] snapshotActorState(
                String actorTypeName,
                ActorRuntime.ActorOwnedGuestState raw,
                ActorRuntime.ActorContext<Object> context) {
            State state = assertInstanceOf(State.class, raw);
            assertEquals(context.self().id(), state.ownerActorId());
            return ByteBuffer.allocate(Long.BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(state.count)
                    .array();
        }

        @Override
        public ActorRuntime.ActorOwnedGuestState restoreActorState(
                String actorTypeName,
                byte[] snapshot,
                ActorRuntime.ActorContext<Object> context) {
            assertSame(image, context.codeImage().orElseThrow());
            if (snapshot.length != Long.BYTES)
                throw new IllegalArgumentException("counter state size");
            return new State(
                    context.self().id(),
                    ByteBuffer.wrap(snapshot).order(ByteOrder.BIG_ENDIAN).getLong());
        }
    }

    private static final class FailingRestoreExecutor extends Executor {
        private FailingRestoreExecutor(SharedCodeImageStore.CodeImage image) {
            super(image, 'b', '1', '2', true, null, null);
        }

        @Override
        public ActorRuntime.ActorOwnedGuestState restoreActorState(
                String actorTypeName,
                byte[] snapshot,
                ActorRuntime.ActorContext<Object> context) {
            throw new IllegalStateException("candidate restore injected failure");
        }
    }

    private static void drain(ActorRuntime runtime, ActorRuntime.ActorRef<?> ref)
            throws Exception {
        runtime.drain(ref).get(5, TimeUnit.SECONDS);
    }

    private static void eventuallyUpgrade(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<Object> ref,
            ActorRuntime.ActorCodeExecutor candidate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!runtime.upgradeSourceActorSameSchema(ref, candidate)) {
            if (System.nanoTime() >= deadline) fail("actor never reached upgrade safe point");
            Thread.sleep(2);
        }
    }

    private static <T extends Throwable> void eventuallyRejects(
            Class<T> type,
            ActorRuntime runtime,
            ActorRuntime.ActorRef<Object> ref,
            ActorRuntime.ActorCodeExecutor candidate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (;;) {
            try {
                if (runtime.upgradeSourceActorSameSchema(ref, candidate))
                    fail("incompatible source actor upgrade committed");
            } catch (Throwable failure) {
                assertInstanceOf(type, failure);
                return;
            }
            if (System.nanoTime() >= deadline) fail("actor never reached validation safe point");
            Thread.sleep(2);
        }
    }

    @Test
    void exactSchemaUpgradePreservesStableRefMailboxStateAndChangesCode() throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image1 = store.publish("counter-v1.ores", program);
            var image2 = store.publish("counter-v2.ores", Parser.parse(
                    "pub routine main(): void { val v = 2; return; }"));
            var v1 = new Executor(image1, 'a', '1', '2', false, null, null);
            var v2 = new Executor(image2, 'b', '1', '2', true, null, null);
            ActorRuntime.ActorRef<Object> ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    v1,
                    "Counter");
            var stableAddress = ref.address();
            ref.getReady().get(5, TimeUnit.SECONDS);

            ref.send(41L);
            drain(runtime, ref);
            assertEquals(42, v1.observed.get());
            eventuallyUpgrade(runtime, ref, v2);
            assertSame(image2, runtime.codeImageFor(ref).orElseThrow());
            assertEquals(stableAddress, ref.address());

            ref.send(-1L);
            drain(runtime, ref);
            assertEquals(43, v2.observed.get(),
                    "restored count must survive and v2 absolute-delta behavior must run");
            ref.send(-2L);
            drain(runtime, ref);
            assertEquals(45, v2.observed.get());
            ref.send("stop");
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void schemaAndMessageAbiMismatchFailWithoutChangingActiveActor() throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image1 = store.publish("counter-base.ores", program);
            var image2 = store.publish("counter-schema.ores", Parser.parse(
                    "pub routine main(): void { val x = 2; return; }"));
            var image3 = store.publish("counter-wire.ores", Parser.parse(
                    "pub routine main(): void { val x = 3; return; }"));
            var v1 = new Executor(image1, 'a', '1', '2', false, null, null);
            var badSchema = new Executor(image2, 'b', '9', '2', true, null, null);
            var badWire = new Executor(image3, 'c', '1', '9', true, null, null);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(), v1, "Counter");
            ref.getReady().get(5, TimeUnit.SECONDS);
            eventuallyRejects(SecurityException.class, runtime, ref, badSchema);
            eventuallyRejects(SecurityException.class, runtime, ref, badWire);
            assertSame(image1, runtime.codeImageFor(ref).orElseThrow());
            ref.send(41L);
            drain(runtime, ref);
            assertEquals(42, v1.observed.get());
            ref.send("stop");
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedCrossRuntimeRestoreReclaimsUnregisteredCandidateAndReopensOldActor()
            throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime source = new ActorRuntime(IsolatePolicy.developer());
             ActorRuntime target = new ActorRuntime(IsolatePolicy.developer())) {
            var image1 = store.publish("transfer-source.ores", program);
            var image2 = store.publish("transfer-candidate.ores", Parser.parse(
                    "pub routine main(): void { val version = 2; return; }"));
            var old = new Executor(image1, 'a', '1', '2', false, null, null);
            var bad = new FailingRestoreExecutor(image2);
            var ref = source.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    old,
                    "Counter");
            ref.getReady().get(5, TimeUnit.SECONDS);
            ref.send(41L);
            drain(source, ref);
            assertEquals(42L, old.observed.get());

            int actorCountBefore = target.actorCount();
            long memoryBefore = target.actorMemoryBytes();
            var transfer = source.tryBeginSourceActorTransfer(
                    ref,
                    java.util.UUID.randomUUID(),
                    1L).orElseThrow();
            try (transfer) {
                assertThrows(IllegalStateException.class,
                        () -> target.spawnCodeActorFromTransfer(transfer, bad));
                assertThrows(IllegalStateException.class,
                        () -> target.spawnCodeActorFromTransfer(transfer, bad),
                        "a failed restore consumes the linear transfer claim");
            }
            assertEquals(actorCountBefore, target.actorCount(),
                    "failed restored spawn must release its reserved actor slot");
            assertEquals(memoryBefore, target.actorMemoryBytes(),
                    "failed restored spawn must reclaim candidate-owned runtime memory");

            // Closing the failed transfer is rollback: exact old state and
            // ingress resume in the original physical actor.
            ref.send(1L);
            drain(source, ref);
            assertEquals(43L, old.observed.get());
            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void crossRuntimeTransferCanMaterializeExactlyOnePhysicalCandidate()
            throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime source = new ActorRuntime(IsolatePolicy.developer());
             ActorRuntime target = new ActorRuntime(IsolatePolicy.developer())) {
            var image1 = store.publish("linear-transfer-source.ores", program);
            var image2 = store.publish("linear-transfer-candidate.ores", Parser.parse(
                    "pub routine main(): void { val version = 2; return; }"));
            var old = new Executor(image1, 'a', '1', '2', false, null, null);
            var next = new Executor(image2, 'b', '1', '2', true, null, null);
            var ref = source.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    old,
                    "Counter");
            ref.getReady().get(5, TimeUnit.SECONDS);
            ref.send(41L);
            drain(source, ref);
            assertEquals(42L, old.observed.get());

            var transfer = source.tryBeginSourceActorTransfer(
                    ref,
                    java.util.UUID.randomUUID(),
                    1L).orElseThrow();
            assertNotEquals(new java.util.UUID(0L, 0L), transfer.transferId());

            ActorRuntime.ActorRef<Object> staged =
                    target.spawnCodeActorFromTransfer(transfer, next);
            staged.getReady().get(5, TimeUnit.SECONDS);
            assertEquals(1, target.actorCount());

            assertThrows(IllegalStateException.class,
                    () -> target.spawnCodeActorFromTransfer(transfer, next),
                    "one checkpoint must never clone into two physical candidates");

            // Rollback is physical, not just logical: close() must stop and
            // reap the unpublished target before reopening source ingress.
            transfer.close();
            assertTrue(staged.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, target.actorCount());

            ref.send(1L);
            drain(source, ref);
            assertEquals(43L, old.observed.get(),
                    "old route reopens only after staged candidate retirement");
            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentCrossRuntimeRestoreHasExactlyOneWinner() throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime source = new ActorRuntime(IsolatePolicy.developer());
             ActorRuntime target = new ActorRuntime(IsolatePolicy.developer())) {
            var image1 = store.publish("linear-race-source.ores", program);
            var image2 = store.publish("linear-race-candidate.ores", Parser.parse(
                    "pub routine main(): void { val version = 2; return; }"));
            var old = new Executor(image1, 'a', '1', '2', false, null, null);
            var next = new Executor(image2, 'b', '1', '2', true, null, null);
            var ref = source.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    old,
                    "Counter");
            ref.getReady().get(5, TimeUnit.SECONDS);
            ref.send(41L);
            drain(source, ref);

            var transfer = source.tryBeginSourceActorTransfer(
                    ref,
                    java.util.UUID.randomUUID(),
                    1L).orElseThrow();
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<ActorRuntime.ActorRef<Object>> first = pool.submit(() -> {
                    start.await();
                    return target.spawnCodeActorFromTransfer(transfer, next);
                });
                Future<ActorRuntime.ActorRef<Object>> second = pool.submit(() -> {
                    start.await();
                    return target.spawnCodeActorFromTransfer(transfer, next);
                });
                start.countDown();

                ActorRuntime.ActorRef<Object> winner = null;
                int rejected = 0;
                for (Future<ActorRuntime.ActorRef<Object>> attempt :
                        java.util.List.of(first, second)) {
                    try {
                        ActorRuntime.ActorRef<Object> candidate =
                                attempt.get(5, TimeUnit.SECONDS);
                        assertNull(winner, "two restore contenders must never both win");
                        winner = candidate;
                    } catch (ExecutionException failure) {
                        assertInstanceOf(
                                IllegalStateException.class,
                                failure.getCause(),
                                "losing restore contender must fail on the linear claim");
                        rejected++;
                    }
                }

                assertNotNull(winner);
                assertEquals(1, rejected);
                winner.getReady().get(5, TimeUnit.SECONDS);
                assertEquals(1, target.actorCount());
                transfer.close();
                assertTrue(winner.awaitTermination(5, TimeUnit.SECONDS));
            }

            assertEquals(0, target.actorCount());
            ref.send(1L);
            drain(source, ref);
            assertEquals(43L, old.observed.get());
            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void activeOldTurnMakesUpgradeReturnNotReadyWithoutBlocking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image1 = store.publish("counter-block-v1.ores", program);
            var image2 = store.publish("counter-block-v2.ores", Parser.parse(
                    "pub routine main(): void { val x = 2; return; }"));
            var v1 = new Executor(image1, 'a', '1', '2', false, entered, release);
            var v2 = new Executor(image2, 'b', '1', '2', true, null, null);
            var ref = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(), v1, "Counter");
            ref.getReady().get(5, TimeUnit.SECONDS);
            ref.send(41L);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                assertFalse(runtime.upgradeSourceActorSameSchema(ref, v2));
                assertSame(image1, runtime.codeImageFor(ref).orElseThrow());
            } finally {
                release.countDown();
            }
            drain(runtime, ref);
            eventuallyUpgrade(runtime, ref, v2);
            ref.send(1L);
            drain(runtime, ref);
            assertEquals(43, v2.observed.get());
            ref.send("stop");
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
