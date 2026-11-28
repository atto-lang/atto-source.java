package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dependency-free, exact-source JDK 21 host-substrate smoke.
 * This does NOT replace production guest compilation, JUnit, native arenas
 * or any AOT image / five-backend conformance test.
 */
public final class ProtectedActorStandaloneSmoke {
    private static int cases;

    private ProtectedActorStandaloneSmoke() { }

    private static void check(boolean condition, String explanation) {
        if (!condition) throw new AssertionError(explanation);
    }

    private static void rejects(Runnable operation, String explanation) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(explanation);
    }

    private static void scalarLeaseAndOverflow() {
        var container = new ScalarStateContainer(Long.MAX_VALUE, 2);
        check(container.tryRead().orElseThrow() == Long.MAX_VALUE, "scalar read");
        try {
            container.tryAddChecked(1);
            throw new AssertionError("checked overflow not rejected");
        } catch (ArithmeticException expected) {
            check(container.tryRead().orElseThrow() == Long.MAX_VALUE,
                    "overflow must not commit mutation");
        }
        container.close();
        check(container.readyForReclamation(), "closed scalar leases quiescent");
        check(container.tryRead().isEmpty(), "closed scalar admits no read");
        cases++;
    }

    private static void closeRevokesOutstandingGateLoans() {
        var gate = new StateContainerAccessGate(2);
        var first = gate.tryRead();
        var second = gate.tryRead();
        check(first != null && second != null, "two reader leases");
        check(gate.tryWrite() == null, "writer excluded by readers");
        gate.close();
        check(!gate.leasesQuiescentAfterClose(), "cannot reclaim during read");
        rejects(() -> first.requireActive(StateContainerAccessGate.Mode.READ),
                "closed reader must be revoked");
        first.close();
        check(!gate.leasesQuiescentAfterClose(), "second reader must remain");
        second.close();
        check(gate.leasesQuiescentAfterClose(), "physical release required");
        cases++;
    }

    private static void fencedIncarnationAndCredits() {
        var registry = new VirtualActorActivationRegistry("local", 1, 1);
        var logical = registry.resolve("Counter", "caf\u00e9");
        var normalized = registry.resolve("Counter", "cafe\u0301");
        check(logical.identity().equals(normalized.identity()), "NFC identity");
        var first = registry.activate(logical);
        check(first.initializer(), "first activation initializes");
        registry.publish(first);
        var ticket = registry.tryAdmit(first);
        check(ticket != null, "ticket admission");
        check(registry.tryAdmit(first) == null, "bounded capacity");
        rejects(() -> registry.deactivate(first), "active credit blocks deactivation");
        ticket.settle(VirtualActorActivationRegistry.Disposition.PROCESSED);
        registry.deactivate(first);
        check(!first.active(), "stale activation fenced");
        var second = registry.activate(logical);
        check(second.initializer() && second.incarnation() > first.incarnation(),
                "generation advances");
        registry.publish(second);
        registry.deactivate(second);
        registry.close();
        cases++;
    }

    private static void acceptedFifoAndBackpressure() {
        var history = new ArrayList<Long>();
        var disposed = new AtomicInteger();
        try (var runtime = new LocalVirtualActorI64("app", "Queue", 1, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) { history.add(value); }
                    @Override public void close() { disposed.incrementAndGet(); }
                }, notice -> { throw new AssertionError("unexpected CONTROL"); })) {
            var ref = runtime.resolve("one");
            var a = runtime.try_send(ref, 5);
            check(a.admitted(), "first admitted");
            check(runtime.try_send(ref, 9).kind() ==
                    LocalVirtualActorI64.AdmissionKind.BACKPRESSURE, "bounded rejection");
            check(runtime.run_one(ref), "first turn executes");
            check(a.completion().join() == LocalVirtualActorI64.Outcome.PROCESSED,
                    "first receipt");
            check(!runtime.run_one(ref), "no ghost message");
            var b = runtime.try_send(ref, 7);
            check(b.admitted() && runtime.run_one(ref), "next turn executes");
            check(b.completion().join() == LocalVirtualActorI64.Outcome.PROCESSED,
                    "second receipt");
            check(history.equals(List.of(5L, 7L)), "strict FIFO and rollback");
        }
        check(disposed.get() == 1, "shutdown disposes state exactly once");
        cases++;
    }

    private static void completionAllowsReentrantAdmit() {
        try (var runtime = new LocalVirtualActorI64("app", "Callback", 1, 1,
                ignored -> ignoredMessage -> { },
                notice -> { throw new AssertionError("unexpected CONTROL"); })) {
            var ref = runtime.resolve("one");
            var first = runtime.try_send(ref, 1);
            var next = new AtomicReference<LocalVirtualActorI64.SendResult>();
            first.completion().thenRun(() -> next.set(runtime.try_send(ref, 2)));
            check(runtime.run_one(ref), "complete first");
            check(next.get() != null && next.get().admitted(),
                    "completion callback sees settled credit");
            check(runtime.run_one(ref), "complete reentrant message");
            check(next.get().completion().join() == LocalVirtualActorI64.Outcome.PROCESSED,
                    "reentrant receipt");
        }
        cases++;
    }

    private static void missingControlPreservesAcceptedTurn() {
        var disposed = new AtomicInteger();
        var runtime = new LocalVirtualActorI64("app", "Worker", 1, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) {
                        throw new IllegalStateException("guest panic");
                    }
                    @Override public void close() { disposed.incrementAndGet(); }
                }, notice -> { throw new IllegalStateException("CONTROL unavailable"); });
        var ref = runtime.resolve("one");
        var ticket = runtime.try_send(ref, 4);
        check(ticket.admitted(), "accepted panic message");
        rejects(() -> runtime.run_one(ref), "failed CONTROL refuses success");
        check(!ticket.completion().isDone(), "failed CONTROL retains completion");
        rejects(runtime::close, "outstanding credit must block shutdown");
        check(disposed.get() == 0, "cannot dispose with unnotified credit");
        cases++;
    }

    private static void physicalHandlerBlocksTeardown() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var disposed = new AtomicInteger();
        var workerFailure = new AtomicReference<Throwable>();
        var runtime = new LocalVirtualActorI64("app", "Worker", 1, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) throws Exception {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("worker timeout");
                    }
                    @Override public void close() { disposed.incrementAndGet(); }
                }, notice -> { throw new AssertionError("unexpected CONTROL"); });
        var ref = runtime.resolve("one");
        var accepted = runtime.try_send(ref, 1);
        Thread worker = new Thread(() -> {
            try {
                check(runtime.run_one(ref), "physical turn runs");
            } catch (Throwable problem) {
                workerFailure.set(problem);
            }
        }, "protected-actor-smoke-worker");
        worker.start();
        try {
            check(entered.await(5, TimeUnit.SECONDS), "worker started");
            rejects(runtime::close, "shutdown must wait for guest to retire");
            check(disposed.get() == 0, "no early disposal");
        } finally {
            release.countDown();
            worker.join(5000);
        }
        check(!worker.isAlive() && workerFailure.get() == null, "worker exits cleanly");
        check(accepted.completion().join() == LocalVirtualActorI64.Outcome.PROCESSED,
                "worker settles");
        runtime.close();
        check(disposed.get() == 1, "post-quiescence cleanup exactly once");
        cases++;
    }

    private static void foreignRuntimeRefNeverCrossesDirectory() {
        try (var first = new LocalVirtualActorI64("tenant-a", "Worker", 1, 1,
                     ignored -> message -> { }, notice -> { throw new AssertionError("CONTROL"); });
             var second = new LocalVirtualActorI64("tenant-b", "Worker", 1, 1,
                     ignored -> message -> { }, notice -> { throw new AssertionError("CONTROL"); })) {
            var ref = first.resolve("one");
            try {
                second.try_send(ref, 1);
                throw new AssertionError("foreign directory accepted a reference");
            } catch (IllegalArgumentException expected) {
                // A handle belongs to exactly one local registry and directory.
            }
            var accepted = first.try_send(ref, 2);
            check(accepted.admitted() && first.run_one(ref), "owning directory still works");
            check(accepted.completion().join() == LocalVirtualActorI64.Outcome.PROCESSED,
                    "owning directory receipt");
        }
        cases++;
    }

    private static void concurrentBackpressureAndSettlement() throws Exception {
        final int producerCount = 6;
        final int perProducer = 400;
        final int total = producerCount * perProducer;
        var executed = new AtomicInteger();
        var disposed = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(producerCount);
        var completions = java.util.Collections.synchronizedList(
                new ArrayList<LocalVirtualActorI64.Completion>());

        try (var runtime = new LocalVirtualActorI64("app", "Stress", 1, 8,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) { executed.incrementAndGet(); }
                    @Override public void close() { disposed.incrementAndGet(); }
                }, notice -> { throw new AssertionError("unexpected CONTROL during stress"); })) {
            var ref = runtime.resolve("one");
            var producers = new Thread[producerCount];
            for (int i = 0; i < producers.length; i++) {
                producers[i] = new Thread(() -> {
                    try {
                        start.await();
                        for (int message = 0; message < perProducer;) {
                            var result = runtime.try_send(ref, message);
                            if (result.admitted()) {
                                completions.add(result.completion());
                                message++;
                            } else if (result.kind() == LocalVirtualActorI64.AdmissionKind.BACKPRESSURE
                                    || result.kind() == LocalVirtualActorI64.AdmissionKind.ACTIVATING) {
                                Thread.onSpinWait();
                            } else {
                                throw new AssertionError("unexpected concurrent admission: " + result.kind());
                            }
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    } finally {
                        done.countDown();
                    }
                }, "protected-stress-producer-" + i);
                producers[i].start();
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            start.countDown();
            while (done.getCount() != 0 || executed.get() < total) {
                if (failure.get() != null) throw new AssertionError("producer failed", failure.get());
                if (System.nanoTime() >= deadline) throw new AssertionError("concurrent admission timed out");
                if (!runtime.run_one(ref)) Thread.onSpinWait();
            }
            for (Thread producer : producers) producer.join(5_000);
            for (Thread producer : producers)
                check(!producer.isAlive(), "producer physically retired");
            check(completions.size() == total, "every producer received a receipt");
            for (var completion : completions)
                check(completion.join() == LocalVirtualActorI64.Outcome.PROCESSED,
                        "every accepted message was settled");
            check(executed.get() == total, "exactly once per admitted message");
        }
        check(disposed.get() == 1, "one physical disposal after stress");
        cases++;
    }

    private static void senderCannotForgeMessageCompletion() {
        var executed = new AtomicInteger();
        try (var runtime = new LocalVirtualActorI64("app", "Forged", 1, 1,
                ignored -> message -> executed.incrementAndGet(),
                notice -> { throw new AssertionError("unexpected CONTROL"); })) {
            var ref = runtime.resolve("one");
            var accepted = runtime.try_send(ref, 7);
            check(accepted.admitted(), "forgery test must admit work");
            var receipt = accepted.completion();
            check(LocalVirtualActorI64.Completion.class.getConstructors().length == 0,
                    "external clients cannot mint completion handles");
            check(LocalVirtualActorI64.SendResult.class.getConstructors().length == 0,
                    "external clients cannot mint accepted results");
            check(!java.util.concurrent.CompletableFuture.class.isAssignableFrom(
                    receipt.getClass()), "sender must never receive a writable CompletableFuture");
            var downstream = receipt.thenRun(() -> { });
            downstream.toCompletableFuture().complete(null);
            check(!receipt.isDone(), "child completion cannot forge parent receipt");
            receipt.stage().toCompletableFuture().complete(LocalVirtualActorI64.Outcome.PROCESSED);
            check(!receipt.isDone(), "read-only stage copy cannot forge parent completion");
            rejects(runtime::close, "unexecuted work still blocks shutdown");
            check(executed.get() == 0, "guest turn not executed yet");
            check(runtime.run_one(ref), "execute after attempted forgery");
            check(receipt.join() == LocalVirtualActorI64.Outcome.PROCESSED,
                    "only runtime records successful completion");
            check(executed.get() == 1, "guest executed exactly once");
        }
        cases++;
    }


    private static void physicalInitializerIsQuiescencePrerequisite() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var result = new AtomicReference<LocalVirtualActorI64.SendResult>();
        var disposed = new AtomicInteger();
        var runtime = new LocalVirtualActorI64("app", "Init", 1, 1,
                ignored -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("initializer timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("initializer interrupted", interrupted);
                    }
                    return new LocalVirtualActorI64.Turn() {
                        @Override public void on_message(long value) { }
                        @Override public void close() { disposed.incrementAndGet(); }
                    };
                }, notice -> { throw new AssertionError("unexpected CONTROL"); });
        var ref = runtime.resolve("one");
        Thread worker = new Thread(() -> {
            try {
                result.set(runtime.try_send(ref, 1));
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        worker.start();
        try {
            check(entered.await(5, TimeUnit.SECONDS), "factory entered");
            check(runtime.try_send(ref, 2).kind() == LocalVirtualActorI64.AdmissionKind.ACTIVATING,
                    "in-flight factory fences concurrent sender");
            rejects(runtime::close, "shutdown must not close active factory");
            check(disposed.get() == 0, "no early disposal during factory");
        } finally {
            release.countDown();
            worker.join(5000);
        }
        check(!worker.isAlive() && failure.get() == null, "initializer physically finished");
        check(result.get() != null && result.get().admitted(), "initializer's message admitted");
        check(runtime.run_one(ref), "run admitted message");
        check(result.get().completion().join() == LocalVirtualActorI64.Outcome.PROCESSED,
                "post-factory receipt");
        runtime.close();
        check(disposed.get() == 1, "dispose exactly once after initialized lifetime");
        cases++;
    }

    private static void failedInitializationCanRestartWithoutCreditLeak() {
        var initialized = new AtomicInteger();
        var disposed = new AtomicInteger();
        try (var runtime = new LocalVirtualActorI64("app", "Retry", 1, 1,
                ignored -> {
                    if (initialized.incrementAndGet() == 1)
                        throw new IllegalStateException("guest factory rejected");
                    return new LocalVirtualActorI64.Turn() {
                        @Override public void on_message(long value) { }
                        @Override public void close() { disposed.incrementAndGet(); }
                    };
                }, notice -> { throw new AssertionError("unexpected CONTROL"); })) {
            var ref = runtime.resolve("one");
            rejects(() -> runtime.try_send(ref, 1), "first factory rejects admission");
            var accepted = runtime.try_send(ref, 2);
            check(accepted.admitted(), "second factory recovers");
            check(runtime.run_one(ref), "recovered turn executes");
            check(accepted.completion().join() == LocalVirtualActorI64.Outcome.PROCESSED,
                    "recovered receipt");
        }
        check(initialized.get() == 2, "one failed and one published incarnation");
        check(disposed.get() == 1, "only successful incarnation disposed");
        cases++;
    }

    private static void copiedStageCancellationNeverCancelsOriginal() {
        var executed = new AtomicInteger();
        try (var runtime = new LocalVirtualActorI64("app", "Cancel", 1, 1,
                ignored -> ignoredMessage -> executed.incrementAndGet(),
                notice -> { throw new AssertionError("unexpected CONTROL"); })) {
            var ref = runtime.resolve("one");
            var accepted = runtime.try_send(ref, 1);
            check(accepted.admitted(), "cancellation test admission");
            check(accepted.completion().stage().toCompletableFuture().cancel(true),
                    "copied stage cancelled");
            check(!accepted.completion().isDone(), "parent still unresolved");
            rejects(runtime::close, "uncleared credit prevents close");
            check(runtime.run_one(ref), "original message executes");
            check(accepted.completion().join() == LocalVirtualActorI64.Outcome.PROCESSED,
                    "original message settles despite copy cancellation");
            check(executed.get() == 1, "guest ran once");
        }
        cases++;
    }

    public static void main(String[] args) throws Exception {
        scalarLeaseAndOverflow();
        closeRevokesOutstandingGateLoans();
        fencedIncarnationAndCredits();
        acceptedFifoAndBackpressure();
        completionAllowsReentrantAdmit();
        missingControlPreservesAcceptedTurn();
        physicalHandlerBlocksTeardown();
        foreignRuntimeRefNeverCrossesDirectory();
        concurrentBackpressureAndSettlement();
        senderCannotForgeMessageCompletion();
        physicalInitializerIsQuiescencePrerequisite();
        failedInitializationCanRestartWithoutCreditLeak();
        copiedStageCancellationNeverCancelsOriginal();
        System.out.println("PROTECTED_ACTOR_JDK21_SMOKE|passed=" + cases);
    }
}
