package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorReservedHeapRuntimeTest {
    private static final long MIB = 1024 * 1024;
    private static IsolatePolicy policy(long heap) {
        return new IsolatePolicy(Set.of(IsolatePolicy.Capability.SHARED_MEMORY,
                IsolatePolicy.Capability.ACTOR_SPAWN), heap, 8, Duration.ofSeconds(10));
    }
    private static ActorRuntime.ActorCodeExecutor executor(SharedCodeImageStore.CodeImage code,
            boolean fail, CountDownLatch entered, CountDownLatch proceed) {
        return new ActorRuntime.ActorCodeExecutor() {
            @Override public SharedCodeImageStore.CodeImage codeImage() { return code; }
            @Override public ActorRuntime.ActorOwnedGuestState initializeActor(String type,
                    ActorRuntime.ActorContext<Object> context) throws Exception {
                context.heap().region(4096);
                if (entered != null) {
                    entered.countDown();
                    if (!proceed.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("test init deadline");
                }
                if (fail) throw new IllegalStateException("injected init failure");
                var id = context.self().id();
                return () -> id;
            }
            @Override public void receiveActor(String type, ActorRuntime.ActorOwnedGuestState state,
                    ActorRuntime.ActorInboxMail<Object> mail, ActorRuntime.ActorContext<Object> context) { }
        };
    }
    @Test void realArenaAllocationsUseReservedCapacityExactlyOnce() throws Exception {
        try (var store = new SharedCodeImageStore(); var runtime = new ActorRuntime(policy(32*MIB))) {
            var code = store.publish("reserved-native-arena", Parser.parse("define actor Worker as state { let int count = 0; } end"));
            var e = executor(code, false, null, null);
            var a = runtime.spawnCodeActorWithReservedHeap(ActorRuntime.ActorKind.SHARED, policy(16*MIB), e, "Worker");
            var b = runtime.spawnCodeActorWithReservedHeap(ActorRuntime.ActorKind.SHARED, policy(16*MIB), e, "Worker");
            assertEquals(32*MIB, runtime.sharedMemoryBytes());
            a.ready().get(5, TimeUnit.SECONDS);
            b.ready().get(5, TimeUnit.SECONDS);
            assertEquals(32*MIB, runtime.sharedMemoryBytes(), "slabs must not charge the aggregate twice");
            assertThrows(IllegalStateException.class, () -> runtime.spawnCodeActorWithReservedHeap(
                ActorRuntime.ActorKind.SHARED, policy(16*MIB), e, "Worker"));
            assertEquals(2, runtime.actorCount());
            a.stop(); assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(16*MIB, runtime.sharedMemoryBytes());
            var c = runtime.spawnCodeActorWithReservedHeap(ActorRuntime.ActorKind.SHARED, policy(16*MIB), e, "Worker");
            b.stop(); c.stop();
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(c.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.sharedMemoryBytes());
        }
    }
    @Test void failedInitializationRefundsOnlyAfterPhysicalRetirement() throws Exception {
        try (var store = new SharedCodeImageStore(); var runtime = new ActorRuntime(policy(16*MIB))) {
            var code = store.publish("reserved-native-arena-failure", Parser.parse("define actor Worker as state { let int count = 0; } end"));
            var actor = runtime.spawnCodeActorWithReservedHeap(ActorRuntime.ActorKind.SHARED,
                policy(16*MIB), executor(code, true, null, null), "Worker");
            assertEquals(16*MIB, runtime.sharedMemoryBytes());
            assertThrows(ExecutionException.class, () -> actor.ready().get(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.sharedMemoryBytes());
        }
    }
    @Test void cancelDuringInitRetainsCapacityUntilTheTurnExits() throws Exception {
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        try (var store = new SharedCodeImageStore(); var runtime = new ActorRuntime(policy(16*MIB))) {
            var code = store.publish("reserved-native-arena-cancel", Parser.parse("define actor Worker as state { let int count = 0; } end"));
            var e = executor(code, false, entered, proceed);
            var actor = runtime.spawnCodeActorWithReservedHeap(ActorRuntime.ActorKind.SHARED, policy(16*MIB), e, "Worker");
            var ready = actor.ready();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(actor.cancel());
                assertEquals(16*MIB, runtime.sharedMemoryBytes());
                assertThrows(IllegalStateException.class, () -> runtime.spawnCodeActorWithReservedHeap(
                    ActorRuntime.ActorKind.SHARED, policy(16*MIB), e, "Worker"));
            } finally { proceed.countDown(); }
            assertThrows(ExecutionException.class, () -> ready.get(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.sharedMemoryBytes());
        } finally { proceed.countDown(); }
    }
}
