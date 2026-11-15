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

    // The WAL requires a physical, non-aliased directory; on macOS the JUnit
    // temp root lives below the /var -> /private/var system alias.
    @org.junit.jupiter.api.BeforeEach
    void usePhysicalTempRoot() throws java.io.IOException {
        root = root.toRealPath();
    }

    @Test void abandonedOpeningReleasesOnlyAfterTrustedNonstartOrNoResource()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var registry = new DbWalTrustedGrantRegistry(runtime,
                    (actor, tenant, shard) -> tenant == 11 && shard == 5);
            var first = new AtomicReference<DbWalTrustedGrantRegistry.Grant>();
            var second = new AtomicReference<DbWalTrustedGrantRegistry.Grant>();
            var error = new AtomicReference<Throwable>();
            var finished = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if (message.equals("reject_before_start")) {
                        first.set(registry.issueForCurrentActor(11, 5, 1));
                        first.get().reserveOpen(runtime);
                        assertThrows(SecurityException.class,
                                () -> first.get().reserveOpen(runtime));
                        // This is the exact AsyncNativeIo never-started
                        // callback path, not an arbitrary Future cancellation.
                        first.get().neverStartedOpen();
                        assertThrows(IllegalStateException.class,
                                () -> first.get().neverStartedOpen());
                        second.set(registry.issueForCurrentActor(11, 5, 2));
                        second.get().reserveOpen(runtime);
                        finished.countDown();
                    }
                } catch (Throwable failure) {
                    error.set(failure);
                    finished.countDown();
                }
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("reject_before_start");
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(1, registry.activeShardCount());
            assertNotNull(second.get());
            // An attempted worker open with an uncertain result must keep
            // the shard fenced. Only a known physical file whose close()
            // returned successfully can be retired. A fabricated/lost file
            // cannot be used as an authority release receipt.
            var physical = DbDurableWalFile.openHostOnly(root, "retry.wal", 2);
            var unrelated = DbDurableWalFile.openHostOnly(root, "unrelated.wal", 2);
            second.get().notePhysicalOpen(physical);
            assertThrows(SecurityException.class,
                    () -> second.get().notePhysicalOpen(unrelated),
                    "a second physical open must not acquire this grant");
            unrelated.close();
            assertThrows(IllegalStateException.class,
                    () -> second.get().failedOpenAfterVerifiedClose(unrelated),
                    "an unrelated closed file cannot retire the real opening");
            assertThrows(IllegalStateException.class,
                    () -> second.get().failedOpenAfterVerifiedClose(physical));
            assertEquals(1, registry.activeShardCount());
            physical.close();
            second.get().failedOpenAfterVerifiedClose(physical);
            assertThrows(IllegalStateException.class,
                    () -> second.get().failedOpenAfterVerifiedClose(physical));
            assertEquals(0, registry.activeShardCount());
        }
    }

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
            var unauthorizedTenantDenied = new CountDownLatch(1);
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
                        firstGrant.get().reserveOpen(runtime);
                        assertThrows(SecurityException.class,
                                () -> firstGrant.get().reserveOpen(runtime),
                                "one shard must have one pending open");
                        issued.countDown();
                    } else if (message.equals("drain")) {
                        firstGrant.get().beginDrain(runtime);
                        drained.countDown();
                    } else if (message.equals("stale")) {
                        assertThrows(SecurityException.class,
                                () -> firstGrant.get().requireLive(runtime));
                        oldGenerationDenied.countDown();
                    } else if (message.equals("unauthorized_tenant")) {
                        assertThrows(SecurityException.class,
                                () -> registry.issueForCurrentActor(12, 4, 9));
                        assertThrows(SecurityException.class,
                                () -> new DbWalTrustedGrantRegistry(runtime)
                                        .issueForCurrentActor(11, 3, 9));
                        unauthorizedTenantDenied.countDown();
                    }
                } catch (Throwable failure) {
                    firstError.compareAndSet(null, failure);
                    issued.countDown();
                    drained.countDown();
                    oldGenerationDenied.countDown();
                    unauthorizedTenantDenied.countDown();
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
                        secondGrant.get().reserveOpen(runtime);
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
            firstGrant.get().notePhysicalOpen(firstWal);
            firstGrant.get().bindPhysicalFile(firstWal);
            assertThrows(SecurityException.class,
                    () -> firstGrant.get().bindPhysicalFile(firstWal),
                    "a shard must have one physical file owner");
            assertTrue(firstGrant.get().issuedBy(registry));
            assertFalse(firstGrant.get().issuedBy(
                    new DbWalTrustedGrantRegistry(runtime)),
                    "another registry cannot be mistaken for the issuing context");
            assertEquals(1, registry.activeShardCount());
            first.send("unauthorized_tenant");
            assertTrue(unauthorizedTenantDenied.await(3, TimeUnit.SECONDS));
            assertNull(firstError.get(), "untrusted tenant issuance must fail within actor turn");
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
            secondGrant.get().notePhysicalOpen(secondWal);
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
