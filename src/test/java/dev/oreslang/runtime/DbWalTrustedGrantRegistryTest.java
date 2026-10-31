package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only registry admission. The test supplies a trusted tenant mapping;
 * it does NOT authenticate a guest principal or prove native durability.
 */
@Timeout(25)
final class DbWalTrustedGrantRegistryTest {
    @TempDir Path root;

    @Test void shardIsReservedAcrossDrainUntilPhysicalRelease() throws Exception {
        try (var runtime = new ActorRuntime()) {
            // Test-only synthetic host authorization: production contexts
            // use the registry's default-deny policy until a trusted host
            // supplies a real authenticated tenant mapping.
            var registry = new DbWalTrustedGrantRegistry(runtime,
                    (actor, tenant, shard) -> tenant == 11 && shard == 3);
            var firstGrant = new AtomicReference<DbWalTrustedGrantRegistry.Grant>();
            var secondGrant = new AtomicReference<DbWalTrustedGrantRegistry.Grant>();
            var firstError = new AtomicReference<Throwable>();
            var secondError = new AtomicReference<Throwable>();
            var issued = new CountDownLatch(1);
            var foreignRejected = new CountDownLatch(1);
            var duplicateRejected = new CountDownLatch(1);
            var drainingConflictRejected = new CountDownLatch(1);
            var drained = new CountDownLatch(1);
            var reissued = new CountDownLatch(1);
            var oldGenerationDenied = new CountDownLatch(1);
            var sealedDenied = new CountDownLatch(1);
            var first = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if (message.equals("issue")) {
                        firstGrant.set(registry.issueForCurrentActor(11, 3, 7));
                        issued.countDown();
                    } else if (message.equals("drain")) {
                        firstGrant.get().beginDrain(runtime);
                        drained.countDown();
                    } else if (message.equals("stale")) {
                        assertThrows(SecurityException.class,
                                () -> firstGrant.get().requireLive(runtime));
                        oldGenerationDenied.countDown();
                    }
                } catch (Throwable failure) {
                    firstError.compareAndSet(null, failure);
                    issued.countDown();
                    drained.countDown();
                    oldGenerationDenied.countDown();
                }
            });
            var second = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if (message.equals("foreign")) {
                        assertThrows(SecurityException.class,
                                () -> firstGrant.get().requireLive(runtime));
                        foreignRejected.countDown();
                    } else if (message.equals("conflict")) {
                        assertThrows(IllegalStateException.class,
                                () -> registry.issueForCurrentActor(11, 3, 8));
                        duplicateRejected.countDown();
                    } else if (message.equals("draining_conflict")) {
                        assertThrows(IllegalStateException.class,
                                () -> registry.issueForCurrentActor(11, 3, 8));
                        drainingConflictRejected.countDown();
                    } else if (message.equals("issue")) {
                        secondGrant.set(registry.issueForCurrentActor(11, 3, 8));
                        reissued.countDown();
                    } else if (message.equals("sealed")) {
                        assertThrows(SecurityException.class,
                                () -> secondGrant.get().requireLive(runtime));
                        assertThrows(SecurityException.class,
                                () -> registry.issueForCurrentActor(12, 4, 9));
                        sealedDenied.countDown();
                    }
                } catch (Throwable failure) {
                    secondError.compareAndSet(null, failure);
                    foreignRejected.countDown();
                    duplicateRejected.countDown();
                    drainingConflictRejected.countDown();
                    reissued.countDown();
                    sealedDenied.countDown();
                }
            });
            first.ready().get(3, TimeUnit.SECONDS);
            second.ready().get(3, TimeUnit.SECONDS);
            first.send("issue");
            assertTrue(issued.await(3, TimeUnit.SECONDS));
            assertNull(firstError.get());
            assertNotNull(firstGrant.get());
            var firstWal = DbDurableWalFile.openHostOnly(root, "first.wal", 7);
            firstGrant.get().bindPhysicalFile(firstWal);
            assertThrows(SecurityException.class,
                    () -> firstGrant.get().bindPhysicalFile(firstWal),
                    "a shard must have one physical file owner");
            assertTrue(firstGrant.get().issuedBy(registry));
            assertFalse(firstGrant.get().issuedBy(
                    new DbWalTrustedGrantRegistry(runtime)),
                    "another registry cannot be mistaken for the issuing context");
            assertEquals(1, registry.activeShardCount());
            assertThrows(SecurityException.class,
                    () -> new DbWalTrustedGrantRegistry(runtime)
                            .issueForCurrentActor(11, 3, 9),
                    "unauthenticated issuance outside actor must fail"); 
            assertThrows(SecurityException.class,
                    () -> firstGrant.get().requireLive(runtime),
                    "a host thread outside the actor turn is not the grant owner");

            second.send("foreign");
            assertTrue(foreignRejected.await(3, TimeUnit.SECONDS));
            second.send("conflict");
            assertTrue(duplicateRejected.await(3, TimeUnit.SECONDS));
            assertNull(secondError.get());

            first.send("drain");
            assertTrue(drained.await(3, TimeUnit.SECONDS));
            assertNull(firstError.get());
            assertEquals(1, registry.activeShardCount(),
                    "a draining shard cannot be reissued to a new generation");
            second.send("draining_conflict");
            assertTrue(drainingConflictRejected.await(3, TimeUnit.SECONDS));
            assertNull(secondError.get());
            assertThrows(SecurityException.class,
                    () -> registry.issueForCurrentActor(11, 3, 8),
                    "host calls outside actor must be rejected");
            var physical = new DbWalAuthorityLifecycle();
            assertThrows(IllegalStateException.class,
                    () -> firstGrant.get().physicalRetire(firstWal, physical),
                    "cannot release a shard while its WAL file is open");
            assertTrue(physical.requestDrain());
            assertTrue(physical.beginClose());
            physical.physicalCloseRetired();
            assertThrows(IllegalStateException.class,
                    () -> firstGrant.get().physicalRetire(firstWal, physical),
                    "lifecycle state alone is insufficient while file is open");
            firstWal.close();
            firstGrant.get().physicalRetire(firstWal, physical);
            assertEquals(0, registry.activeShardCount());
            assertThrows(IllegalStateException.class,
                    () -> firstGrant.get().physicalRetire(firstWal, physical));

            second.send("issue");
            assertTrue(reissued.await(3, TimeUnit.SECONDS));
            assertNull(secondError.get());
            assertNotNull(secondGrant.get());
            var secondWal = DbDurableWalFile.openHostOnly(root, "second.wal", 8);
            secondGrant.get().bindPhysicalFile(secondWal);
            assertEquals(8, secondGrant.get().generation());
            assertEquals(1, registry.activeShardCount());
            first.send("stale");
            assertTrue(oldGenerationDenied.await(3, TimeUnit.SECONDS));
            assertNull(firstError.get());
            registry.seal();
            assertEquals(1, registry.activeShardCount(),
                    "sealing must retain a live generation until physical retirement");
            second.send("sealed");
            assertTrue(sealedDenied.await(3, TimeUnit.SECONDS));
            assertNull(secondError.get());
            secondWal.close();
        }
    }
}
