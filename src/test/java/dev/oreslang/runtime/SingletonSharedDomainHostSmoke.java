package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executable host-Java smoke. A real Java class, not a JS/Python model.
 * Guest parse/typecheck and GraalVM native-isolate parity are NOT exercised.
 * Can run with javac/java alone or from SingletonSharedDomainHostTest JUnit.
 */
public final class SingletonSharedDomainHostSmoke {
    private static int assertions;

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private static void rejects(Runnable call, String messagePart) {
        assertions++;
        try {
            call.run();
        } catch (Throwable error) {
            Throwable cause = error instanceof CompletionException
                    ? error.getCause() : error;
            if (cause instanceof UnsupportedOperationException && "null".equals(messagePart))
                return;
            if (cause != null && cause.getMessage() != null
                    && cause.getMessage().contains(messagePart)) return;
            throw new AssertionError("wrong rejection: " + messagePart, error);
        }
        throw new AssertionError("expected rejection: " + messagePart);
    }

    public static void main(String[] args) throws Exception {
        assertions = 0;
        snapshotsInitializeOnceAndFreeze();
        concurrencyAndFairReaderWriterLoans();
        capabilityOwnerQueueAndStop();
        reentrantWriterPublicationFence();
        cancellationAndCallbackStopPinning();
        System.out.println("SINGLETON_JAVA_HOST_ASSERTIONS=" + assertions);
        System.out.println("SINGLETON_JAVA_HOST_PASS=true");
        System.out.println("COMPILED_ATTO_GUEST=NOT_EXECUTED");
    }

    private static void snapshotsInitializeOnceAndFreeze() throws Exception {
        var domain = new SingletonSharedDomain();
        var key = new SingletonSharedDomain.Key("tenant", "module", "Settings");
        var initial = new LinkedHashMap<String, Object>();
        initial.put("nested", new ArrayList<>(List.of(1, 2, 3)));
        Object a = domain.snapshotOnce(key, () -> initial).join();
        Object b = domain.snapshotOnce(key, () -> Map.of("nested", List.of(99))).join();
        check(a == b, "same compiler key must yield the same domain snapshot");
        initial.put("nested", List.of(99));
        var snapshot = (Map<?, ?>) a;
        check(((List<?>) snapshot.get("nested")).get(0).equals(1),
                "snapshot detached from original mutable data");
        rejects(() -> snapshot.clear(), "null");
        rejects(() -> ((List<?>) snapshot.get("nested")).clear(), "null");
        var otherDomain = new SingletonSharedDomain();
        check(otherDomain.snapshotOnce(key, () -> Map.of("nested", List.of(1))).join() != a,
                "tenants have independent snapshot objects");

        var failure = new SingletonSharedDomain.Key("tenant", "module", "Broken");
        var attempts = new AtomicInteger();
        var first = domain.snapshotOnce(failure, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("sticky boot failure");
        });
        rejects(first::join, "sticky boot failure");
        rejects(domain.snapshotOnce(failure, () -> { attempts.incrementAndGet(); return 7; })::join,
                "sticky boot failure");
        check(attempts.get() == 1, "failure does not retry initializer");
        var cyclic = new SingletonSharedDomain.Key("tenant", "module", "Cycle");
        rejects(domain.snapshotOnce(cyclic,
                () -> domain.snapshotOnce(cyclic, () -> 3).join())::join,
                "singleton initializer cycle");

        rejects(() -> domain.snapshotOnce(
                new SingletonSharedDomain.Key("tenant", "module", "Bad"),
                () -> new Object()).join(), "unproved mutable singleton");
        rejects(() -> domain.snapshotOnce(
                new SingletonSharedDomain.Key("tenant", "module", "NaN"),
                () -> Double.NaN).join(), "nonfinite");
        rejects(() -> domain.snapshotOnce(
                new SingletonSharedDomain.Key("tenant", "module", "Huge"),
                () -> "x".repeat(8193)).join(), "too long");

        var bounded = new SingletonSharedDomain(2);
        bounded.snapshotOnce(new SingletonSharedDomain.Key("tenant", "pkg", "A"),
                () -> 1).join();
        bounded.snapshotOnce(new SingletonSharedDomain.Key("tenant", "pkg", "B"),
                () -> 2).join();
        rejects(() -> bounded.snapshotOnce(
                new SingletonSharedDomain.Key("tenant", "pkg", "C"), () -> 3).join(),
                "singleton identity quota exceeded");

        // Caller may complete its own derived future, never the registry cell.
        var authoritativeKey = new SingletonSharedDomain.Key("tenant", "pkg", "Receipt");
        var receipt = new SingletonSharedDomain();
        check(receipt.snapshotOnce(authoritativeKey, () -> 41).join().equals(41),
                "initial registry value");
        var derived = receipt.snapshotOnce(authoritativeKey, () -> 99);
        derived.obtrudeValue(1000);
        check(receipt.snapshotOnce(authoritativeKey, () -> 99).join().equals(41),
                "external future obtrusion cannot overwrite canonical value");

        var concurrentKey = new SingletonSharedDomain.Key("tenant", "module", "Once");
        var count = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        var results = new ArrayList<CompletableFuture<Object>>();
        try {
            for (int i = 0; i < 32; i++) {
                results.add(CompletableFuture.supplyAsync(() -> {
                    try { start.await(); }
                    catch (InterruptedException ex) { throw new RuntimeException(ex); }
                    return domain.snapshotOnce(concurrentKey, () -> {
                        count.incrementAndGet();
                        return Map.of("ready", true);
                    }).join();
                }, pool));
            }
            start.countDown();
            var all = CompletableFuture.allOf(results.toArray(CompletableFuture[]::new));
            all.orTimeout(10, TimeUnit.SECONDS).join();
            Object firstValue = results.get(0).join();
            for (var result : results) check(result.join() == firstValue,
                    "concurrent first access returns one identity");
            check(count.get() == 1, "single concurrent initializer");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void concurrencyAndFairReaderWriterLoans() throws Exception {
        var d = new SingletonSharedDomain();
        var owner = d.principalForRuntime(10, 1, 0);
        var a = d.principalForRuntime(10, 1, 101);
        var b = d.principalForRuntime(11, 1, 102);
        var writerP = d.principalForRuntime(12, 1, 103);
        var lateP = d.principalForRuntime(13, 1, 104);
        var key = new SingletonSharedDomain.Key("tenant", "mod", "Counters");
        var region = d.regionForRuntime(key, owner, Map.of("count", 0), 8);
        check(region == d.regionForRuntime(key, owner, Map.of("count", 888), 8),
                "same region identity regardless of memory slice");
        var ar = region.acquire(a, region.delegateForRuntime(owner, a, SingletonSharedDomain.Access.READ)).join();
        var br = region.acquire(b, region.delegateForRuntime(owner, b, SingletonSharedDomain.Access.READ)).join();
        check(region.activeReaders() == 2, "simultaneous readers");

        var waitingW = region.acquire(writerP, region.delegateForRuntime(owner, writerP, SingletonSharedDomain.Access.WRITE));
        var late = region.acquire(lateP, region.delegateForRuntime(owner, lateP, SingletonSharedDomain.Access.READ));
        check(region.pendingCount() == 2, "writer barrier queue");
        check(!waitingW.isDone() && !late.isDone(), "writer and late reader await");
        rejects(() -> waitingW.complete(null), "runtime-owned future");
        rejects(() -> waitingW.completeExceptionally(new IllegalStateException("forged")),
                "runtime-owned future");
        rejects(() -> waitingW.obtrudeValue(null), "runtime-owned future");
        check(!waitingW.isDone(), "forged caller completion never grants a lease");
        rejects(() -> ar.write(a, old -> old), "exclusive");

        var overlap = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var t1 = pool.submit(() -> {
                check(((Map<?, ?>) ar.read(a)).get("count").equals(0), "first read");
                overlap.countDown();
                await(release);
            });
            var t2 = pool.submit(() -> {
                check(((Map<?, ?>) br.read(b)).get("count").equals(0), "second read");
                overlap.countDown();
                await(release);
            });
            check(overlap.await(10, TimeUnit.SECONDS), "two physical readers overlap");
            release.countDown();
            t1.get(10, TimeUnit.SECONDS); t2.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        ar.release(a);
        check(!waitingW.isDone(), "writer waits for second reader");
        br.release(b);
        var wl = waitingW.copy().orTimeout(10, TimeUnit.SECONDS).join();
        check(region.activeWriters() == 1 && region.activeReaders() == 0,
                "exclusive logical writer");
        rejects(() -> ar.read(a), "revoked");
        wl.write(writerP, before -> Map.of("count",
                (int) ((Map<?, ?>) before).get("count") + 1));
        check(((Map<?, ?>) wl.read(writerP)).get("count").equals(1), "write published");
        check(!late.isDone(), "late reader must not bypass writer");
        wl.release(writerP);
        var lr = late.copy().orTimeout(10, TimeUnit.SECONDS).join();
        check(((Map<?, ?>) lr.read(lateP)).get("count").equals(1),
                "reader after writer sees committed value");
        lr.release(lateP);
        check(region.activeReaders() == 0 && region.activeWriters() == 0,
                "all physical loans released");
    }

    private static void capabilityOwnerQueueAndStop() {
        var d = new SingletonSharedDomain();
        var owner = d.principalForRuntime(21, 1, 0);
        var reader = d.principalForRuntime(21, 1, 1);
        var next = d.principalForRuntime(22, 1, 0);
        var stranger = new SingletonSharedDomain().principalForRuntime(21, 1, 1);
        var region = d.regionForRuntime(
                new SingletonSharedDomain.Key("tenant", "pkg", "Lease"),
                owner, Map.of("value", 5), 0);
        var w = region.acquire(owner,
                region.delegateForRuntime(owner, owner, SingletonSharedDomain.Access.WRITE)).join();
        var waiting = region.delegateForRuntime(owner, reader, SingletonSharedDomain.Access.READ);
        rejects(() -> region.acquire(reader, waiting).join(), "queue full");
        rejects(() -> region.acquire(stranger, waiting).join(), "stale or forged");
        rejects(() -> region.delegateForRuntime(reader, next, SingletonSharedDomain.Access.READ),
                "only region owner");
        rejects(() -> region.transferOwnerForRuntime(owner, next), "quiescence");
        w.release(owner);
        var r = region.acquire(reader, waiting).join();
        check(((Map<?, ?>) r.read(reader)).get("value").equals(5), "failed admission preserves grant");
        rejects(() -> r.release(owner), "foreign");
        r.release(reader);
        region.transferOwnerForRuntime(owner, next);
        rejects(() -> region.acquire(reader, waiting).join(), "stale");
        rejects(() -> region.delegateForRuntime(owner, reader, SingletonSharedDomain.Access.READ),
                "only region owner");
        var nextR = region.acquire(reader, region.delegateForRuntime(next, reader,
                SingletonSharedDomain.Access.READ)).join();
        region.stopForRuntime(next);
        check(!region.physicallyRetired(), "STOP preserves physical loan pin");
        rejects(() -> nextR.read(reader), "revoked");
        nextR.release(reader);
        check(region.physicallyRetired(), "physical retirement after final release");
        rejects(() -> region.acquire(reader, waiting).join(), "stale");
    }

    private static void reentrantWriterPublicationFence() {
        var d = new SingletonSharedDomain();
        var owner = d.principalForRuntime(31, 1, 0);
        var successor = d.principalForRuntime(32, 1, 1);
        var region = d.regionForRuntime(
                new SingletonSharedDomain.Key("tenant", "pkg", "Guard"),
                owner, Map.of("count", 0), 3);
        var first = region.acquire(owner, region.delegateForRuntime(
                owner, owner, SingletonSharedDomain.Access.WRITE)).join();
        var pending = region.acquire(successor, region.delegateForRuntime(
                owner, successor, SingletonSharedDomain.Access.WRITE));
        rejects(() -> first.write(owner, before -> {
            first.release(owner);
            check(!pending.isDone(),
                    "new writer cannot enter while old callback is physically live");
            return Map.of("count", 999);
        }), "revoked");
        var next = pending.join();
        check(((Map<?, ?>) next.read(successor)).get("count").equals(0),
                "revoked writer never publishes stale state");
        rejects(() -> next.write(successor, before ->
                CompletableFuture.completedFuture(Map.of("count", 99))), "await");
        check(((Map<?, ?>) next.read(successor)).get("count").equals(0),
                "async candidate never publishes");
        next.release(successor);
        region.stopForRuntime(owner);
        check(region.physicallyRetired(), "retirement after successful shutdown");
    }

    private static void cancellationAndCallbackStopPinning() {
        var d = new SingletonSharedDomain();
        var owner = d.principalForRuntime(41, 1, 0);
        var writerP = d.principalForRuntime(42, 1, 1);
        var late = d.principalForRuntime(43, 1, 2);
        var region = d.regionForRuntime(
                new SingletonSharedDomain.Key("tenant", "pkg", "Cancellation"),
                owner, Map.of("number", 5), 4);
        var reader = region.acquire(owner, region.delegateForRuntime(
                owner, owner, SingletonSharedDomain.Access.READ)).join();
        var writer = region.acquire(writerP, region.delegateForRuntime(
                owner, writerP, SingletonSharedDomain.Access.WRITE));
        var following = region.acquire(late, region.delegateForRuntime(
                owner, late, SingletonSharedDomain.Access.READ));
        check(!following.isDone(), "writer barrier prevents bypass");
        check(writer.cancel(false), "queued writer cancellation accepted");
        var second = following.copy().orTimeout(10, TimeUnit.SECONDS).join();
        check(region.activeReaders() == 2, "later reader joins after writer cancellation");
        second.release(late);
        reader.release(owner);
        check(region.activeReaders() == 0, "reader cohort drained");

        var exclusive = region.acquire(owner, region.delegateForRuntime(
                owner, owner, SingletonSharedDomain.Access.WRITE)).join();
        rejects(() -> exclusive.write(owner, before -> {
            exclusive.release(owner);
            region.stopForRuntime(owner);
            check(!region.physicallyRetired(),
                    "STOP cannot physically free region during executing callback");
            return Map.of("number", 999);
        }), "revoked");
        check(region.physicallyRetired(),
                "callback unwind releases the final physical reclamation pin");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private SingletonSharedDomainHostSmoke() { }
}
