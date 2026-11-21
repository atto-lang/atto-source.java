package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresHostFutureBridge;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Truffle root carriers, not simulated guest Runnable callbacks. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class OresContextPhysicalEntryExecutionTest {
    @Test
    void rootCarrierCannotEnterWhileEmbeddingHostLeaseIsHeld() throws Exception {
        try (Context host = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            host.parse(Source.newBuilder(OresLanguage.ID,
                    "pub fnc main(): int { return 1; }", "real-gate-root.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            host.initialize(OresLanguage.ID);
            OresContext owner = OresContext.forExactHostValueContext(host);
            var entered = new CountDownLatch(1);
            var turns = new AtomicInteger();
            OresFuture<Integer> future;
            try (OresContext.HostEntryLease scope = owner.acquireScopedHostEntry()) {
                future = owner.vm().rootScheduler().startSync(() -> {
                    turns.incrementAndGet();
                    entered.countDown();
                    return 5;
                });
                assertFalse(entered.await(150, TimeUnit.MILLISECONDS),
                        "root TruffleContext.enter must wait for host to exit");
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(5, future.get(5, TimeUnit.SECONDS));
            assertEquals(1, turns.get());
        }
    }

    @Test
    void existingRealControlTurnMustPhysicallyExitBeforeHostValueExecute()
            throws Exception {
        try (Context host = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value source = host.parse(Source.newBuilder(OresLanguage.ID,
                    "pub async fnc work(): int { return 9; }", "already-running-root.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            host.initialize(OresLanguage.ID);
            OresContext owner = OresContext.forExactHostValueContext(host);
            CountDownLatch physicalGuestEntered = new CountDownLatch(1);
            CountDownLatch releaseGuest = new CountDownLatch(1);
            AtomicBoolean hostWaitObserved = new AtomicBoolean();
            Thread callingHost = Thread.currentThread();
            var oldTurn = owner.vm().rootScheduler().startSync(() -> {
                physicalGuestEntered.countDown();
                if (!releaseGuest.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("guest release was not observed");
                }
                return 3;
            });
            assertTrue(physicalGuestEntered.await(5, TimeUnit.SECONDS));
            Thread releaseThread = Thread.ofPlatform().start(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                try {
                    while (System.nanoTime() < deadline) {
                        Thread.State state = callingHost.getState();
                        if (state == Thread.State.WAITING
                                || state == Thread.State.TIMED_WAITING) {
                            hostWaitObserved.set(true);
                            return;
                        }
                        Thread.onSpinWait();
                    }
                } finally {
                    releaseGuest.countDown();
                }
            });
            try {
                OresFuture<Number> next =
                        OresHostFutureBridge.invokePublicFuture(source, "work");
                assertEquals(9L, next.get(5, TimeUnit.SECONDS).longValue());
                assertEquals(3, oldTurn.get(5, TimeUnit.SECONDS));
                assertTrue(hostWaitObserved.get(),
                        "host must wait for the already-entered physical root turn");
            } finally {
                releaseGuest.countDown();
                releaseThread.join(5_000);
                assertFalse(releaseThread.isAlive());
            }
        }
    }

    @Test
    void multipleContextsOnOneHostThreadPreserveExactValueOwnership() throws Exception {
        try (Context first = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            first.parse(Source.newBuilder(OresLanguage.ID,
                    "pub fnc main(): int { return 1; }", "one.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            first.initialize(OresLanguage.ID);
            OresContext firstOwner = OresContext.forExactHostValueContext(first);
            try (Context second = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(true).build()) {
                second.parse(Source.newBuilder(OresLanguage.ID,
                        "pub fnc main(): int { return 2; }", "two.ores")
                        .mimeType(OresLanguage.MIME_TYPE).build());
                second.initialize(OresLanguage.ID);
                assertThrows(SecurityException.class,
                        OresContext::singleHostOwnedContext,
                        "unqualified thread-only selection must remain unavailable");
                OresContext secondOwner = OresContext.forExactHostValueContext(second);
                assertNotSame(firstOwner, secondOwner);
                try (OresContext.HostEntryLease firstLease =
                             firstOwner.acquireScopedHostEntry();
                     OresContext.HostEntryLease secondLease =
                             secondOwner.acquireScopedHostEntry()) {
                    assertNotNull(firstLease);
                    assertNotNull(secondLease);
                }
            }
            assertSame(firstOwner, OresContext.forExactHostValueContext(first));
            try (OresContext.HostEntryLease lease = firstOwner.acquireScopedHostEntry()) {
                assertNotNull(lease);
            }
        }
    }
}
