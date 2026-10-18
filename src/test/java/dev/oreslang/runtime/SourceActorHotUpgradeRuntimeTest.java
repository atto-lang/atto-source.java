package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
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

    private static final class Executor implements ActorRuntime.ActorCodeExecutor {
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

    private static void drain(ActorRuntime runtime, ActorRuntime.ActorRef<?> ref)
            throws Exception {
        runtime.drain(ref).get(5, TimeUnit.SECONDS);
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
            assertTrue(runtime.upgradeSourceActorSameSchema(ref, v2));
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
            assertThrows(SecurityException.class,
                    () -> runtime.upgradeSourceActorSameSchema(ref, badSchema));
            assertThrows(SecurityException.class,
                    () -> runtime.upgradeSourceActorSameSchema(ref, badWire));
            assertSame(image1, runtime.codeImageFor(ref).orElseThrow());
            ref.send(41L);
            drain(runtime, ref);
            assertEquals(42, v1.observed.get());
            ref.send("stop");
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
            assertTrue(runtime.upgradeSourceActorSameSchema(ref, v2));
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
