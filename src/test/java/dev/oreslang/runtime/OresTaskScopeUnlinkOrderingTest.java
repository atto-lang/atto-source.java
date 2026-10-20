package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Host token-detach cannot delay producer revocation or re-enter owner locks. */
@Timeout(20)
final class OresTaskScopeUnlinkOrderingTest {
    @SuppressWarnings("unchecked")
    @Test
    void tokenDetachObservesRevokedChildProducerOutsideAdmissionLocks()
            throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope root = new OresTaskScope(runtime)) {
            CountDownLatch entered = new CountDownLatch(1);
            OresTask<Integer> child = root.submit(() -> {
                entered.countDown();
                Thread.sleep(30_000);
                return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            Field tokenField = OresTaskScope.class.getDeclaredField("externalCancellation");
            tokenField.setAccessible(true);
            List<OresCancellationToken.Registration> tokenRegistrations =
                    (List<OresCancellationToken.Registration>) tokenField.get(root);
            Field gateField = OresTaskScope.class.getDeclaredField("gate");
            gateField.setAccessible(true);
            Field treeField = OresTaskScope.class.getDeclaredField("treeAdmissionGate");
            treeField.setAccessible(true);
            Object gate = gateField.get(root);
            Object tree = treeField.get(root);

            AtomicBoolean sawRevocation = new AtomicBoolean();
            AtomicBoolean heldLock = new AtomicBoolean(true);
            tokenRegistrations.add(() -> {
                sawRevocation.set(child.isCancelled());
                heldLock.set(Thread.holdsLock(gate) || Thread.holdsLock(tree));
            });

            assertTrue(root.cancel());
            assertTrue(sawRevocation.get(),
                    "owner producer must be revoked before token unlink");
            assertFalse(heldLock.get(), "unlink must run outside owner monitors");
            assertDoesNotThrow(root::close);
            assertTrue(child.isQuiescent());
        }
    }

    @Test
    void failedTokenRegistrationDetachesUpstreamObserverAndKeepsScopeOpen()
            throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime);
             OresCancellationSource source = new OresCancellationSource()) {
            Field field = OresTaskScope.class.getDeclaredField("externalCancellation");
            field.setAccessible(true);
            Object original = field.get(scope);
            java.util.List<OresCancellationToken.Registration> hostile =
                    new java.util.ArrayList<>() {
                        @Override
                        public boolean add(OresCancellationToken.Registration registration) {
                            throw new IllegalStateException(
                                    "injected token registration allocation failure");
                        }
                    };
            field.set(scope, hostile);
            try {
                assertEquals(0, source.notification().pendingRuntimeWaiterCount());
                var exception = assertThrows(IllegalStateException.class,
                        () -> scope.cancelWhen(source.token()));
                assertTrue(exception.getMessage().contains(
                        "token registration allocation failure"));
                assertEquals(0, source.notification().pendingRuntimeWaiterCount(),
                        "failed owner registration may not leak source subscription");
            } finally {
                field.set(scope, original);
            }

            assertFalse(scope.isCancelRequested(),
                    "failed subscription must not unexpectedly seal scope");
            assertTrue(source.cancel(), "upstream source can still cancel itself");
            assertFalse(scope.isCancelRequested(),
                    "rolled-back subscription must not cancel the Task scope");
            OresTask<Integer> task = scope.submit(() -> 123);
            assertEquals(123, AsyncRuntime.await(task.getAwaited()));
            assertNull(AsyncRuntime.await(task.getDone()));
        }
    }

    @Test
    void tokenInsertThenThrowRollbackUnregistersAndRemovesPartialLink()
            throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime);
             OresCancellationSource source = new OresCancellationSource()) {
            Field field = OresTaskScope.class.getDeclaredField("externalCancellation");
            field.setAccessible(true);
            Object original = field.get(scope);
            java.util.List<OresCancellationToken.Registration> hostile =
                    new java.util.ArrayList<>() {
                        @Override
                        public boolean add(OresCancellationToken.Registration registration) {
                            super.add(registration);
                            throw new IllegalStateException("injected token insert then throw");
                        }
                    };
            field.set(scope, hostile);
            try {
                var exception = assertThrows(IllegalStateException.class,
                        () -> scope.cancelWhen(source.token()));
                assertTrue(exception.getMessage().contains("token insert then throw"));
                assertEquals(0, hostile.size(),
                        "partial external link must be removed on failed admission");
                assertEquals(0, source.notification().pendingRuntimeWaiterCount(),
                        "upstream waiter must be detached even after partial insertion");
            } finally {
                field.set(scope, original);
            }
            assertTrue(source.cancel());
            assertFalse(scope.isCancelRequested());
        }
    }
}
