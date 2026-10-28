package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorNativeRuntimeIntegrationTest {
    @Test
    void snapshotsAndOutputCannotOvercommitNativeActorBudget() throws Exception {
        IsolatePolicy policy = new IsolatePolicy(IsolatePolicy.developer().capabilities(),
                16L * 1024 * 1024, 64, java.time.Duration.ofSeconds(20));
        for (boolean shared : new boolean[] {false, true}) {
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                runtime.enableNativeActorStorage();
                ActorRuntime.BehaviorFactory<String> factory = context -> {
                    ActorNativeArena arena = context.nativeStorage().orElseThrow();
                    // A snapshot is charged outside the private slice but must
                    // still limit subsequent native allocation in either order.
                    try (var snapshot = runtime.reserveActorShardSnapshot((int) (policy.maxHeapBytes() / 8))) {
                        assertThrows(IllegalStateException.class, () -> arena.region(32));
                    }
                    try (var region = arena.region((int) policy.maxHeapBytes())) {
                        assertThrows(IllegalStateException.class, () -> runtime.reserveActorShardSnapshot(1));
                        assertThrows(IllegalStateException.class, () -> runtime.emitCurrentActorOutput(1L));
                    }
                    // An output charge must also constrain a later native allocation.
                    runtime.emitCurrentActorOutput(1L);
                    assertThrows(IllegalStateException.class,
                            () -> arena.region((int) policy.maxHeapBytes()));
                    return (message, turn) -> turn.self().stop();
                };
                ActorRuntime.ActorRef<String> ref = shared
                        ? runtime.spawnSharedTrusted(policy, factory) : runtime.spawnPrivateTrusted(policy, factory);
                ref.ready().get(5, TimeUnit.SECONDS);
                ref.send("stop");
                ref.done().get(5, TimeUnit.SECONDS);
                assertTrue(ref.failure().isEmpty(), ref.failure().toString());
                assertEquals(0, runtime.nativeActorMemoryBytes());
            }
        }
    }

    @Test
    void stopWaitsForOffCarrierLifetimeAndReadLoans() throws Exception {
        for (boolean shared : new boolean[] {false, true}) {
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
                runtime.enableNativeActorStorage();
                AtomicReference<ActorNativeArena> arena = new AtomicReference<>();
                AtomicReference<ActorNativeArena.LifetimePin> pin = new AtomicReference<>();
                AtomicReference<ActorNativeArena.Region.ReadOnlyLease> loan = new AtomicReference<>();
                AtomicReference<ActorNativeArena.Region> region = new AtomicReference<>();
                ActorRuntime.BehaviorFactory<String> factory = context -> {
                    ActorNativeArena a = context.nativeStorage().orElseThrow();
                    var r = a.region(32);
                    r.writeLong(0, 42L);
                    arena.set(a);
                    region.set(r);
                    pin.set(a.acquireLifetimePin());
                    loan.set(r.leaseReadOnly(0, Long.BYTES));
                    return (message, turn) -> turn.self().stop();
                };
                ActorRuntime.ActorRef<String> ref = shared
                        ? runtime.spawnSharedTrusted(factory) : runtime.spawnPrivateTrusted(factory);
                ref.ready().get(5, TimeUnit.SECONDS);
                assertThrows(SecurityException.class, () -> region.get().readLong(0));
                assertThrows(SecurityException.class, () -> arena.get().acquireLifetimePin());
                // A valid turn for another actor must not authorize this arena.
                ActorRuntime.ActorRef<String> foreign = runtime.spawnSharedTrusted(context -> {
                    assertThrows(SecurityException.class, () -> region.get().readLong(0));
                    assertThrows(SecurityException.class, () -> arena.get().acquireLifetimePin());
                    ActorNativeArena own = context.nativeStorage().orElseThrow();
                    assertNotSame(arena.get(), own);
                    try (var local = own.region(32)) {
                        local.writeLong(0, 99L);
                        assertEquals(99L, local.readLong(0));
                    }
                    return (message, turn) -> turn.self().stop();
                });
                foreign.ready().get(5, TimeUnit.SECONDS);
                foreign.send("stop");
                foreign.done().get(5, TimeUnit.SECONDS);
                assertTrue(foreign.failure().isEmpty(), foreign.failure().toString());
                assertThrows(IllegalStateException.class, runtime::enableNativeActorStorage);
                ref.send("stop");
                // awaitTermination observes the physical finalization gate.
                assertFalse(ref.awaitTermination(100, TimeUnit.MILLISECONDS));
                assertFalse(ref.done().isDone());
                assertTrue(runtime.nativeActorMemoryBytes() > 0);
                pin.get().close();
                pin.get().close();
                assertFalse(ref.done().isDone());
                loan.get().close();
                loan.get().close();
                ref.done().get(5, TimeUnit.SECONDS);
                assertTrue(arena.get().cleanupComplete());
                assertEquals(0, arena.get().committedBytes());
                assertEquals(0, runtime.nativeActorMemoryBytes());
                assertTrue(ref.failure().isEmpty(), ref.failure().toString());
            }
        }
    }
}
