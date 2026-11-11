package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real ActorRuntime/managed-worker integration, not a source-free oracle. */
@Timeout(45)
final class ActorStateSupervisorLoansTest {

    private static void sendAndDrain(
            ActorRuntime.ActorRef<String> worker, String message) throws Exception {
        worker.send(message);
        worker.drain().get(5, TimeUnit.SECONDS);
    }

    private static final class Fixture implements AutoCloseable {
        final ActorRuntime runtime = new ActorRuntime();
        final ActorRuntime.ActorSupervisor supervisor = runtime.createSupervisor();
        final ActorRuntime.ActorSupervisor foreign = runtime.createSupervisor();
        final ActorRuntime.ActorGroup group = runtime.createActorGroup(supervisor);
        final AtomicReference<ActorStateSupervisorLoans> region = new AtomicReference<>();
        final ConcurrentLinkedQueue<ActorStateSupervisorLoans.Grant> grants =
                new ConcurrentLinkedQueue<>();
        final AtomicInteger ownerReadOperations = new AtomicInteger();
        final AtomicReference<Throwable> denied = new AtomicReference<>();
        final ActorRuntime.ActorRef<String> worker;

        Fixture() {
            worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> {
                        switch (message) {
                            case "create" -> region.set(
                                    runtime.createCurrentActorStateLoans(8, 2));
                            case "read" -> grants.add(
                                    region.get().delegateRead(supervisor));
                            case "write" -> grants.add(
                                    region.get().delegateWrite(supervisor));
                            case "foreign" -> {
                                try {
                                    region.get().delegateRead(foreign);
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "owner-read" -> {
                                try {
                                    region.get().withOwnerRead(ownerReadOperations::incrementAndGet);
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "owner-write-throw" -> {
                                try {
                                    region.get().withOwnerWrite(() -> {
                                        throw new IllegalStateException("injected owner handler failure");
                                    });
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "nested-owner" -> {
                                try {
                                    region.get().withOwnerRead(() ->
                                            region.get().withOwnerRead(
                                                    ownerReadOperations::incrementAndGet));
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "quota" -> {
                                try {
                                    region.get().delegateRead(supervisor);
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            default -> throw new IllegalArgumentException(message);
                        }
                    });
        }

        ActorStateSupervisorLoans create() throws Exception {
            worker.ready().get(5, TimeUnit.SECONDS);
            sendAndDrain(worker, "create");
            assertNotNull(region.get());
            return region.get();
        }

        ActorStateSupervisorLoans.Grant issue(String mode) throws Exception {
            sendAndDrain(worker, mode);
            var grant = grants.poll();
            assertNotNull(grant);
            return grant;
        }

        @Override public void close() {
            try { group.close(); } finally { runtime.close(); }
        }
    }

    @Test
    void unrelatedHostAndUnshepherdedActorCannotMintRegion() throws Exception {
        try (var runtime = new ActorRuntime()) {
            assertThrows(SecurityException.class,
                    () -> runtime.createCurrentActorStateLoans(2, 1));
            ActorRuntime.ActorRef<String> freeActor = runtime.spawnPrivate(
                    context -> (message, actorContext) -> {
                        assertThrows(SecurityException.class,
                                () -> runtime.createCurrentActorStateLoans(2, 1));
                    });
            freeActor.ready().get(5, TimeUnit.SECONDS);
            sendAndDrain(freeActor, "check");
            freeActor.stop();
            assertTrue(freeActor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void ownerTurnMintsARegionButForeignDirectSupervisorCannotIssueGrant()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            sendAndDrain(fixture.worker, "foreign");
            assertInstanceOf(SecurityException.class, fixture.denied.get());
            assertEquals(0, region.outstandingGrantCount());
            assertThrows(SecurityException.class,
                    () -> region.delegateRead(fixture.supervisor));
            assertThrows(SecurityException.class,
                    () -> region.withOwnerRead(() -> { }));
            assertEquals(0, region.activeReaders());
        }
    }

    @Test
    void supervisorGrantIsOneUseAndCannotCrossRegions() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var grant = fixture.issue("read");
            assertEquals(1, region.outstandingGrantCount());
            assertThrows(SecurityException.class,
                    () -> region.redeem(grant, fixture.foreign));
            assertEquals(1, region.outstandingGrantCount(),
                    "rejection by a foreign supervisor must not consume grant");

            sendAndDrain(fixture.worker, "create");
            var secondRegion = fixture.region.get();
            assertNotSame(region, secondRegion);
            assertThrows(SecurityException.class,
                    () -> secondRegion.redeem(grant, fixture.supervisor),
                    "a token from one actor region cannot be replayed into another");
            // A separate region requires an independently minted token.
            var loan = region.redeem(grant, fixture.supervisor).join();
            assertEquals(0, region.outstandingGrantCount());
            assertEquals(1, region.activeReaders());
            assertThrows(SecurityException.class,
                    () -> region.redeem(grant, fixture.supervisor));
            loan.close();
            assertEquals(0, region.activeReaders());
        }
    }

    @Test
    void directSupervisorRwLoansAreFairAndWriterExclusive() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var readOne = fixture.issue("read");
            var readTwo = fixture.issue("read");
            var write = fixture.issue("write");
            var lateRead = fixture.issue("read");

            var r1 = region.redeem(readOne, fixture.supervisor).join();
            var r2 = region.redeem(readTwo, fixture.supervisor).join();
            var waitingWrite = region.redeem(write, fixture.supervisor);
            var waitingRead = region.redeem(lateRead, fixture.supervisor);
            assertEquals(2, region.activeReaders());
            assertEquals(0, region.activeWriters());
            assertFalse(waitingWrite.isDone());
            assertFalse(waitingRead.isDone());

            r1.close();
            assertFalse(waitingWrite.isDone());
            r2.close();
            var writer = waitingWrite.join();
            assertEquals(1, region.activeWriters());
            assertFalse(waitingRead.isDone());
            writer.close();
            waitingRead.join().close();
            assertEquals(0, region.pendingCount());
        }
    }

    @Test
    void actorOwnedReadIsLexicalAndFailsFastAgainstDelegatedWrite() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var grant = fixture.issue("write");
            var writer = region.redeem(grant, fixture.supervisor).join();
            sendAndDrain(fixture.worker, "owner-read");
            assertInstanceOf(IllegalStateException.class, fixture.denied.get(),
                    "owner turn must not park its carrier to await supervisor write");
            assertEquals(0, fixture.ownerReadOperations.get());
            assertEquals(1, region.activeWriters());

            writer.close();
            fixture.denied.set(null);
            sendAndDrain(fixture.worker, "owner-read");
            assertNull(fixture.denied.get());
            assertEquals(1, fixture.ownerReadOperations.get());
            assertEquals(0, region.activeReaders(),
                    "owner read must be released before mailbox turn exits");
        }
    }

    @Test
    void failedLexicalOwnerHandlerStillReleasesItsExclusiveLoan() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            sendAndDrain(fixture.worker, "owner-write-throw");
            assertInstanceOf(IllegalStateException.class, fixture.denied.get());
            assertEquals(0, region.activeWriters());
            assertEquals(0, region.pendingCount());
            sendAndDrain(fixture.worker, "owner-read");
            assertEquals(1, fixture.ownerReadOperations.get());
        }
    }

    @Test
    void stopRevokesUnusedGrantAndWaitsForPhysicalSupervisorLoan()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var stale = fixture.issue("read");
            var live = fixture.issue("write");
            var loan = region.redeem(live, fixture.supervisor).join();

            fixture.worker.stop();
            assertFalse(fixture.worker.done().isDone(),
                    "STOP cannot reclaim while a physical supervisor write remains live");
            assertFalse(region.physicallyRetired());
            assertThrows(SecurityException.class,
                    () -> region.redeem(stale, fixture.supervisor));
            loan.close();
            assertTrue(fixture.worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(region.physicallyRetired());
        }
    }

    @Test
    void cancelledQueuedGrantDoesNotLeavePhysicalStateLoanPinned()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var first = fixture.issue("write");
            var blocked = fixture.issue("read");
            var held = region.redeem(first, fixture.supervisor).join();
            var queued = region.redeem(blocked, fixture.supervisor);
            assertFalse(queued.isDone());
            assertTrue(queued.cancel(false));
            held.close();
            fixture.worker.stop();
            assertTrue(fixture.worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(region.physicallyRetired());
        }
    }

    @Test
    void grantQuotaIsBoundedAt64AndSurplusDoesNotCreateAuthority()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            List<ActorStateSupervisorLoans.Grant> tokens = new ArrayList<>();
            for (int i = 0; i < 64; ++i) {
                tokens.add(fixture.issue("read"));
            }
            assertEquals(64, region.outstandingGrantCount());
            sendAndDrain(fixture.worker, "quota");
            assertInstanceOf(IllegalStateException.class, fixture.denied.get());
            assertEquals(64, region.outstandingGrantCount(),
                    "failed issue cannot enlarge the capability ledger");
        }
    }

    @Test
    void callerCreatedOwnerRecordsCannotForgeRuntimeAllocationMints() {
        try (var runtime = new ActorRuntime()) {
            var invented = new ExecutionContext.AllocationDomain(
                    ExecutionContext.AllocationKind.ACTOR, 1, 1, 7);
            var inventedActor = ActorRuntime.ActorId.create();
            assertThrows(SecurityException.class, () ->
                    new ActorStateSupervisorLoans(
                            runtime, new Object(), inventedActor, 7, invented, 2, 1));
            assertThrows(SecurityException.class, () ->
                    new ActorStateSupervisorLoans(
                            runtime, null, inventedActor, 7, invented, 2, 1));
        }
    }


    @Test
    void twoHostClaimantsCannotRedeemTheSameGrantTwice() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var grant = fixture.issue("read");
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var successful = new AtomicInteger();
            var rejected = new AtomicInteger();
            var unexpected = new AtomicReference<Throwable>();
            var winner = new AtomicReference<OresFuture<ActorStateSupervisorLoans.Loan>>();
            Runnable claim = () -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("host claimants did not start");
                    }
                    var accepted = region.redeem(grant, fixture.supervisor);
                    winner.set(accepted);
                    successful.incrementAndGet();
                } catch (SecurityException expected) {
                    rejected.incrementAndGet();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            };
            var first = Thread.ofPlatform().start(claim);
            var second = Thread.ofPlatform().start(claim);
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
                first.join(5000);
                second.join(5000);
            }
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertNull(unexpected.get());
            assertEquals(1, successful.get());
            assertEquals(1, rejected.get());
            assertEquals(0, region.outstandingGrantCount());
            winner.get().join().close();
            assertEquals(0, region.activeReaders());
        }
    }

    @Test
    void stopFailsQueuedGrantButDoesNotUnpinActiveSupervisorWriter()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var write = fixture.issue("write");
            var waitingRead = fixture.issue("read");
            var held = region.redeem(write, fixture.supervisor).join();
            var queued = region.redeem(waitingRead, fixture.supervisor);
            assertFalse(queued.isDone());

            fixture.worker.stop();
            assertTrue(queued.isCompletedExceptionally(),
                    "STOP should fail but physically drain queued host notifications");
            assertFalse(fixture.worker.done().isDone(),
                    "the active writer keeps actor memory physically pinned");
            assertFalse(region.physicallyRetired());
            held.close();
            assertTrue(fixture.worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(region.physicallyRetired());
        }
    }


    @Test
    void nestedOwnerReadCannotLaunderRecursiveEntryViaNewExecutionId()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            sendAndDrain(fixture.worker, "nested-owner");
            assertInstanceOf(IllegalStateException.class, fixture.denied.get());
            assertEquals(0, fixture.ownerReadOperations.get());
            assertEquals(0, region.activeReaders(),
                    "outer owner read must still unwind on nested denial");
            fixture.denied.set(null);
            sendAndDrain(fixture.worker, "owner-read");
            assertNull(fixture.denied.get());
            assertEquals(1, fixture.ownerReadOperations.get());
        }
    }

}
