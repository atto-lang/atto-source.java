package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A linked upstream token remains independent of the TaskScope owner.
 * Failing to publish the registration must revoke that subscription, not
 * orphan a retained callback that may cancel this scope much later.
 */
@Timeout(15)
final class OresTaskScopeCancelLinkAdmissionTest {
    @SuppressWarnings("unchecked")
    @Test
    void failedRegistrationListGrowthDetachesUpstreamTokenImmediately()
            throws Exception {
        var upstream = new OresCancellationSource();
        try (var scope = OresTaskScope.compilerOwned()) {
            Field links = OresTaskScope.class.getDeclaredField(
                    "externalCancellation");
            links.setAccessible(true);
            List<OresCancellationToken.Registration> original =
                    (List<OresCancellationToken.Registration>) links.get(scope);
            var injected = new OutOfMemoryError(
                    "simulated TaskScope cancellation link list growth");
            List<OresCancellationToken.Registration> faulted =
                    new AbstractList<>() {
                @Override public OresCancellationToken.Registration get(int index) {
                    throw new IndexOutOfBoundsException(index);
                }
                @Override public int size() { return 0; }
                @Override public void add(
                        int index, OresCancellationToken.Registration registration) {
                    throw injected;
                }
            };
            int priorWaiters = upstream.notification().pendingRuntimeWaiterCount();
            try {
                links.set(scope, faulted);
                assertSame(injected, assertThrows(OutOfMemoryError.class,
                        () -> scope.cancelWhen(upstream.token())));
                assertEquals(priorWaiters,
                        upstream.notification().pendingRuntimeWaiterCount(),
                        "failed TaskScope admission must detach upstream observer");
                assertFalse(scope.isCancelRequested());
                upstream.cancel();
                assertFalse(scope.isCancelRequested(),
                        "unowned upstream listener must not cancel a live scope");
            } finally {
                links.set(scope, original);
            }
        }
    }

    @Test
    void alreadyCancelledUpstreamCancelsScopeWithoutRetainedSubscription() {
        var upstream = new OresCancellationSource();
        upstream.cancel();
        try (var scope = OresTaskScope.compilerOwned()) {
            scope.cancelWhen(upstream.token());
            assertTrue(scope.isCancelRequested(),
                    "already-terminal token must synchronously seal new Task admissions");
            assertEquals(0, upstream.notification().pendingRuntimeWaiterCount());
        }
    }
}
