package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real ActorRuntime/managed-worker integration, not a source-free oracle. */
@Timeout(20)
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
        final AtomicReference<ActorStateSupervisorLoans.Loan> actorLoan =
                new AtomicReference<>();
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
                            case "owner-read" -> actorLoan.set(
                                    region.get().ownerReadAsync().join());
                            case "owner-release" -> actorLoan.get().close();
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
            var freeActor = runtime.spawnPrivate(
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
                    () -> region.ownerReadAsync());
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

            var secondRegion = fixture.region.get();
            // A separate region requires an independently minted token.
            var loan = region.redeem(grant, fixture.supervisor).join();
            assertSame(region, secondRegion);
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
    void actorOwnedReadAndDelegatedWriteShareOneLockDomain() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            sendAndDrain(fixture.worker, "owner-read");
            assertEquals(1, region.activeReaders());
            assertThrows(SecurityException.class,
                    () -> fixture.actorLoan.get().close(),
                    "host must not terminate another actor turn's read loan");

            var grant = fixture.issue("write");
            var pending = region.redeem(grant, fixture.supervisor);
            assertFalse(pending.isDone());
            sendAndDrain(fixture.worker, "owner-release");
            pending.join().close();
            assertEquals(0, region.activeReaders());
            assertEquals(0, region.activeWriters());
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
            assertThrows(java.util.concurrent.ExecutionException.class, () -> {
                // quota rejection is thrown from the actor message handler.
                fixture.worker.send("read");
                fixture.worker.drain().get(5, TimeUnit.SECONDS);
            });
            assertEquals(64, region.outstandingGrantCount());
        }
    }
}
