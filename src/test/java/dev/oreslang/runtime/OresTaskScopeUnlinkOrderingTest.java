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
}
