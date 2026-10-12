package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class ChannelSharedFairCursorTest {

    private static ChannelRuntime.SelectSet select(
            ChannelRuntime.Channel<Integer> a,
            ChannelRuntime.Channel<Integer> b,
            ChannelRuntime.FairCursor cursor) {
        return new ChannelRuntime.SelectSet(
                List.of(ChannelRuntime.read(a), ChannelRuntime.read(b)), cursor);
    }

    @Test
    void freshSetsShareOnlyCommittedCreditAcrossDefaultAndCancellation() {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> a = new ChannelRuntime.Channel<>(2);
        ChannelRuntime.Channel<Integer> b = new ChannelRuntime.Channel<>(2);

        assertEquals(2, new ChannelRuntime.SelectSet(
                List.of(ChannelRuntime.read(a), ChannelRuntime.read(b),
                        ChannelRuntime.defaultCase()), cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());

        OresFuture<ChannelRuntime.SelectResult> cancelled =
                select(a, b, cursor).selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertTrue(cancelled.cancel(false));
        assertTrue(a.tryWrite(1));
        assertTrue(b.tryWrite(2));

        assertEquals(0, select(a, b, cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());
        assertTrue(a.tryWrite(3));
        assertEquals(1, select(a, b, cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());
    }

    @Test
    void pendingSelectResamplesCommittedCursorRatherThanStaleAdmissionOrder() {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> pendingChannel = new ChannelRuntime.Channel<>(1);
        OresFuture<ChannelRuntime.SelectResult> pending =
                select(pendingChannel, pendingChannel, cursor)
                        .selectAsync(ChannelRuntime.SelectPolicy.FAIR);
        assertFalse(pending.isDone());

        ChannelRuntime.Channel<Integer> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> second = new ChannelRuntime.Channel<>(1);
        assertTrue(first.tryWrite(5));
        assertTrue(second.tryWrite(6));
        assertEquals(0, select(first, second, cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());

        assertTrue(pendingChannel.tryWrite(17));
        assertEquals(1, pending.join().index(),
                "pending FAIR select must see credit advanced after admission");
        assertEquals(17, pending.join().value());
    }

    @Test
    void retiredCursorCannotBeResurrectedByLaterCommit() throws Exception {
        ChannelRuntime.FairCursor cursor = new ChannelRuntime.FairCursor();
        ChannelRuntime.Channel<Integer> a = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<Integer> b = new ChannelRuntime.Channel<>(1);

        cursor.retire();
        assertTrue(a.tryWrite(7));
        assertEquals(0, select(a, b, cursor)
                .selectAsync(ChannelRuntime.SelectPolicy.FAIR).join().index());

        Field indexField = ChannelRuntime.FairCursor.class.getDeclaredField("index");
        indexField.setAccessible(true);
        assertEquals(Long.MIN_VALUE, ((AtomicLong) indexField.get(cursor)).get(),
                "a late coordinator callback cannot unretire actor fairness state");
    }
}
