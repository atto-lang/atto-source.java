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
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,c.failure());
        assertThrows(IllegalStateException.class,c::result);
        assertEquals(0,ch.pendingRegistrations());
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
