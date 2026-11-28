package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A failing internal publication/cleanup hook must not strand independent
 * selections that were irrevocably committed under the channel coordinator.
 */
final class ChannelCommittedCompletionDrainTest {
    private static void runCompletions(List<Runnable> completions) throws Exception {
        Method method = ChannelRuntime.class.getDeclaredMethod(
                "runCompletions", List.class);
        method.setAccessible(true);
        try {
            method.invoke(null, completions);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException failure) throw failure;
            if (exception.getCause() instanceof Error failure) throw failure;
            throw exception;
        }
    }

    /** Invoke the ordering/failure-composition helper without widening runtime API. */
    private static void runAfterPreservingFailure(
            Throwable primary, Runnable action) throws Exception {
        Method method = ChannelRuntime.class.getDeclaredMethod(
                "runAfterPreservingFailure", Throwable.class, Runnable.class);
        method.setAccessible(true);
        try {
            method.invoke(null, primary, action);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException failure) throw failure;
            if (exception.getCause() instanceof Error failure) throw failure;
            throw exception;
        }
    }

    @Test
    void mailboxConsumerFailureStillPublishesCommittedWaitingWriter()
            throws Exception {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite(5));
        OresFuture<Void> waitingWriter = channel.writeAsync(6);
        assertFalse(waitingWriter.isDone(), "the buffer is still full");
        IllegalStateException primary = new IllegalStateException("consumer rejected value");

        assertSame(primary, assertThrows(IllegalStateException.class,
                () -> channel.drainOne(value -> {
                    assertEquals(5, value);
                    throw primary;
                })));

        assertNull(waitingWriter.get(5, TimeUnit.SECONDS),
                "an already-admitted writer must receive its terminal publication");
        assertEquals(6, channel.tryRead().orElseThrow());
        assertEquals(0, channel.pendingRegistrations());
    }

    @Test
    void secondaryFailureIsSuppressedWithoutMaskingThePrimary() throws Exception {
        IllegalStateException primary = new IllegalStateException("consumer failed");
        AssertionError late = new AssertionError("notification failed");
        AtomicInteger ran = new AtomicInteger();
        runAfterPreservingFailure(primary, () -> {
            ran.incrementAndGet();
            throw late;
        });
        assertEquals(1, ran.get(), "the required follow-up must execute");
        assertEquals(1, primary.getSuppressed().length);
        assertSame(late, primary.getSuppressed()[0]);
    }

    @Test
    void followUpFailurePropagatesWhenThereWasNoPrimary() throws Exception {
        AssertionError late = new AssertionError("notification failed");
        assertSame(late, assertThrows(AssertionError.class,
                () -> runAfterPreservingFailure(null, () -> { throw late; })));
    }

    @Test
    void continuesAllCommittedPublicationsAndPreservesOriginalFailure()
            throws Exception {
        var first = new OresFuture<Integer>();
        var second = new OresFuture<Integer>();
        AtomicInteger callbacks = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("bad hook");
        AssertionError secondary = new AssertionError("bad second hook");

        List<Runnable> queued = new ArrayList<>();
        queued.add(() -> {
            callbacks.incrementAndGet();
            throw failure;
        });
        queued.add(() -> {
            callbacks.incrementAndGet();
            assertTrue(first.completeFromRuntime(17));
        });
        queued.add(() -> {
            callbacks.incrementAndGet();
            throw secondary;
        });
        queued.add(() -> {
            callbacks.incrementAndGet();
            assertTrue(second.completeFromRuntime(29));
        });

        IllegalStateException observed = assertThrows(
                IllegalStateException.class, () -> runCompletions(queued));
        assertSame(failure, observed);
        assertEquals(1, observed.getSuppressed().length);
        assertSame(secondary, observed.getSuppressed()[0]);
        assertEquals(4, callbacks.get(), "no irrevocable completion may be lost");
        assertEquals(17, first.get(5, TimeUnit.SECONDS));
        assertEquals(29, second.get(5, TimeUnit.SECONDS));
    }

    @Test
    void normalCompletionsPreserveOriginalOrderAndRunExactlyOnce() throws Exception {
        var order = new ArrayList<Integer>();
        runCompletions(List.of(
                () -> order.add(1),
                () -> order.add(2),
                () -> order.add(3)));
        assertEquals(List.of(1, 2, 3), order);
    }

    @Test
    void firstErrorIsPreservedWithLaterRuntimeFailureSuppressed() throws Exception {
        AssertionError fatal = new AssertionError("first");
        IllegalArgumentException later = new IllegalArgumentException("second");
        AtomicInteger delivered = new AtomicInteger();
        AssertionError observed = assertThrows(AssertionError.class,
                () -> runCompletions(List.of(
                        () -> { throw fatal; },
                        () -> { throw later; },
                        delivered::incrementAndGet)));
        assertSame(fatal, observed);
        assertEquals(1, observed.getSuppressed().length);
        assertSame(later, observed.getSuppressed()[0]);
        assertEquals(1, delivered.get());
    }
}
