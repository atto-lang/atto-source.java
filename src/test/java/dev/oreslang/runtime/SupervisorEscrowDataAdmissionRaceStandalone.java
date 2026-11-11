package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exact source-only Java 21 admission/fence stress. Does not simulate compiled
 * AttoLang guest messages, the ActorRuntime mailbox, or native reclamation.
 */
final class SupervisorEscrowDataAdmissionRaceStandalone {
    private static int checks;

    private static void require(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void reject(Runnable action, String reason) {
        checks++;
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        var ledger = new SupervisorFailureEscrowV1();
        ledger.registerRoot(1, 1);
        ledger.registerChild(2, 1, 1);
        ledger.registerChild(3, 2, 1);
        ledger.registerChild(4, 1, 1);

        var admittedBeforeFence = ledger.beginDataAdmission(3, 1);
        require(admittedBeforeFence != null, "pre-fence actor admission denied");
        require(ledger.dataInFlightInSubtree(2) == 1, "pre-fence token not tracked");
        ledger.fail(2);
        require(ledger.beginDataAdmission(3, 1) == null, "descendant admitted after ancestor failure");
        require(ledger.beginDataAdmission(2, 1) == null, "failed supervisor admitted DATA");
        require(ledger.dataInFlightInSubtree(2) == 1, "fence erased an existing admission");
        var sibling = ledger.beginDataAdmission(4, 1);
        require(sibling != null, "unrelated sibling fenced");
        ledger.finishDataAdmission(admittedBeforeFence, true);
        ledger.finishDataAdmission(sibling, false);
        require(ledger.dataInFlightInSubtree(2) == 0, "admission leaked after finishing");
        require(ledger.dataAdmissionStatus(3).committed() == 1, "pre-fence commit unaccounted");
        require(ledger.dataAdmissionStatus(4).aborted() == 1, "rollback unaccounted");
        reject(() -> ledger.finishDataAdmission(admittedBeforeFence, false),
                "replayed admission finished twice");

        var foreign = new SupervisorFailureEscrowV1();
        foreign.registerRoot(1, 1);
        foreign.registerChild(2, 1, 1);
        reject(() -> foreign.finishDataAdmission(admittedBeforeFence, false),
                "permit from a foreign runtime accepted");

        // Force the otherwise astronomically rare signed counter boundary.
        // A broken finish() must not drop its one-use outstanding permit and
        // thereby authorize supervisor restart with unaccounted DATA.
        var overflow = new SupervisorFailureEscrowV1();
        overflow.registerRoot(1, 1);
        overflow.registerChild(2, 1, 1);
        var unsettled = overflow.beginDataAdmission(2, 1);
        require(unsettled != null, "overflow fixture could not reserve DATA");
        var ownerField = SupervisorFailureEscrowV1.DataAdmission.class.getDeclaredField("owner");
        ownerField.setAccessible(true);
        var owner = ownerField.get(unsettled);
        var committedField = owner.getClass().getDeclaredField("dataCommitted");
        committedField.setAccessible(true);
        committedField.setLong(owner, Long.MAX_VALUE);
        boolean overflowCaught = false;
        try {
            overflow.finishDataAdmission(unsettled, true);
        } catch (ArithmeticException expected) {
            overflowCaught = true;
        }
        require(overflowCaught, "committed counter overflow was silently accepted");
        require(overflow.dataInFlightInSubtree(2) == 1,
                "overflow incorrectly released the outstanding DATA permit");
        require(overflow.dataAdmissionStatus(2).committed() == Long.MAX_VALUE,
                "overflow silently wrapped the committed counter");
        reject(() -> overflow.beginDataAdmission(2, 1),
                "new DATA permit admitted without counter headroom");
        committedField.setLong(owner, Long.MAX_VALUE - 1);
        overflow.finishDataAdmission(unsettled, true);
        require(overflow.dataInFlightInSubtree(2) == 0
                        && overflow.dataAdmissionStatus(2).committed() == Long.MAX_VALUE,
                "restored permitted settlement must complete exactly once");
        reject(() -> overflow.beginDataAdmission(2, 1),
                "exhausted counter permitted more DATA");

        // Atomic inherited child publication: the pure ledger may revoke
        // ONLY a never-published child with no terminal, DATA or descendants.
        var publication = new SupervisorFailureEscrowV1();
        publication.registerRoot(1, 1);
        publication.registerChild(2, 1, 1);
        publication.registerChild(3, 2, 1);
        publication.rollbackUnpublishedChild(3, 2, 1);
        require(!publication.snapshot().containsKey(3L),
                "aborted child reservation remained visible");
        require(!publication.mayAdmit(3, 1),
                "an aborted child acquired stale DATA authority");
        publication.registerChild(4, 2, 1);
        publication.registerChild(5, 4, 1);
        reject(() -> publication.rollbackUnpublishedChild(4, 2, 1),
                "registered descendant did not prevent parent rollback");
        require(publication.snapshot().containsKey(4L)
                        && publication.snapshot().containsKey(5L),
                "failed parent rollback silently erased a registered descendant");
        publication.rollbackUnpublishedChild(5, 4, 1);
        publication.rollbackUnpublishedChild(4, 2, 1);
        require(!publication.snapshot().containsKey(4L)
                        && !publication.snapshot().containsKey(5L),
                "bottom-up unpublished child rollback left CONTROL nodes");
        publication.registerChild(6, 2, 1);
        publication.fail(6);
        reject(() -> publication.rollbackUnpublishedChild(6, 2, 1),
                "failed actor terminal CONTROL notice erased by rollback");
        require(publication.pendingCount() == 1,
                "failed actor terminal CONTROL notice not retained");

        final int rounds = 16;
        final int workers = 8;
        final int perWorker = 512;
        final AtomicInteger attempts = new AtomicInteger();
        for (int round = 0; round < rounds; round++) {
            var concurrent = new SupervisorFailureEscrowV1();
            concurrent.registerRoot(1, 1);
            concurrent.registerChild(2, 1, 1);
            concurrent.registerChild(3, 2, 1);
            concurrent.registerChild(4, 1, 1);
            var fenceReturned = new AtomicBoolean();
            var started = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(workers + 1);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < workers; i++) {
                    final int worker = i;
                    tasks.add(pool.submit(() -> {
                        started.await();
                        for (int attempt = 0; attempt < perWorker; attempt++) {
                            boolean alreadyFenced = fenceReturned.get();
                            var ticket = concurrent.beginDataAdmission(3, 1);
                            if (alreadyFenced && ticket != null) {
                                throw new AssertionError("post-fence DATA permit issued");
                            }
                            if (ticket != null) {
                                // Exercise both successful admission and failure rollback.
                                concurrent.finishDataAdmission(ticket, (attempt + worker) % 2 == 0);
                            }
                            attempts.incrementAndGet();
                        }
                        return null;
                    }));
                }
                tasks.add(pool.submit(() -> {
                    started.await();
                    concurrent.fail(2);
                    fenceReturned.set(true);
                    return null;
                }));
                started.countDown();
                for (Future<?> task : tasks) task.get(15, TimeUnit.SECONDS);
                require(concurrent.dataInFlightInSubtree(2) == 0, "unsettled permit in round " + round);
                require(concurrent.beginDataAdmission(3, 1) == null,
                        "post-fence actor admission after quiescence");
                var goodSibling = concurrent.beginDataAdmission(4, 1);
                require(goodSibling != null, "unrelated sibling blocked in round " + round);
                concurrent.finishDataAdmission(goodSibling, true);
                require(concurrent.dataAdmissionStatus(4).inFlight() == 0,
                        "sibling permit leak");
            } finally {
                pool.shutdownNow();
            }
        }
        require(attempts.get() == rounds * workers * perWorker,
                "stress tasks stopped before all attempts");
        System.out.println("PASS SupervisorEscrowDataAdmissionRaceStandalone attempts="
                + attempts.get() + " checks=" + checks);
    }
}
