package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Executable pure Java proof for the one-way physical ActorRef ingress fence. */
final class ActorPhysicalHotRouteIngressFenceTest {
    @Test
    void ordinaryRawDeliveryAllowedBeforeClaimThenFailsClosed() {
        var fence = new ActorPhysicalHotRouteIngressFence();
        assertDoesNotThrow(() -> fence.require(null));
        assertFalse(fence.claimed());
        var route = fence.claim();
        assertTrue(fence.claimed());
        assertThrows(SecurityException.class, () -> fence.require(null),
                "stale raw physical ActorRef must be fenced after claim");
        assertDoesNotThrow(() -> fence.require(route));
        assertThrows(IllegalStateException.class, fence::claim);
    }

    @Test
    void foreignPermitCannotAuthorizeOpenOrClaimedTarget() {
        var a = new ActorPhysicalHotRouteIngressFence();
        var b = new ActorPhysicalHotRouteIngressFence();
        var permit = a.claim();
        assertThrows(SecurityException.class, () -> b.require(permit));
        b.claim();
        assertThrows(SecurityException.class, () -> b.require(permit));
        assertDoesNotThrow(() -> a.require(permit));
    }

    @Test
    void retirementNeverRestoresRawOrOldRouteIngress() {
        var fence = new ActorPhysicalHotRouteIngressFence();
        var permit = fence.claim();
        fence.require(permit);
        permit.retire();
        permit.retire();
        assertTrue(permit.retired());
        assertThrows(SecurityException.class, () -> fence.require(permit));
        assertThrows(SecurityException.class, () -> fence.require(null));
        assertThrows(IllegalStateException.class, fence::claim);
    }

    @Test
    void exactlyOneConcurrentOwnerWinsIngressClaim() throws Exception {
        var fence = new ActorPhysicalHotRouteIngressFence();
        int contenders = 12;
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(contenders);
        var success = new AtomicInteger();
        var rejected = new AtomicInteger();
        var unexpected = new AtomicInteger();
        Thread[] threads = new Thread[contenders];
        for (int i = 0; i < contenders; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                    fence.claim();
                    success.incrementAndGet();
                } catch (IllegalStateException expected) {
                    rejected.incrementAndGet();
                } catch (Throwable error) {
                    unexpected.incrementAndGet();
                }
            });
            threads[i].setDaemon(true);
            threads[i].start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : threads) {
            worker.join(5000);
            assertFalse(worker.isAlive());
        }
        assertEquals(1, success.get());
        assertEquals(contenders - 1, rejected.get());
        assertEquals(0, unexpected.get());
        assertThrows(SecurityException.class, () -> fence.require(null));
    }

    @Test
    void oneWaySealingDoesNotAllowPermitInjectionIntoFreshActor() {
        var claimed = new ActorPhysicalHotRouteIngressFence();
        var fresh = new ActorPhysicalHotRouteIngressFence();
        var permit = claimed.claim();
        assertThrows(SecurityException.class, () -> fresh.require(permit));
        assertDoesNotThrow(() -> fresh.require(null));
        var freshPermit = fresh.claim();
        assertThrows(SecurityException.class, () -> claimed.require(freshPermit));
        assertDoesNotThrow(() -> fresh.require(freshPermit));
    }
}
