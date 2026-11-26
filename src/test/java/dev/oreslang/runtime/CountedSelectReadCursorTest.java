package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CountedSelectReadCursorTest {
    private static CountedSelectReadCursor cursor(int count, ChannelRuntime.Channel<Long> ch) {
        return new CountedSelectReadCursor(count, List.of(ch.output()), ChannelRuntime.SelectPolicy.FAIR);
    }
    @Test void exactThreeFairRoundsNeverConsumeFourth() {
        var a = new ChannelRuntime.Channel<Long>(2);
        var b = new ChannelRuntime.Channel<Long>(2);
        assertTrue(a.tryWrite(10L)); assertTrue(a.tryWrite(30L));
        assertTrue(b.tryWrite(20L)); assertTrue(b.tryWrite(40L));
        var c = new CountedSelectReadCursor(3, List.of(a.output(), b.output()), ChannelRuntime.SelectPolicy.FAIR);
        for (int i = 0; i < 3; i++) {
            assertTrue(c.beginRound().isDone());
            assertThrows(IllegalStateException.class, c::beginRound);
            assertTrue(c.retireRound());
        }
        assertEquals(List.of(10L,20L,30L), c.result());
        assertEquals(40L, b.tryRead().orElseThrow());
        assertFalse(c.cancel());
        assertThrows(IllegalStateException.class, c::beginRound);
    }
    @Test void zeroAndInvalidCountDoNotRegisterOrConsume() {
        var ch = new ChannelRuntime.Channel<Long>(1);
        assertTrue(ch.tryWrite(42L));
        assertEquals(List.of(), cursor(0,ch).result());
        assertThrows(IllegalArgumentException.class, () -> cursor(-1,ch));
        assertThrows(IllegalArgumentException.class, () -> cursor(1025,ch));
        assertEquals(0,ch.pendingRegistrations());
        assertEquals(42L,ch.tryRead().orElseThrow());
    }
    @Test void pendingRoundUsesActualChannelRegistrationAndResumes() {
        var ch = new ChannelRuntime.Channel<Long>(1);
        var c = cursor(2,ch);
        var f = c.beginRound();
        assertFalse(f.isDone());
        assertFalse(c.retireRound());
        assertEquals(1,ch.pendingRegistrations());
        assertTrue(ch.tryWrite(21L));
        assertTrue(c.retireRound());
        assertEquals(0,ch.pendingRegistrations());
        assertTrue(ch.tryWrite(42L));
        c.beginRound();
        assertTrue(c.retireRound());
        assertEquals(List.of(21L,42L),c.result());
    }
    @Test void cancellationBeforeCommitDetachesAndPreservesNextMessage() {
        var ch = new ChannelRuntime.Channel<Long>(1);
        var c = cursor(2,ch);
        var f = c.beginRound();
        assertTrue(c.cancel());
        assertTrue(f.isCancelled());
        assertTrue(c.retireRound());
        assertEquals(CountedSelectReadCursor.State.CANCELLED,c.state());
        assertEquals(0,ch.pendingRegistrations());
        assertTrue(ch.tryWrite(99L));
        assertEquals(99L,ch.tryRead().orElseThrow());
        assertThrows(IllegalStateException.class,c::result);
    }
    @Test void cancellationAfterCommitDoesNotReadAgainOrPublishPrefix() {
        var ch = new ChannelRuntime.Channel<Long>(2);
        assertTrue(ch.tryWrite(1L)); assertTrue(ch.tryWrite(2L));
        var c = cursor(2,ch);
        var f = c.beginRound();
        assertTrue(f.isDone());
        assertTrue(c.cancel());
        assertTrue(c.retireRound());
        assertEquals(CountedSelectReadCursor.State.CANCELLED,c.state());
        assertEquals(2L,ch.tryRead().orElseThrow());
        assertThrows(IllegalStateException.class,c::result);
    }
    @Test void closeAfterPrefixFailsWithoutShortArray() {
        var ch = new ChannelRuntime.Channel<Long>(1);
        var c = cursor(2,ch);
        assertTrue(ch.tryWrite(1L)); c.beginRound(); assertTrue(c.retireRound());
        c.beginRound(); ch.close(); assertTrue(c.retireRound());
        assertEquals(CountedSelectReadCursor.State.FAILED,c.state());
        assertInstanceOf(CountedSelectReadCursor.SelectExhausted.class,c.failure());
        assertThrows(IllegalStateException.class,c::result);
        assertEquals(0,ch.pendingRegistrations());
    }
    @Test void gracefulEofRetiresAliasesButKeepsOtherLiveChannel() {
        var a = new ChannelRuntime.Channel<Long>(1);
        var b = new ChannelRuntime.Channel<Long>(2);
        a.close();
        assertTrue(b.tryWrite(21L)); assertTrue(b.tryWrite(42L));
        var c = new CountedSelectReadCursor(2, List.of(a.output(),a.output(),b.output()), ChannelRuntime.SelectPolicy.FAIR);
        c.beginRound(); assertTrue(c.retireRound()); // EOF, no value
        assertEquals(CountedSelectReadCursor.State.READY,c.state());
        c.beginRound(); assertTrue(c.retireRound());
        c.beginRound(); assertTrue(c.retireRound());
        assertEquals(List.of(21L,42L),c.result());
        assertEquals(0,a.pendingRegistrations());
        assertEquals(0,b.pendingRegistrations());
    }
    @Test void closedBufferedChannelDrainsBeforeEof() {
        var a = new ChannelRuntime.Channel<Long>(1);
        var b = new ChannelRuntime.Channel<Long>(1);
        assertTrue(a.tryWrite(1L)); a.close();
        assertTrue(b.tryWrite(2L));
        var c = new CountedSelectReadCursor(2,List.of(a.output(),b.output()),ChannelRuntime.SelectPolicy.PRIORITY);
        c.beginRound(); assertTrue(c.retireRound()); // 1
        c.beginRound(); assertTrue(c.retireRound()); // EOF
        c.beginRound(); assertTrue(c.retireRound()); // 2
        assertEquals(List.of(1L,2L),c.result());
    }
    @Test void oneShotFutureAliasesRetireAndDoNotDuplicateResult() {
        var f = OresFuture.completed(21L);
        var g = new OresFuture<Long>();
        var c = new CountedSelectReadCursor(2,List.of(),List.of(f,f,g),ChannelRuntime.SelectPolicy.FAIR);
        c.beginRound(); assertTrue(c.retireRound());
        var round = c.beginRound();
        assertFalse(round.isDone());
        assertEquals(1,g.pendingRuntimeWaiterCount());
        assertTrue(g.completeFromRuntime(42L));
        assertTrue(c.retireRound());
        assertEquals(List.of(21L,42L),c.result());
        assertEquals(0,g.pendingRuntimeWaiterCount());
    }
    @Test void exhaustedFuturesDoNotRepeatOrPublishPartialBatch() {
        var f = OresFuture.completed(1L);
        var c = new CountedSelectReadCursor(2,List.of(),List.of(f,f),ChannelRuntime.SelectPolicy.FAIR);
        c.beginRound(); assertTrue(c.retireRound());
        assertInstanceOf(CountedSelectReadCursor.SelectExhausted.class,c.failure());
        assertThrows(IllegalStateException.class,c::result);
    }
    @Test void errorCloseIsNotGracefulEofAndOrdinarySelectIsUnchanged() {
        var a = new ChannelRuntime.Channel<Long>(1);
        var b = new ChannelRuntime.Channel<Long>(1);
        var cause = new IllegalStateException("producer failed");
        a.close(cause); assertTrue(b.tryWrite(42L));
        var c = new CountedSelectReadCursor(1,List.of(a.output(),b.output()),ChannelRuntime.SelectPolicy.FAIR);
        c.beginRound(); assertTrue(c.retireRound());
        assertSame(cause,c.failure().getCause());
        assertEquals(42L,b.tryRead().orElseThrow());
        b.close();
        var failed = ChannelRuntime.SelectSet.of(ChannelRuntime.read(b)).selectAsync();
        assertThrows(java.util.concurrent.CompletionException.class,failed::join);
    }
    @Test void cancellationDetachesPendingFutureWithoutCancellingProducer() {
        var f = new OresFuture<Long>();
        var c = new CountedSelectReadCursor(1,List.of(),List.of(f),ChannelRuntime.SelectPolicy.FAIR);
        c.beginRound();
        assertTrue(c.cancel()); assertTrue(c.retireRound());
        assertEquals(0,f.pendingRuntimeWaiterCount());
        assertFalse(f.isDone());
        assertTrue(f.completeFromRuntime(42L));
    }
    @Test void mixedLexicalPriorityDoesNotGroupChannelsBeforeFutures() {
        var channel = new ChannelRuntime.Channel<Long>(2);
        assertTrue(channel.tryWrite(20L));
        assertTrue(channel.tryWrite(30L));
        var f = OresFuture.completed(10L);
        var c = CountedSelectReadCursor.ordered(2,
                List.of(ChannelRuntime.await(f), ChannelRuntime.read(channel.output())),
                ChannelRuntime.SelectPolicy.PRIORITY);
        c.beginRound(); assertTrue(c.retireRound());
        c.beginRound(); assertTrue(c.retireRound());
        assertEquals(List.of(10L,20L), c.result());
        assertEquals(30L, channel.tryRead().orElseThrow());
    }

    @Test void mixedFairOrderSurvivesFutureAliasRetirement() {
        var a = new ChannelRuntime.Channel<Long>(2);
        var b = new ChannelRuntime.Channel<Long>(2);
        assertTrue(a.tryWrite(10L)); assertTrue(a.tryWrite(40L));
        assertTrue(b.tryWrite(30L)); assertTrue(b.tryWrite(50L));
        var f = OresFuture.completed(20L);
        var c = CountedSelectReadCursor.ordered(4,
                List.of(ChannelRuntime.read(a.output()), ChannelRuntime.await(f),
                        ChannelRuntime.read(b.output()), ChannelRuntime.await(f)),
                ChannelRuntime.SelectPolicy.FAIR);
        for (int i = 0; i < 4; i++) {
            c.beginRound(); assertTrue(c.retireRound());
        }
        assertEquals(List.of(10L,20L,30L,40L), c.result());
        assertEquals(50L, b.tryRead().orElseThrow());
        assertEquals(0, f.pendingRuntimeWaiterCount());
    }

    @Test void orderedCasesRejectUnsupportedOperationsBeforeRegistration() {
        var channel = new ChannelRuntime.Channel<Long>(1);
        assertThrows(IllegalArgumentException.class, () -> CountedSelectReadCursor.ordered(1,
                List.of(ChannelRuntime.read(channel.output()), ChannelRuntime.defaultCase()),
                ChannelRuntime.SelectPolicy.FAIR));
        assertEquals(0, channel.pendingRegistrations());
        assertEquals(List.of(), CountedSelectReadCursor.ordered(0, null,
                ChannelRuntime.SelectPolicy.FAIR).result());
    }

    @Test void aggregateFuturePublishesOnlyAfterExactCardinalityAndRetirement() {
        var ch = new ChannelRuntime.Channel<Long>(4);
        ch.tryWrite(10L); ch.tryWrite(20L); ch.tryWrite(30L); ch.tryWrite(40L);
        var b = new CountedSelectBatch(3, List.of(ChannelRuntime.read(ch.output())),
                ChannelRuntime.SelectPolicy.FAIR);
        var result = b.completion();
        for (int i = 0; i < 3; i++) {
            assertTrue(b.pump());
            assertFalse(result.isDone(), "registration/commit is not batch settlement");
            assertTrue(b.pump());
            assertEquals(i == 2, result.isDone());
        }
        assertEquals(List.of(10L,20L,30L), result.join());
        assertThrows(UnsupportedOperationException.class, () -> result.join().add(99L));
        assertEquals(40L, ch.tryRead().orElseThrow());
        assertEquals(0, ch.pendingRegistrations());
        assertFalse(result.cancel(false));
        assertFalse(b.pump());
    }

    @Test void aggregateCancellationDetachesAndNeverPublishesPrefix() {
        var ch = new ChannelRuntime.Channel<Long>(2);
        ch.tryWrite(10L);
        var b = new CountedSelectBatch(2, List.of(ChannelRuntime.read(ch.output())),
                ChannelRuntime.SelectPolicy.FAIR);
        assertTrue(b.pump()); assertTrue(b.pump());
        assertTrue(b.pump()); assertFalse(b.pump());
        assertEquals(1, ch.pendingRegistrations());
        var f = b.completion();
        assertTrue(f.cancel(false));
        while (!f.isDone()) assertTrue(b.pump());
        assertTrue(f.isCancelled());
        assertEquals(0, ch.pendingRegistrations());
        ch.tryWrite(42L); assertEquals(42L, ch.tryRead().orElseThrow());
        assertFalse(b.pump());
    }

    @Test void aggregateCancellationAfterCommitNeverConsumesAnotherMessage() {
        var ch = new ChannelRuntime.Channel<Long>(2);
        ch.tryWrite(10L); ch.tryWrite(20L);
        var b = new CountedSelectBatch(2,List.of(ChannelRuntime.read(ch.output())),
                ChannelRuntime.SelectPolicy.FAIR);
        assertTrue(b.pump());
        assertTrue(b.completion().cancel(false));
        while (!b.completion().isDone()) assertTrue(b.pump());
        assertTrue(b.completion().isCancelled());
        assertEquals(20L, ch.tryRead().orElseThrow());
    }

    @Test void aggregateZeroAndExhaustionHaveDistinctTerminalResults() {
        var zero = new CountedSelectBatch(0,null,ChannelRuntime.SelectPolicy.FAIR);
        assertEquals(List.of(),zero.completion().join());
        var f = OresFuture.completed(10L);
        var b = new CountedSelectBatch(2,List.of(ChannelRuntime.await(f),ChannelRuntime.await(f)),
                ChannelRuntime.SelectPolicy.FAIR);
        assertTrue(b.pump()); assertTrue(b.pump());
        assertTrue(b.completion().isCompletedExceptionally());
        assertInstanceOf(CountedSelectReadCursor.SelectExhausted.class,
                assertThrows(java.util.concurrent.CompletionException.class,
                        b.completion()::join).getCause());
        assertEquals(0, f.pendingRuntimeWaiterCount());
    }

    @Test void aggregateForeignOwnerCannotCancelOrPump() throws Exception {
        var ch = new ChannelRuntime.Channel<Long>(1);
        var b = new CountedSelectBatch(1,List.of(ChannelRuntime.read(ch.output())),
                ChannelRuntime.SelectPolicy.FAIR);
        var observed = new AtomicReference<Throwable>();
        var foreign = Thread.startVirtualThread(() -> {
            try {
                assertFalse(b.completion().cancel(false));
                b.pump();
            } catch (Throwable error) { observed.set(error); }
        });
        foreign.join();
        assertInstanceOf(IllegalStateException.class, observed.get());
        assertFalse(b.completion().isDone());
        assertEquals(0,ch.pendingRegistrations());
        assertTrue(b.completion().cancel(false));
        assertTrue(b.completion().isCancelled());
    }

    @Test void foreignThreadCannotDriveOwnerCursor() throws Exception {
        var ch = new ChannelRuntime.Channel<Long>(1);
        var c = cursor(1,ch);
        var error = new AtomicReference<Throwable>();
        var t = Thread.startVirtualThread(() -> {
            try { c.beginRound(); } catch (Throwable failure) { error.set(failure); }
        });
        t.join();
        assertInstanceOf(IllegalStateException.class,error.get());
        assertEquals(0,ch.pendingRegistrations());
        assertTrue(c.cancel());
    }
}
