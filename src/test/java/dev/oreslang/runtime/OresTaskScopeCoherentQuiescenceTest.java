package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A quiescence read must linearize against admissions into ANY scope in the
 * owned tree. Merely locking individual scope snapshots admits torn reads.
 * This package/reflection-level probe does not grant code-unload authority.
 */
@Timeout(25)
final class OresTaskScopeCoherentQuiescenceTest {
    @Test
    void physicalObservationWaitsForTreeAdmissionAndSeesOwnedChildFrame()
            throws Exception {
        try (var scheduler = new OresScheduler(1);
             var root = OresTaskScope.compilerOwned()) {
            var child = root.childScope();
            var field = OresTaskScope.class.getDeclaredField("treeAdmissionGate");
            field.setAccessible(true);
            Object rootGate = field.get(root);
            assertSame(rootGate, field.get(child),
                    "every descendant must share the root admission linearization");

            var releaseFrame = new CountDownLatch(1);
            var enteredFrame = new CountDownLatch(1);
            var enteredObserver = new CountDownLatch(1);
            var observed = new AtomicReference<Boolean>();
            var error = new AtomicReference<Throwable>();
            Thread reader = null;
            try {
                synchronized (rootGate) {
                    // Root admission is held while a real compiler Task is
                    // registered in the child. The Task's result and physical
                    // exit are not yet settled.
                    child.startCompilerFrame(
                            scheduler, resume -> {
                                enteredFrame.countDown();
                                try {
                                    if (!releaseFrame.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("frame release timeout");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                                return OresScheduler.done(1);
                            });
                    reader = Thread.ofPlatform().start(() -> {
                        enteredObserver.countDown();
                        try {
                            observed.set(root.physicallyQuiescent());
                        } catch (Throwable failure) {
                            error.set(failure);
                        }
                    });
                    assertTrue(enteredObserver.await(5, TimeUnit.SECONDS));
                    // A correct observer must acquire rootGate BEFORE its
                    // per-scope snapshots. If it terminates without blocking,
                    // it has bypassed the concurrent admission fence.
                    boolean blocked = false;
                    long deadline = System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(3);
                    while (System.nanoTime() < deadline) {
                        Thread.State state = reader.getState();
                        if (state == Thread.State.BLOCKED) {
                            blocked = true;
                            break;
                        }
                        if (state == Thread.State.TERMINATED) break;
                        Thread.onSpinWait();
                    }
                    assertTrue(blocked,
                            "quiescence observer must block on tree-wide admission gate");
                }
                reader.join(5000);
                assertFalse(reader.isAlive());
                assertNull(error.get());
                assertEquals(Boolean.FALSE, observed.get(),
                        "pending physical child frame must defeat quiescence");
                assertTrue(enteredFrame.await(5, TimeUnit.SECONDS));
            } finally {
                releaseFrame.countDown();
                if (reader != null) reader.join(5000);
            }
        }
    }
}
