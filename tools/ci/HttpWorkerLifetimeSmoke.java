package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Standalone, dependency-free exact-production-helper smoke suite.
 *
 * Compile with the actual Git-tree helper sources using javac 21+; no
 * Maven, JUnit, network or substitute reimplementation is involved.
 * This does NOT prove actual Atto guest execution or carrier finalization.
 */
public final class HttpWorkerLifetimeSmoke {
    private static long assertions;

    private static void require(boolean value, String explanation) {
        assertions++;
        if (!value) throw new AssertionError(explanation);
    }

    private static void admissionFence() {
        var gate = new HttpPooledWorkerAdmissionFence();
        require(!gate.quiescent(), "unsealed gate claimed quiescence");
        for (int i = 0; i < 32; i++) require(gate.tryReserve(), "lost pre-seal handoff");
        gate.seal();
        require(!gate.tryReserve(), "post-seal admission succeeded");
        for (int i = 0; i < 32; i++) {
            require(!gate.quiescent(), "hand-off still outstanding");
            gate.release();
        }
        require(gate.quiescent(), "missing retirement receipt");
        try {
            gate.release();
            throw new AssertionError("double release accepted");
        } catch (IllegalStateException expected) {
            assertions++;
        }
        require(!gate.tryReserve(), "sealed gate reopened");
    }

    private static void historicalGenerations() {
        var registry = new HttpPooledWorkerGenerationRegistry<Object>();
        var generations = new HashSet<Object>();
        for (int i = 0; i < 10; i++) {
            Object identity = new Object();
            require(registry.register(identity), "failed to register worker");
            generations.add(identity);
        }
        require(registry.outstanding() == 10, "lost historical worker");
        require(Set.copyOf(registry.stopAndSnapshot()).equals(generations),
                "shutdown failed to snapshot all incarnations");
        require(!registry.register(new Object()), "registered a generation after pool stop");
        for (Object identity : generations) registry.retire(identity);
        require(registry.outstanding() == 0, "orphan generation after physical retire");
    }

    private static void concurrentGenerationStop() throws Exception {
        final int concurrentProducers = 96;
        var gate = new HttpPooledWorkerAdmissionFence();
        var registry = new HttpPooledWorkerGenerationRegistry<Object>();
        var ready = new CountDownLatch(concurrentProducers);
        var start = new CountDownLatch(1);
        var accepted = ConcurrentHashMap.<Object>newKeySet();
        require(registry.register(new Object()), "seed registration failed");
        require(gate.tryReserve(), "seed handoff failed");
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < concurrentProducers; i++) {
                tasks.add(threads.submit(() -> {
                    Object incarnation = new Object();
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("start deadline");
                    }
                    if (registry.register(incarnation)) accepted.add(incarnation);
                    if (gate.tryReserve()) gate.release();
                    return null;
                }));
            }
            require(ready.await(5, TimeUnit.SECONDS), "producers did not start");
            start.countDown();
            gate.seal();
            List<Object> snapshot = registry.stopAndSnapshot();
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            require(snapshot.size() == accepted.size() + 1, "stop lost a generation");
            require(new HashSet<>(snapshot).containsAll(accepted),
                    "stop snapshot omitted an admitted incarnation");
            require(!gate.quiescent(), "pre-seal handoff disappeared");
            require(!gate.tryReserve(), "sealed worker accepted a new handoff");
            gate.release();
            require(gate.quiescent(), "handoff lease failed to retire");
            for (Object worker : snapshot) registry.retire(worker);
            require(registry.outstanding() == 0, "pool retirement incomplete");
        }
    }

    private static final class EqualListener {
        @Override public boolean equals(Object other) { return other instanceof EqualListener; }
        @Override public int hashCode() { return 1; }
    }

    private static void isolatedListenerSlots() {
        var slots = new HttpPooledWorkerListenerSlots<EqualListener, Object>(2);
        var a = new EqualListener();
        var b = new EqualListener();
        Object a0 = new Object();
        Object b0 = new Object();
        var firstA = slots.next(a);
        var firstB = slots.next(b);
        require(firstA.previous() == null && firstB.previous() == null,
                "listener B inherited listener A's slot");
        slots.replace(a, firstA, a0);
        slots.replace(b, firstB, b0);
        require(slots.listenerCount() == 2, "identity collision merged two listeners");
        var secondA = slots.next(a);
        var secondB = slots.next(b);
        require(secondA.previous() == null && secondB.previous() == null,
                "listener slot rotation leaked a worker");
        slots.replace(a, secondA, new Object());
        slots.replace(b, secondB, new Object());
        require(slots.next(a).previous() == a0, "listener A cannot reuse its actor");
        require(slots.next(b).previous() == b0, "listener B cannot reuse its actor");
        slots.forget(a);
        require(slots.listenerCount() == 1, "closed listener slots still retained");
        require(slots.next(a).previous() == null,
                "closed listener inherited its retired actor generation");
        require(slots.next(b).previous() != null,
                "closing A destroyed B's live worker");
    }

    private static final class OwnedWorker {
        final Object owner;
        OwnedWorker(Object owner) { this.owner = owner; }
    }

    private static void concurrentListenerIsolation() throws Exception {
        final int listeners = 32;
        final int rotations = 128;
        var slots = new HttpPooledWorkerListenerSlots<Object, OwnedWorker>(3);
        var ready = new CountDownLatch(listeners);
        var go = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var workers = new ArrayList<Future<?>>();
            for (int i = 0; i < listeners; i++) {
                final Object owner = new Object();
                workers.add(threads.submit(() -> {
                    ready.countDown();
                    if (!go.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("listener stress start deadline");
                    }
                    for (int turn = 0; turn < rotations; turn++) {
                        // Match the atomic next + replace operation performed
                        // under the production Handler monitor.
                        synchronized (slots) {
                            var selected = slots.next(owner);
                            if (selected.previous() != null
                                    && selected.previous().owner != owner) {
                                throw new AssertionError("worker crossed listener boundary");
                            }
                            slots.replace(owner, selected, new OwnedWorker(owner));
                        }
                    }
                    return null;
                }));
            }
            require(ready.await(5, TimeUnit.SECONDS), "not all listeners started");
            go.countDown();
            for (Future<?> worker : workers) worker.get(10, TimeUnit.SECONDS);
            require(slots.listenerCount() == listeners,
                    "parallel worker rotations merged listener identities");
        }
    }

    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 100; round++) {
            admissionFence();
            historicalGenerations();
            concurrentGenerationStop();
            isolatedListenerSlots();
            concurrentListenerIsolation();
        }
        System.out.printf(
                "PASS: %,d assertions; 100 concurrent rounds x 96 virtual-thread producers (JDK %s)%n",
                assertions, Runtime.version().feature());
    }
}
