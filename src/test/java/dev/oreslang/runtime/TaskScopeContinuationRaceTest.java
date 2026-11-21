package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial races for continuation-created children.
 *
 * A closing scope may still admit a descendant through a live executing
 * registration. Terminal publication or cancellation must revoke that authority
 * without allowing premature drain or leaking a child registration.
 */
@Timeout(30)
final class TaskScopeContinuationRaceTest {

    @Test
    void callbackChildAdmissionRacingParentTerminalNeverEscapesDrain()
            throws Exception {
        for (int attempt = 0; attempt < 512; attempt++) {
            TaskScope scope = new TaskScope();
            TaskScope.Registration chain = scope.reserve();
            chain.beganExecution();

            OresFuture<Void> chainFuture = new OresFuture<>();
            chain.attach(chainFuture);
            OresFuture<Void> joined = scope.close();

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<TaskScope.Registration> childRef =
                    new AtomicReference<>();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            AtomicInteger rejected = new AtomicInteger();

            Thread spawn = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    TaskScope.Registration child = scope.reserve(chain);
                    childRef.set(child);
                    child.beganExecution();
                    child.attach(OresFuture.completed(null));
                    child.quiesced();
                } catch (IllegalStateException expected) {
                    // Parent terminal authority may win first. Rejection is the
                    // only valid alternative to admission-before-terminal.
                    rejected.incrementAndGet();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });

            Thread terminal = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    assertTrue(chainFuture.completeFromRuntime(null));
                    chain.quiesced();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });

            start.countDown();
            spawn.join();
            terminal.join();

            assertNull(unexpected.get(), () -> "unexpected race failure: " + unexpected.get());
            assertTrue(rejected.get() == 0 || rejected.get() == 1);
            assertTrue(joined.isDone(),
                    "scope leaked a continuation child after parent retirement");
            assertDoesNotThrow(joined::join);
            assertThrows(IllegalStateException.class, () -> scope.reserve(chain),
                    "retired chain permit must never reopen a closed scope");
        }
    }

    @Test
    void cancellationRacingDescendantRegistrationCannotMissLateAttachment()
            throws Exception {
        for (int attempt = 0; attempt < 256; attempt++) {
            TaskScope scope = new TaskScope();
            TaskScope.Registration parent = scope.reserve();
            parent.beganExecution();
            OresFuture<Void> parentFuture = new OresFuture<>();
            parent.attach(parentFuture);
            OresFuture<Void> joined = scope.close();

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<TaskScope.Registration> childRef =
                    new AtomicReference<>();
            AtomicReference<OresFuture<Void>> childFutureRef =
                    new AtomicReference<>();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread register = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    TaskScope.Registration child = scope.reserve(parent);
                    childRef.set(child);
                    child.beganExecution();
                    OresFuture<Void> childFuture = new OresFuture<>();
                    childFutureRef.set(childFuture);
                    child.attach(childFuture);
                } catch (IllegalStateException expected) {
                    // Cancellation sealed admission before reserve(parent).
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });

            Thread cancel = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    assertSame(joined, scope.cancelAndDrain());
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });

            start.countDown();
            register.join();
            cancel.join();

            assertNull(unexpected.get(), () -> "unexpected cancellation race failure: " + unexpected.get());
            assertTrue(parentFuture.isCancelled());
            assertThrows(IllegalStateException.class, () -> scope.reserve(parent));

            TaskScope.Registration child = childRef.get();
            OresFuture<Void> childFuture = childFutureRef.get();
            if (child != null) {
                assertNotNull(childFuture);
                assertTrue(childFuture.isCancelled(),
                        "late attachment must observe the cancellation seal");
                child.quiesced();
            }

            assertFalse(joined.isDone(),
                    "Future cancellation alone must not forge parent quiescence");
            parent.quiesced();
            assertThrows(
                    ExecutionException.class,
                    () -> joined.get(5, TimeUnit.SECONDS));
        }
    }
}
