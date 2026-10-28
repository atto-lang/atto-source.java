package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorNativeRuntimeIntegrationTest {
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
