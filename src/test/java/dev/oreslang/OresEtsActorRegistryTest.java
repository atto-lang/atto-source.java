package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresEtsActorRegistry;
import dev.oreslang.runtime.OresEtsActorRegistry.Access;
import dev.oreslang.runtime.OresSymbol;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Tests actual ActorRuntime-issued identities, not forged ActorId strings. */
final class OresEtsActorRegistryTest {
    @Test
    void privateProtectedAndPublicAuthorization() {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 4)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var privateId = registry.<Object, Integer>create(owner, Access.PRIVATE, 2, 1024);
            assertTrue(registry.insertNew(privateId, owner, OresSymbol.process("ready"), 1));
            assertEquals(1, registry.lookup(privateId, owner, OresSymbol.process("ready")).orElseThrow());
            assertThrows(SecurityException.class, () -> registry.lookup(privateId, peer, OresSymbol.process("ready")));
            assertThrows(SecurityException.class, () -> registry.insert(privateId, peer, "x", 7));

            var protectedId = registry.<String, Integer>create(owner, Access.PROTECTED, 2, 1024);
            assertTrue(registry.insertNew(protectedId, owner, "score", 2));
            assertEquals(2, registry.lookup(protectedId, peer, "score").orElseThrow());
            assertThrows(SecurityException.class, () -> registry.take(protectedId, peer, "score"));
            assertThrows(SecurityException.class, () -> registry.compareExchange(protectedId, peer, "score", 2, 3));

            var publicId = registry.<String, Integer>create(owner, Access.PUBLIC, 2, 1024);
            assertTrue(registry.insertNew(publicId, peer, "score", 7));
            assertTrue(registry.compareExchange(publicId, peer, "score", 7, 8));
            assertEquals(8, registry.lookup(publicId, owner, "score").orElseThrow());
        }
    }

    @Test
    void foreignRuntimeCannotBorrowOwnerOrTableCapability() {
        try (var a = new ActorRuntime();
             var b = new ActorRuntime();
             var first = new OresEtsActorRegistry(a, 2);
             var second = new OresEtsActorRegistry(b, 2)) {
            var ownerA = a.<String>spawnShared(() -> (message, context) -> {});
            var actorB = b.<String>spawnShared(() -> (message, context) -> {});
            assertThrows(SecurityException.class, () -> first.create(actorB, Access.PUBLIC, 1, 64));
            var id = first.<String, String>create(ownerA, Access.PUBLIC, 2, 64);
            assertThrows(SecurityException.class, () -> first.lookup(id, actorB, "x"));
            assertThrows(SecurityException.class, () -> second.lookup(id, actorB, "x"));
            assertEquals(1, first.liveTableCount());
        }
    }

    @Test
    void quotaRetirementAndStaleHandleFailClosed() {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var token = registry.<String, String>create(owner, Access.PRIVATE, 1, 4);
            assertTrue(registry.insertNew(token, owner, "a", "b"));
            assertEquals(2L, registry.byteSize(token, owner));
            assertThrows(IllegalStateException.class,
                    () -> registry.create(owner, Access.PRIVATE, 1, 4));
            registry.retire(token, owner);
            assertThrows(IllegalStateException.class, () -> registry.lookup(token, owner, "a"));
            assertEquals(0, registry.liveTableCount());
            var second = registry.<String, String>create(owner, Access.PRIVATE, 1, 4);
            assertNotSame(token, second);
            assertTrue(registry.insertNew(second, owner, "a", "b"));
            assertEquals(1, registry.liveTableCount());
        }
    }

    @Test
    void ownerStopRevokesAllAccessAndReclaimsTableOnHost() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var peer = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, String>create(owner, Access.PUBLIC, 1, 128);
            registry.insert(id, owner, "key", "value");
            owner.stop();
            assertFalse(owner.isAlive());
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "key"));
            assertEquals(1, registry.reap());
            assertEquals(0, registry.liveTableCount());
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, peer, "key"));
            var replacement = registry.<String, String>create(peer, Access.PRIVATE, 1, 128);
            assertNotSame(id, replacement);
        }
    }

    @Test
    void actorCarrierCannotCallHostEtsOrReaper() throws Exception {
        try (var runtime = new ActorRuntime();
             var registry = new OresEtsActorRegistry(runtime, 1)) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var id = registry.<String, Integer>create(owner, Access.PUBLIC, 1, 128);
            var checked = new CountDownLatch(1);
            var rejected = new AtomicBoolean();
            var worker = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    boolean ops = assertThrows(IllegalStateException.class,
                            () -> registry.lookup(id, context.self(), "key")) != null;
                    boolean cleanup = assertThrows(IllegalStateException.class,
                            registry::reap) != null;
                    rejected.set(ops && cleanup);
                } finally {
                    checked.countDown();
                }
            });
            worker.send("ping");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(rejected.get());
        }
    }

    @Test
    void closedRegistryRevokesEveryHandle() {
        try (var runtime = new ActorRuntime()) {
            var owner = runtime.<String>spawnShared(() -> (message, context) -> {});
            var registry = new OresEtsActorRegistry(runtime, 1);
            var id = registry.<String, String>create(owner, Access.PUBLIC, 2, 64);
            registry.close();
            registry.close();
            assertThrows(IllegalStateException.class, () -> registry.lookup(id, owner, "key"));
            assertThrows(IllegalStateException.class, () -> registry.create(owner, Access.PUBLIC, 1, 64));
        }
    }
}
