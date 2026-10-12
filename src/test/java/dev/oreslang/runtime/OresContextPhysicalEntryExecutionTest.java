package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
            OresContext owner = OresContext.singleHostOwnedContext();
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
    void multipleContextsOnOneHostThreadRejectAmbiguousEntry() throws Exception {
        try (Context first = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            first.parse(Source.newBuilder(OresLanguage.ID,
                    "pub fnc main(): int { return 1; }", "one.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            OresContext firstOwner = OresContext.singleHostOwnedContext();
            try (Context second = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(true).build()) {
                second.parse(Source.newBuilder(OresLanguage.ID,
                        "pub fnc main(): int { return 2; }", "two.ores")
                        .mimeType(OresLanguage.MIME_TYPE).build());
                assertThrows(SecurityException.class,
                        OresContext::singleHostOwnedContext);
                assertThrows(SecurityException.class,
                        firstOwner::acquireScopedHostEntry);
            }
            assertSame(firstOwner, OresContext.singleHostOwnedContext());
            try (OresContext.HostEntryLease lease = firstOwner.acquireScopedHostEntry()) {
                assertNotNull(lease);
            }
        }
    }
}
