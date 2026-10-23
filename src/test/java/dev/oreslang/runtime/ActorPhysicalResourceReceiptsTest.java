package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Host-driver and actor STOP proofs; no simulated Future cancellation receipt. */
@Timeout(20)
final class ActorPhysicalResourceReceiptsTest {
    private static final ActorPhysicalResourceReceipts.Kind TCP =
            ActorPhysicalResourceReceipts.Kind.TCP_HANDLE;
    private static final ActorPhysicalResourceReceipts.Kind FFI =
            ActorPhysicalResourceReceipts.Kind.FFI_LOAN;
    private static final ActorPhysicalResourceReceipts.Kind MUTEX =
            ActorPhysicalResourceReceipts.Kind.MUTEX_HANDOFF;

    @Test
    void registryRefusesWrongPhaseAndDuplicateDriverSettlement() {
        AtomicInteger notifications = new AtomicInteger();
        var registry = new ActorPhysicalResourceReceipts(notifications::incrementAndGet);
        var ticket = registry.register(TCP, false);
        assertEquals(1, registry.pending());
        registry.sealOnStop();
        assertThrows(IllegalStateException.class,
                () -> registry.register(TCP, false));
        assertEquals(1, registry.pending());
        assertTrue(ticket.completeFromDriver());
        assertFalse(ticket.completeFromDriver());
        assertEquals(0, registry.pending());
        assertEquals(1, notifications.get());
        registry.finishAfterProof();
        assertThrows(IllegalStateException.class,
                () -> registry.register(FFI, true));
        assertNull(registry.quarantineCause());
    }

    @Test
    void failedDriverSettlementPermanentlyQuarantinesAfterReclaimAttempt() {
        var registry = new ActorPhysicalResourceReceipts(() -> { });
        var ticket = registry.register(FFI, false);
        registry.sealOnStop();
        IllegalStateException physical = new IllegalStateException("native close rejected");
        assertTrue(ticket.failFromDriver(physical));
        assertFalse(ticket.completeFromDriver());
        assertEquals(0, registry.pending());
        assertSame(physical, registry.quarantineCause().getCause());
        assertThrows(IllegalStateException.class, registry::finishAfterProof);
        assertThrows(IllegalStateException.class,
                () -> registry.register(FFI, true));
    }

    @Test
    void stopWaitsForLastPhysicalReceiptRatherThanLastCancelledFuture()
            throws Exception {
        AtomicReference<ActorPhysicalResourceReceipts.Receipt> mutex = new AtomicReference<>();
        AtomicReference<ActorPhysicalResourceReceipts.Receipt> tcp = new AtomicReference<>();
        AtomicReference<ActorPhysicalResourceReceipts.Receipt> ffi = new AtomicReference<>();
        CountDownLatch admitted = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                mutex.set(context.runtime().registerCurrentActorPhysicalReceipt(MUTEX));
                tcp.set(context.runtime().registerCurrentActorPhysicalReceipt(TCP));
                ffi.set(context.runtime().registerCurrentActorPhysicalReceipt(FFI));
                admitted.countDown();
            });
            actor.send("open");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertFalse(actor.done().isDone(), "cancellation is not release evidence");
            assertTrue(mutex.get().completeFromDriver());
            assertTrue(tcp.get().completeFromDriver());
            assertFalse(actor.done().isDone(), "FFI loan remains physically live");
            assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS));
            assertTrue(ffi.get().completeFromDriver());
            assertFalse(ffi.get().completeFromDriver(), "duplicate driver receipt");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            ExecutionException cancelled = assertThrows(ExecutionException.class,
                    () -> actor.done().get(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    cancelled.getCause());
            assertThrows(IllegalStateException.class,
                    () -> runtime.registerCurrentActorPhysicalReceipt(TCP));
        }
    }

    @Test
    void cancellationCleanupCanIssueAndJoinNewPhysicalCloseLoan() throws Exception {
        AtomicReference<ActorPhysicalResourceReceipts.Receipt> close =
                new AtomicReference<>();
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch minted = new CountDownLatch(1);
        AtomicInteger unwinds = new AtomicInteger();
        OresFuture<Void> never = new OresFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            int state;
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                if (state++ == 0) {
                                    suspended.countDown();
                                    return OresScheduler.await(never);
                                }
                                assertTrue(OresScheduler.cancellationCleanupTurn());
                                assertInstanceOf(CancellationException.class, resume.failure());
                                close.set(context.runtime().registerCurrentActorPhysicalReceipt(
                                        ActorPhysicalResourceReceipts.Kind.RESOURCE_CLOSE));
                                unwinds.incrementAndGet();
                                minted.countDown();
                                return OresScheduler.done(null);
                            }
                        });
            });
            actor.send("start");
            assertTrue(suspended.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(minted.await(3, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone(),
                    "SourceTask may be complete but physical close receipt remains outstanding");
            assertTrue(close.get().completeFromDriver());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(1, unwinds.get(), "cleanup only once");
            assertFalse(never.isCancelled(), "borrowed Future must remain unrevoked");
        }
    }

    @Test
    void childNativeLoanPreventsParentFromPublishingStructuredDone()
            throws Exception {
        AtomicReference<ActorRuntime.ActorRef<String>> child = new AtomicReference<>();
        AtomicReference<ActorPhysicalResourceReceipts.Receipt> childLoan =
                new AtomicReference<>();
        CountDownLatch acquired = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                var descendant = context.runtime().<String>spawnShared(() ->
                        (childMessage, childContext) -> {
                            childLoan.set(childContext.runtime()
                                    .registerCurrentActorPhysicalReceipt(FFI));
                            acquired.countDown();
                        });
                child.set(descendant);
                descendant.send("take-native-loan");
            });
            parent.send("spawn");
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertTrue(parent.cancel());
            assertFalse(parent.done().isDone(),
                    "supervisor parent must await its child's physical FFI loan");
            assertFalse(child.get().done().isDone());
            assertTrue(childLoan.get().completeFromDriver());
            assertTrue(child.get().awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void physicalReleaseFailureQuarantinesArenaAndNeverPublishesDone()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        AtomicReference<ActorPhysicalResourceReceipts.Receipt> receipt =
                new AtomicReference<>();
        CountDownLatch acquired = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                receipt.set(context.runtime().registerCurrentActorPhysicalReceipt(FFI));
                acquired.countDown();
            });
            actor.send("loan");
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            Throwable releaseFailure = new IllegalStateException("native driver failed to close");
            assertTrue(receipt.get().failFromDriver(releaseFailure));
            assertFalse(receipt.get().failFromDriver(releaseFailure));
            assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                    "failed physical release must quarantine private arena");
            assertFalse(actor.done().isDone(),
                    "quarantined child must not advertise clean termination");
        } finally {
            assertThrows(IllegalStateException.class, runtime::close,
                    "runtime close must report quarantined actor rather than clear registry");
        }
    }
}
