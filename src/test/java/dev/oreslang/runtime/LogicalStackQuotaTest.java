package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class LogicalStackQuotaTest {
    /** Run on a fresh Oreslang-style carrier with a known stack, isolated from other tests. */
    private static void onCarrier(Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread carrier = new Thread(null,
                LogicalStackQuota.carrierBody(LogicalStackQuota.CARRIER_STACK_BYTES, () -> {
                    try {
                        body.run();
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                }),
                "logical-stack-test-carrier", LogicalStackQuota.CARRIER_STACK_BYTES);
        carrier.start();
        carrier.join();
        Throwable t = failure.get();
        if (t instanceof Error error) throw error;
        if (t instanceof Exception exception) throw exception;
    }

    @Test
    void segmentQuotaIsIndependentOfHowDeepTheCarrierAlreadyIs() throws Exception { onCarrier(() -> {
        int outer = 40;
        for (int i = 0; i < outer; i++) LogicalStackQuota.pushFrame();
        try (LogicalStackQuota.Segment inner = LogicalStackQuota.enter("inner", 8)) {
            for (int i = 0; i < 8; i++) LogicalStackQuota.pushFrame();
            LogicalStackOverflowException overflow =
                    assertThrows(LogicalStackOverflowException.class, LogicalStackQuota::pushFrame);
            assertFalse(overflow.carrierCeiling());
            assertEquals(8, overflow.quota());
            assertTrue(inner.poisoned());
            assertSame(overflow, inner.overflowFailure());
            for (int i = 0; i < 8; i++) LogicalStackQuota.popFrame();
            assertEquals(8, inner.peakDepth());
        }
        assertEquals(outer, LogicalStackQuota.carrierDepth(), "inner segment must not leak frames");
        assertFalse(LogicalStackQuota.current().poisoned(), "an inner overflow does not poison the caller");
        for (int i = 0; i < outer; i++) LogicalStackQuota.popFrame();
        assertNull(LogicalStackQuota.current(), "implicit root unbinds once empty");
    }); }

    @Test
    void poisonedSegmentGrantsOnlyTheBoundedUnwindReserve() throws Exception { onCarrier(() -> {
        try (LogicalStackQuota.Segment segment = LogicalStackQuota.enter("reserve", 4)) {
            for (int i = 0; i < 4; i++) LogicalStackQuota.pushFrame();
            LogicalStackOverflowException first =
                    assertThrows(LogicalStackOverflowException.class, LogicalStackQuota::pushFrame);
            int granted = 0;
            while (true) {
                try {
                    LogicalStackQuota.pushFrame();
                    granted++;
                } catch (LogicalStackOverflowException exhausted) {
                    assertNotSame(first, exhausted);
                    break;
                }
            }
            assertEquals(LogicalStackQuota.UNWIND_RESERVE_FRAMES, granted);
            assertEquals(1, first.getSuppressed().length, "reserve exhaustion is recorded on the original overflow");
            assertSame(first, segment.overflowFailure(), "the first overflow stays authoritative");
            for (int i = 0; i < 4 + granted; i++) LogicalStackQuota.popFrame();
        }
        assertEquals(0, LogicalStackQuota.carrierDepth());
    }); }

    @Test
    void closingWithLiveFramesFailsClosedAndRestoresTheCarrier() throws Exception { onCarrier(() -> {
        LogicalStackQuota.Segment leaky = LogicalStackQuota.enter("leaky", 16);
        LogicalStackQuota.pushFrame();
        IllegalStateException failure = assertThrows(IllegalStateException.class, leaky::close);
        assertTrue(failure.getMessage().contains("1 live guest frames"));
        assertEquals(0, LogicalStackQuota.carrierDepth());
        assertNull(LogicalStackQuota.current());
    }); }

    @Test
    void segmentsMustCloseInnermostFirstAndOnTheirOwnCarrier() throws Exception { onCarrier(() -> {
        LogicalStackQuota.Segment outer = LogicalStackQuota.enter("outer", 16);
        LogicalStackQuota.Segment inner = LogicalStackQuota.enter("inner", 16);
        assertThrows(IllegalStateException.class, outer::close);
        assertSame(inner, LogicalStackQuota.current(), "a rejected close changes nothing");
        AtomicReference<Throwable> foreign = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                inner.close();
            } catch (Throwable t) {
                foreign.set(t);
            }
        });
        other.start();
        try {
            other.join();
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
        assertInstanceOf(IllegalStateException.class, foreign.get());
        inner.close();
        assertSame(outer, LogicalStackQuota.current());
        outer.close();
        assertNull(LogicalStackQuota.current(), "rejected closes must not strand a segment");
    }); }

    @Test
    void recoverCallbacksCannotConsumeStackExhaustion() {
        // Panic-class: neither on_throw nor on_raise may resolve an overflow,
        // whichever effect kind the compiler attached to it.
        CodeImageIdentity image = CodeImageIdentity.issue("logical-stack-recover-test", 1);
        ExecutionContext context = ExecutionContext.actor(19, 31, 7, 131, 1, 0, 0);
        AtomicInteger invoked = new AtomicInteger();
        var chain = RecoveryHandlerChain.<Integer>builder(context, image)
                .onThrow((e, cursor) -> { invoked.incrementAndGet(); cursor.resolve(1); })
                .onRaise((e, cursor) -> { invoked.incrementAndGet(); cursor.resolve(2); })
                .freeze();
        for (var kind : new RecoveryHandlerChain.FailureKind[] {
                RecoveryHandlerChain.FailureKind.THROW, RecoveryHandlerChain.FailureKind.RAISE }) {
            var overflow = new LogicalStackOverflowException("actor-131", 1024, 1024, false);
            GuestFailure signal = GuestFailure.issueFromCompiler(kind, overflow, context, image, 1);
            var unhandled = assertInstanceOf(RecoveryHandlerChain.Unhandled.class, chain.dispatch(signal));
            assertSame(overflow, unhandled.error());
        }
        assertEquals(0, invoked.get(), "no recover callback may run for stack exhaustion");
    }

    @Test
    void popWithoutPushIsAnInvariantFailure() throws Exception {
        onCarrier(() -> assertThrows(IllegalStateException.class, LogicalStackQuota::popFrame));
    }

    @Test
    void carrierCeilingScalesWithTheRealStackSizeAndCoversTheDefaultQuota() {
        int small = LogicalStackQuota.carrierFramesFor(2L * 1024 * 1024);
        int large = LogicalStackQuota.carrierFramesFor(LogicalStackQuota.CARRIER_STACK_BYTES);
        assertTrue(small < large);
        assertTrue(large >= LogicalStackQuota.DEFAULT_SEGMENT_FRAMES,
                "Oreslang carriers must fit a whole default receive stack, so overflow depth is carrier-independent");
        assertEquals(16, LogicalStackQuota.carrierFramesFor(1), "tiny stacks still get a bounded positive ceiling");
    }

    @Test
    void carrierBodyBindsTheDeclaredStackSize() throws Exception {
        AtomicInteger ceiling = new AtomicInteger();
        long bytes = 4L * 1024 * 1024;
        Thread carrier = new Thread(null,
                LogicalStackQuota.carrierBody(bytes, () -> ceiling.set(LogicalStackQuota.carrierFrames())),
                "logical-stack-carrier", bytes);
        carrier.start();
        carrier.join();
        assertEquals(LogicalStackQuota.carrierFramesFor(bytes), ceiling.get());
    }

    @Test
    void swallowedOverflowStillFailsTheActorAndTheSharedCarrierServesTheSibling() throws Exception {
        // One shared lane: both actors run on the same carrier thread, so the
        // sibling's reply proves the carrier survived the overflow.
        var config = new ActorRuntime.DispatcherConfig(1, 1, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<Thread> overflowCarrier = new AtomicReference<>();
            AtomicReference<Thread> siblingCarrier = new AtomicReference<>();
            CountDownLatch siblingRan = new CountDownLatch(1);

            var diver = runtime.<Integer>spawnShared(() -> (message, context) -> {
                overflowCarrier.set(Thread.currentThread());
                int pushed = 0;
                try {
                    while (true) {
                        LogicalStackQuota.pushFrame();
                        pushed++;
                    }
                } catch (LogicalStackOverflowException swallowed) {
                    // Trusted host code hides the failure; the poisoned
                    // receive stack must still fail the incarnation.
                } finally {
                    for (int i = 0; i < pushed; i++) LogicalStackQuota.popFrame();
                }
            });
            diver.send(1);
            assertTrue(diver.awaitTermination(10, TimeUnit.SECONDS));
            assertInstanceOf(LogicalStackOverflowException.class, diver.failure().orElseThrow());

            var sibling = runtime.<Integer>spawnShared(() -> (message, context) -> {
                siblingCarrier.set(Thread.currentThread());
                assertEquals(0, LogicalStackQuota.carrierDepth(), "sibling starts on a clean carrier");
                siblingRan.countDown();
                context.self().stop();
            });
            sibling.send(2);
            assertTrue(siblingRan.await(10, TimeUnit.SECONDS));
            assertTrue(sibling.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty(), () -> String.valueOf(sibling.failure()));
            assertSame(overflowCarrier.get(), siblingCarrier.get(), "both turns used the single shared carrier");
            assertTrue(overflowCarrier.get().isAlive(), "the carrier thread survived the overflow");
        }
    }
}
