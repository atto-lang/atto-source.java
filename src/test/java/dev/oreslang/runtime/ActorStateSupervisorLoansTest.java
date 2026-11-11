package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real ActorRuntime/managed-worker integration, not a source-free oracle. */
@Timeout(60)
final class ActorStateSupervisorLoansTest {

    private static void sendAndDrain(
            ActorRuntime.ActorRef<String> worker, String message) throws Exception {
        worker.send(message);
        worker.drain().get(5, TimeUnit.SECONDS);
    }

    private static final class Fixture implements AutoCloseable {
        final ExecutorService controlPool = Executors.newFixedThreadPool(3);
        final ActorRuntime.ControlDispatcher dispatcher = work -> {
            var receipt = new OresFuture<Void>();
            controlPool.execute(() -> {
                try {
                    work.run();
                    receipt.completeFromRuntime(null);
                } catch (Throwable error) {
                    receipt.failFromRuntime(error);
                }
            });
            return receipt;
        };
        final ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(), dispatcher);
        final ActorRuntime.ActorSupervisor supervisor = runtime.createSupervisor();
        final ActorRuntime.ActorSupervisor foreign = runtime.createSupervisor();
        final ActorRuntime.ActorGroup group = runtime.createActorGroup(supervisor);
        final AtomicReference<ActorStateSupervisorLoans> region = new AtomicReference<>();
        final AtomicReference<ActorStateSupervisorLoans.LongField> typedBalance =
                new AtomicReference<>();
        final AtomicReference<ActorStateSupervisorLoans.LongField> typedLate =
                new AtomicReference<>();
        final ConcurrentLinkedQueue<ActorStateSupervisorLoans.Grant> grants =
                new ConcurrentLinkedQueue<>();
        final AtomicInteger ownerReadOperations = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong ownerScalarObserved =
                new java.util.concurrent.atomic.AtomicLong(-1);
        final AtomicReference<Throwable> denied = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch ownerUpdateEntered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch allowOwnerUpdate =
                new java.util.concurrent.CountDownLatch(1);
        final ActorRuntime.ActorRef<String> worker;

        Fixture() {
            worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> {
                        switch (message) {
                            case "create" -> {
                                ActorStateSupervisorLoans created =
                                        runtime.createCurrentActorStateLoans(8, 2);
                                region.set(created);
                                typedBalance.set(created.declareOwnerLongField("balance", 5L));
                            }
                            case "typed-owner-add-field" -> {
                                try {
                                    typedLate.set(region.get().declareOwnerLongField("late", 71L));
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "typed-owner-duplicate-field" -> {
                                try {
                                    region.get().declareOwnerLongField("balance", 99L);
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "typed-owner-read" -> {
                                try {
                                    ownerScalarObserved.set(
                                            region.get().readOwnerLongField(typedBalance.get()));
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "typed-owner-increment" -> {
                                try {
                                    region.get().updateOwnerLongField(
                                            typedBalance.get(), value -> value + 1);
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "typed-owner-paused" -> {
                                try {
                                    region.get().updateOwnerLongField(
                                            typedBalance.get(), value -> {
                                                ownerUpdateEntered.countDown();
                                                try {
                                                    if (!allowOwnerUpdate.await(5, TimeUnit.SECONDS)) {
                                                        throw new IllegalStateException(
                                                                "typed owner update timed out");
                                                    }
                                                } catch (InterruptedException interrupted) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(
                                                            "typed owner update interrupted", interrupted);
                                                }
                                                return value + 10;
                                            });
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
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
                            case "scalar-owner-read" -> {
                                try {
                                    region.get().withOwnerScalarRead(ownerScalarObserved::set);
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "scalar-owner-increment" -> {
                                try {
                                    region.get().withOwnerScalarUpdate(value -> value + 1);
                                } catch (Throwable rejected) {
                                    denied.set(rejected);
                                }
                            }
                            case "paused-owner-update" -> {
                                try {
                                    region.get().withOwnerScalarUpdate(value -> {
                                        ownerUpdateEntered.countDown();
                                        try {
                                            if (!allowOwnerUpdate.await(5, TimeUnit.SECONDS)) {
                                                throw new IllegalStateException("owner update timed out");
                                            }
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException(
                                                    "owner update interrupted", interrupted);
                                        }
                                        return value + 100;
                                    });
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

        <T> T asControl(
                ActorRuntime.ActorSupervisor principal, Callable<T> action) throws Exception {
            var observed = new CompletableFuture<T>();
            runtime.dispatchSupervisorStateControl(principal, () -> {
                try {
                    observed.complete(action.call());
                } catch (Throwable failure) {
                    observed.completeExceptionally(failure);
                }
            }).get(5, TimeUnit.SECONDS);
            try {
                return observed.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException rejected) {
                Throwable cause = rejected.getCause();
                if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                if (cause instanceof Error fatal) throw fatal;
                throw rejected;
            }
        }

        OresFuture<ActorStateSupervisorLoans.Loan> redeem(
                ActorStateSupervisorLoans active,
                ActorStateSupervisorLoans.Grant grant) throws Exception {
            return redeemAs(supervisor, active, grant);
        }

        OresFuture<ActorStateSupervisorLoans.Loan> redeemAs(
                ActorRuntime.ActorSupervisor principal,
                ActorStateSupervisorLoans active,
                ActorStateSupervisorLoans.Grant grant) throws Exception {
            return asControl(principal, () -> active.redeem(grant, principal));
        }

        void scopedRead(
                ActorStateSupervisorLoans active, ActorStateSupervisorLoans.Grant grant,
                java.util.function.Consumer<ActorStateSupervisorLoans.ScopedScalar> callback)
                throws Exception {
            asControl(supervisor, () -> {
                active.withSupervisorRead(grant, supervisor, callback);
                return null;
            });
        }

        void scopedWrite(
                ActorStateSupervisorLoans active, ActorStateSupervisorLoans.Grant grant,
                java.util.function.Consumer<ActorStateSupervisorLoans.ScopedScalar> callback)
                throws Exception {
            asControl(supervisor, () -> {
                active.withSupervisorWrite(grant, supervisor, callback);
                return null;
            });
        }

        void release(ActorStateSupervisorLoans.Loan loan) throws Exception {
            asControl(supervisor, () -> { loan.close(); return null; });
        }

        long read(ActorStateSupervisorLoans.Loan loan) throws Exception {
            return asControl(supervisor, loan::readScalar);
        }

        void write(ActorStateSupervisorLoans.Loan loan, long value) throws Exception {
            asControl(supervisor, () -> { loan.writeScalar(value); return null; });
        }

        @Override public void close() {
            try {
                group.close();
            } finally {
                try { runtime.close(); }
                finally { controlPool.shutdownNow(); }
            }
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
                    () -> fixture.redeemAs(fixture.foreign, region, grant));
            assertEquals(1, region.outstandingGrantCount(),
                    "rejection by a foreign supervisor must not consume grant");

            sendAndDrain(fixture.worker, "create");
            var secondRegion = fixture.region.get();
            assertNotSame(region, secondRegion);
            assertThrows(SecurityException.class,
                    () -> fixture.redeem(secondRegion, grant),
                    "a token from one actor region cannot be replayed into another");
            // A separate region requires an independently minted token.
            var loan = fixture.redeem(region, grant).join();
            assertEquals(0, region.outstandingGrantCount());
            assertEquals(1, region.activeReaders());
            assertThrows(SecurityException.class,
                    () -> fixture.redeem(region, grant));
            fixture.release(loan);
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

            var r1 = fixture.redeem(region, readOne).join();
            var r2 = fixture.redeem(region, readTwo).join();
            var waitingWrite = fixture.redeem(region, write);
            var waitingRead = fixture.redeem(region, lateRead);
            assertEquals(2, region.activeReaders());
            assertEquals(0, region.activeWriters());
            assertFalse(waitingWrite.isDone());
            assertFalse(waitingRead.isDone());

            fixture.release(r1);
            assertFalse(waitingWrite.isDone());
            fixture.release(r2);
            var writer = waitingWrite.join();
            assertEquals(1, region.activeWriters());
            assertFalse(waitingRead.isDone());
            fixture.release(writer);
            fixture.release(waitingRead.join());
            assertEquals(0, region.pendingCount());
        }
    }

    @Test
    void actorOwnedReadIsLexicalAndFailsFastAgainstDelegatedWrite() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var grant = fixture.issue("write");
            var writer = fixture.redeem(region, grant).join();
            sendAndDrain(fixture.worker, "owner-read");
            assertInstanceOf(IllegalStateException.class, fixture.denied.get(),
                    "owner turn must not park its carrier to await supervisor write");
            assertEquals(0, fixture.ownerReadOperations.get());
            assertEquals(1, region.activeWriters());

            fixture.release(writer);
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
            var loan = fixture.redeem(region, live).join();

            assertTrue(fixture.worker.cancel(),
                    "structured cancellation must seal without awaiting live physical loan");
            assertFalse(fixture.worker.done().isDone(),
                    "STOP cannot reclaim while a physical supervisor write remains live");
            assertFalse(region.physicallyRetired());
            assertThrows(SecurityException.class,
                    () -> fixture.redeem(region, stale));
            fixture.release(loan);
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
            var held = fixture.redeem(region, first).join();
            var queued = fixture.redeem(region, blocked);
            assertFalse(queued.isDone());
            assertTrue(queued.cancel(false));
            fixture.release(held);
            assertTrue(fixture.worker.cancel(),
                    "structured cancellation must seal without awaiting live physical loan");
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
                    var accepted = fixture.redeem(region, grant);
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
            fixture.release(winner.get().join());
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
            var held = fixture.redeem(region, write).join();
            var queued = fixture.redeem(region, waitingRead);
            assertFalse(queued.isDone());

            assertTrue(fixture.worker.cancel(),
                    "structured cancellation must seal without awaiting live physical loan");
            assertTrue(queued.isCompletedExceptionally(),
                    "STOP should fail but physically drain queued host notifications");
            assertFalse(fixture.worker.done().isDone(),
                    "the active writer keeps actor memory physically pinned");
            assertFalse(region.physicallyRetired());
            fixture.release(held);
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


    @Test
    void hostThreadCannotImpersonateSupervisorControlOrReleaseLoan() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var grant = fixture.issue("read");
            assertThrows(SecurityException.class,
                    () -> region.redeem(grant, fixture.supervisor));
            assertEquals(1, region.outstandingGrantCount(),
                    "missing CONTROL identity must not consume a grant");
            var loan = fixture.redeem(region, grant).join();
            assertThrows(SecurityException.class, loan::readScalar);
            assertThrows(SecurityException.class, () -> loan.writeScalar(5));
            assertThrows(SecurityException.class, loan::close);
            assertEquals(1, region.activeReaders(),
                    "unauthenticated close must not release live physical loan");
            assertEquals(0L, fixture.read(loan));
            fixture.release(loan);
        }
    }

    @Test
    void immutableLoanCannotMutateProtectedStateEvenUnderRightControl() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var loan = fixture.redeem(region, fixture.issue("read")).join();
            assertEquals(0L, fixture.read(loan));
            assertThrows(SecurityException.class,
                    () -> fixture.write(loan, 22L));
            assertEquals(0L, fixture.read(loan));
            fixture.release(loan);
        }
    }

    @Test
    void writerAndOwnerShareOneProtectedPrimitiveStorageWithoutRawAliases()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            sendAndDrain(fixture.worker, "scalar-owner-increment");
            var writer = fixture.redeem(region, fixture.issue("write")).join();
            assertEquals(1L, fixture.read(writer));
            fixture.write(writer, 41L);
            assertEquals(41L, fixture.read(writer));
            fixture.release(writer);
            sendAndDrain(fixture.worker, "scalar-owner-read");
            assertEquals(41L, fixture.ownerScalarObserved.get());
            sendAndDrain(fixture.worker, "scalar-owner-increment");
            var reader = fixture.redeem(region, fixture.issue("read")).join();
            assertEquals(42L, fixture.read(reader));
            fixture.release(reader);
        }
    }

    @Test
    void otherSupervisorControlCannotUseOrReleaseExistingBorrow() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var loan = fixture.redeem(region, fixture.issue("read")).join();
            assertThrows(SecurityException.class,
                    () -> fixture.asControl(fixture.foreign, loan::readScalar));
            assertThrows(SecurityException.class,
                    () -> fixture.asControl(fixture.foreign,
                            () -> { loan.close(); return null; }));
            assertEquals(1, region.activeReaders());
            fixture.release(loan);
        }
    }

    @Test
    void releasedLoanCannotResumeInNewAuthenticatedControlTurn() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var loan = fixture.redeem(region, fixture.issue("write")).join();
            fixture.write(loan, 31L);
            fixture.release(loan);
            assertThrows(SecurityException.class,
                    () -> fixture.asControl(fixture.supervisor, loan::readScalar));
            assertThrows(SecurityException.class,
                    () -> fixture.write(loan, 32L));
            var fresh = fixture.redeem(region, fixture.issue("read")).join();
            assertEquals(31L, fixture.read(fresh));
            fixture.release(fresh);
        }
    }

    @Test
    void nestedSupervisorControlDispatchCannotMintSecondControlPrincipal()
            throws Exception {
        try (var fixture = new Fixture()) {
            fixture.asControl(fixture.supervisor, () -> {
                assertThrows(SecurityException.class,
                        () -> fixture.runtime.dispatchSupervisorStateControl(
                                fixture.foreign,
                                () -> fail("nested CONTROL must not execute")));
                return null;
            });
        }
    }

    @Test
    void synchronousControlDispatcherCannotForgePhysicalCarrierAdmission()
            throws Exception {
        ActorRuntime.ControlDispatcher illegalInline = operation -> {
            var result = new OresFuture<Void>();
            try {
                operation.run();
                result.completeFromRuntime(null);
            } catch (Throwable denied) {
                result.failFromRuntime(denied);
            }
            return result;
        };
        try (var runtime = new ActorRuntime(
                IsolatePolicy.developer(), ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(), illegalInline)) {
            var supervisor = runtime.createSupervisor();
            var accepted = runtime.dispatchSupervisorStateControl(
                    supervisor, () -> fail("CONTROL must not execute inline"));
            assertTrue(accepted.isCompletedExceptionally());
        }
    }

    @Test
    void stoppedActorCannotReadOrWriteThroughStillPhysicallyPinnedGrant()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var writer = fixture.redeem(region, fixture.issue("write")).join();
            fixture.write(writer, 55L);
            assertTrue(fixture.worker.cancel(),
                    "structured cancellation must seal without awaiting live physical loan");
            assertFalse(fixture.worker.done().isDone());
            assertThrows(SecurityException.class, () -> fixture.read(writer));
            assertThrows(SecurityException.class, () -> fixture.write(writer, 56L));
            assertFalse(region.physicallyRetired());
            fixture.release(writer);
            assertTrue(fixture.worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(region.physicallyRetired());
        }
    }


    @Test
    void stopSealsBeforePausedActorMutationCanCommit() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            fixture.worker.send("paused-owner-update");
            assertTrue(fixture.ownerUpdateEntered.await(5, TimeUnit.SECONDS),
                    "actor must have acquired owner writer and reached paused callback");
            try {
                assertTrue(fixture.worker.cancel(),
                        "structured STOP must seal the state region without waiting for callback");
                assertFalse(region.physicallyRetired(),
                        "active actor lexical writer must pin physical memory");
                assertFalse(fixture.worker.done().isDone());
            } finally {
                fixture.allowOwnerUpdate.countDown();
            }
            assertTrue(fixture.worker.awaitTermination(5, TimeUnit.SECONDS));
            var revoked = assertInstanceOf(
                    IllegalStateException.class, fixture.denied.get(),
                    "the actor write must fail its post-STOP state admission");
            assertTrue(revoked.getMessage().contains("revoked"),
                    "a timeout or interruption is not proof of STOP write fencing");
            assertTrue(region.physicallyRetired());
        }
    }


    @Test
    void scopedWriteReleasesBeforeNextControlTurnAndOwnerReadsResult() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            fixture.scopedWrite(region, fixture.issue("write"), scope -> {
                assertEquals(1, region.activeWriters());
                assertEquals(0L, scope.read());
                scope.write(101L);
                assertEquals(101L, scope.read());
            });
            assertEquals(0, region.activeWriters(),
                    "physical writer must unwind before CONTROL dispatch resolves");
            sendAndDrain(fixture.worker, "scalar-owner-read");
            assertEquals(101L, fixture.ownerScalarObserved.get());

            var result = new java.util.concurrent.atomic.AtomicLong();
            fixture.scopedRead(region, fixture.issue("read"), scope ->
                    result.set(scope.read()));
            assertEquals(101L, result.get());
            assertEquals(0, region.activeReaders());
        }
    }

    @Test
    void escapedScopedViewIsInvalidEvenInLaterAuthenticatedControlTurn()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var escaped = new AtomicReference<ActorStateSupervisorLoans.ScopedScalar>();
            fixture.scopedRead(region, fixture.issue("read"), escaped::set);
            assertNotNull(escaped.get());
            assertEquals(0, region.activeReaders());
            assertThrows(SecurityException.class,
                    () -> fixture.asControl(
                            fixture.supervisor, escaped.get()::read));
            assertThrows(SecurityException.class,
                    () -> fixture.asControl(
                            fixture.supervisor,
                            () -> { escaped.get().write(1L); return null; }));
        }
    }

    @Test
    void scopeCannotMoveToAnotherThreadWhileOriginalControlIsActive()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var rejected = new AtomicReference<Throwable>();
            fixture.scopedRead(region, fixture.issue("read"), scope -> {
                var child = Thread.ofPlatform().start(() -> {
                    try {
                        scope.read();
                    } catch (Throwable error) {
                        rejected.set(error);
                    }
                });
                try {
                    child.join(3000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(child.isAlive());
                assertEquals(1, region.activeReaders(),
                        "failed cross-thread access must not release CONTROL owner");
            });
            assertInstanceOf(SecurityException.class, rejected.get());
            assertEquals(0, region.activeReaders());
        }
    }

    @Test
    void readScopeCannotWriteAndMustStillReleaseOnCallbackFailure()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var denied = assertThrows(SecurityException.class,
                    () -> fixture.scopedRead(region, fixture.issue("read"),
                            scope -> scope.write(123L)));
            assertTrue(denied.getMessage().contains("exclusive"));
            assertEquals(0, region.activeReaders());
            fixture.scopedRead(region, fixture.issue("read"),
                    scope -> assertEquals(0L, scope.read()));
        }
    }

    @Test
    void callbackFailureAlwaysReleasesExclusiveScopedWriter() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var fault = assertThrows(IllegalStateException.class,
                    () -> fixture.scopedWrite(region, fixture.issue("write"), scope -> {
                        scope.write(333L);
                        throw new IllegalStateException("user callback injected failure");
                    }));
            assertEquals("user callback injected failure", fault.getMessage());
            assertEquals(0, region.activeWriters());
            var seen = new java.util.concurrent.atomic.AtomicLong();
            fixture.scopedRead(region, fixture.issue("read"), scope ->
                    seen.set(scope.read()));
            assertEquals(333L, seen.get());
        }
    }

    @Test
    void mismatchedScopedBorrowModeDoesNotConsumeValidGrant() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var grant = fixture.issue("read");
            assertThrows(SecurityException.class,
                    () -> fixture.scopedWrite(region, grant,
                            scope -> fail("read grant must not become writer")));
            assertEquals(1, region.outstandingGrantCount());
            fixture.scopedRead(region, grant, scope -> assertEquals(0L, scope.read()));
            assertEquals(0, region.outstandingGrantCount());
        }
    }

    @Test
    void contendedScopeCancelsWaiterInsteadOfParkingControlCarrier()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var writer = fixture.redeem(region, fixture.issue("write")).join();
            var blocked = fixture.issue("read");
            assertThrows(IllegalStateException.class,
                    () -> fixture.scopedRead(region, blocked,
                            scope -> fail("queued reader must never execute")));
            assertEquals(1, region.activeWriters());
            assertEquals(0, region.pendingCount(),
                    "failed scoped acquisition must cancel the queued read");
            fixture.release(writer);
            fixture.scopedRead(region, fixture.issue("read"),
                    scope -> assertEquals(0L, scope.read()));
        }
    }

    @Test
    void stopInsideScopedWriterRevokesAccessButPhysicalReleaseRunsInFinally()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            fixture.scopedWrite(region, fixture.issue("write"), scope -> {
                scope.write(80L);
                assertTrue(fixture.worker.cancel());
                assertFalse(fixture.worker.done().isDone());
                assertThrows(SecurityException.class, scope::read);
                assertThrows(SecurityException.class, () -> scope.write(81L));
                assertFalse(region.physicallyRetired());
            });
            assertEquals(0, region.activeWriters());
            assertTrue(fixture.worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(region.physicallyRetired());
        }
    }


    @Test
    void sameSupervisorButDifferentSimultaneousControlTurnCannotBorrowScopedView()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var rejected = new AtomicReference<Throwable>();
            fixture.scopedRead(region, fixture.issue("read"), scope -> {
                var anotherHost = Thread.ofPlatform().start(() -> {
                    try {
                        fixture.asControl(fixture.supervisor, scope::read);
                    } catch (Throwable failure) {
                        rejected.set(failure);
                    }
                });
                try {
                    anotherHost.join(4000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(anotherHost.isAlive());
                assertEquals(1, region.activeReaders());
            });
            assertInstanceOf(SecurityException.class, rejected.get(),
                    "same supervisor identity must not impersonate original CONTROL turn");
            assertEquals(0, region.activeReaders());
        }
    }

    @Test
    void rawFutureLoanAdapterRemainsPackagePrivateNotGuestPublicApi()
            throws Exception {
        assertFalse(java.lang.reflect.Modifier.isPublic(
                        ActorStateSupervisorLoans.Loan.class.getModifiers()),
                "unscoped Loan type must never be accessible to guest or external Java callers");
        var raw = ActorStateSupervisorLoans.class.getDeclaredMethod(
                "redeem",
                ActorStateSupervisorLoans.Grant.class,
                ActorRuntime.ActorSupervisor.class);
        assertFalse(java.lang.reflect.Modifier.isPublic(raw.getModifiers()),
                "guest or external host callers must not get unscoped loan Futures");
        for (var name : java.util.List.of("withSupervisorRead", "withSupervisorWrite")) {
            var method = ActorStateSupervisorLoans.class.getDeclaredMethod(
                    name,
                    ActorStateSupervisorLoans.Grant.class,
                    ActorRuntime.ActorSupervisor.class,
                    java.util.function.Consumer.class);
            assertFalse(java.lang.reflect.Modifier.isPublic(method.getModifiers()),
                    "compiler-only scoped bridge must remain runtime-package-local");
        }
    }


    @Test
    void typedLongFieldIsCanonicalOwnerAndSupervisorSharedStorage() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var field = fixture.typedBalance.get();
            assertNotNull(field);
            sendAndDrain(fixture.worker, "typed-owner-read");
            assertEquals(5L, fixture.ownerScalarObserved.get());

            fixture.scopedRead(region, fixture.issue("read"),
                    scope -> assertEquals(5L, scope.read(field)));
            fixture.scopedWrite(region, fixture.issue("write"), scope -> {
                assertEquals(5L, scope.read(field));
                scope.write(field, 23L);
                assertEquals(23L, scope.read(field));
            });
            sendAndDrain(fixture.worker, "typed-owner-read");
            assertEquals(23L, fixture.ownerScalarObserved.get());
            sendAndDrain(fixture.worker, "typed-owner-increment");
            sendAndDrain(fixture.worker, "typed-owner-read");
            assertEquals(24L, fixture.ownerScalarObserved.get());
            fixture.scopedRead(region, fixture.issue("read"),
                    scope -> assertEquals(24L, scope.read(field)));
            assertEquals(0, region.activeReaders());
            assertEquals(0, region.activeWriters());
        }
    }

    @Test
    void typedLongFieldCannotBeReadWithoutItsActualPhysicalPrincipal() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var field = fixture.typedBalance.get();
            assertThrows(SecurityException.class, () -> region.readOwnerLongField(field));
            assertThrows(SecurityException.class,
                    () -> region.updateOwnerLongField(field, value -> value + 1));
            assertThrows(SecurityException.class,
                    () -> region.declareOwnerLongField("outside", 1L));
        }
    }

    @Test
    void typedLongFieldDoesNotPermitSharedReaderToWrite() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var field = fixture.typedBalance.get();
            fixture.scopedRead(region, fixture.issue("read"), scope -> {
                assertEquals(5L, scope.read(field));
                assertThrows(SecurityException.class, () -> scope.write(field, 77L));
            });
            fixture.scopedRead(region, fixture.issue("read"), scope ->
                    assertEquals(5L, scope.read(field)));
        }
    }

    @Test
    void typedLongFieldRejectsForeignRegionHandleEvenIfSupervisorIsAuthenticated()
            throws Exception {
        try (var first = new Fixture(); var second = new Fixture()) {
            var a = first.create();
            var b = second.create();
            var foreign = b == null ? null : second.typedBalance.get();
            assertNotNull(foreign);
            first.scopedWrite(a, first.issue("write"), scope -> {
                assertThrows(SecurityException.class, () -> scope.read(foreign));
                assertThrows(SecurityException.class, () -> scope.write(foreign, 81L));
                assertEquals(5L, scope.read(first.typedBalance.get()));
            });
        }
    }

    @Test
    void typedLongFieldScopedViewExpiresBeforeNextControlTurn() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var field = fixture.typedBalance.get();
            var escaped = new AtomicReference<ActorStateSupervisorLoans.ScopedScalar>();
            fixture.scopedRead(region, fixture.issue("read"), escaped::set);
            assertThrows(SecurityException.class,
                    () -> fixture.asControl(fixture.supervisor,
                            () -> escaped.get().read(field)));
            assertThrows(SecurityException.class,
                    () -> fixture.asControl(fixture.supervisor, () -> {
                        escaped.get().write(field, 8L);
                        return null;
                    }));
        }
    }

    @Test
    void typedOwnerWriteCannotCommitAcrossStopSeal() throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            fixture.worker.send("typed-owner-paused");
            assertTrue(fixture.ownerUpdateEntered.await(5, TimeUnit.SECONDS),
                    "owner must be inside actual mutation callback");
            assertTrue(fixture.worker.cancel());
            fixture.allowOwnerUpdate.countDown();
            assertTrue(fixture.worker.awaitTermination(5, TimeUnit.SECONDS));
            assertNotNull(fixture.denied.get(),
                    "the stale write must fail instead of committing after STOP");
            assertTrue(region.physicallyRetired());
        }
    }


    @Test
    void typedFieldRegistrationWaitsForExclusivePhysicalRegionAdmission()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            var writer = fixture.redeem(region, fixture.issue("write")).join();
            sendAndDrain(fixture.worker, "typed-owner-add-field");
            assertInstanceOf(IllegalStateException.class, fixture.denied.get());
            assertNull(fixture.typedLate.get(),
                    "a contended owner cannot publish a new mutable field");
            fixture.release(writer);
            fixture.denied.set(null);
            sendAndDrain(fixture.worker, "typed-owner-add-field");
            assertNull(fixture.denied.get());
            var late = fixture.typedLate.get();
            assertNotNull(late);
            fixture.scopedRead(region, fixture.issue("read"),
                    scope -> assertEquals(71L, scope.read(late)));
        }
    }

    @Test
    void typedFieldNamesAreOneUseAndCannotReplaceExistingGuestBacking()
            throws Exception {
        try (var fixture = new Fixture()) {
            var region = fixture.create();
            sendAndDrain(fixture.worker, "typed-owner-duplicate-field");
            assertInstanceOf(IllegalArgumentException.class, fixture.denied.get());
            fixture.scopedRead(region, fixture.issue("read"),
                    scope -> assertEquals(5L,
                            scope.read(fixture.typedBalance.get())));
        }
    }

}
