package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A committed channel operation cannot be stranded by a different operation's
 * failed completion callback. Exercise the queue-drain contract directly so
 * the test does not depend on probabilistic callback interleavings.
 */
final class ChannelCompletionPublicationIsolationTest {
    private static Method method(String name, Class<?>... args) throws Exception {
        Method target = ChannelRuntime.class.getDeclaredMethod(name, args);
        target.setAccessible(true);
        return target;
    }

    @Test
    void laterCommittedCompletionsStillRunAndFailuresRetainOrder() throws Exception {
        Method run = method("runCompletions", List.class);
        AtomicInteger successfulCompletions = new AtomicInteger();
        IllegalStateException first = new IllegalStateException("first notification failed");
        AssertionError second = new AssertionError("second notification failed");

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> run.invoke(null, List.<Runnable>of(
                        () -> { throw first; },
                        successfulCompletions::incrementAndGet,
                        () -> { throw second; },
                        successfulCompletions::incrementAndGet)));

        assertSame(first, wrapped.getCause(), "the first failure must remain primary");
        assertArrayEquals(new Throwable[] { second }, first.getSuppressed());
        assertEquals(2, successfulCompletions.get(),
                "no already-committed completion may be skipped");
    }

    @Test
    void repeatedSameThrowableDoesNotAttemptSelfSuppression() throws Exception {
        Method run = method("runCompletions", List.class);
        AssertionError shared = new AssertionError("shared failure");
        AtomicInteger last = new AtomicInteger();

        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> run.invoke(null, List.<Runnable>of(
                        () -> { throw shared; },
                        () -> { throw shared; },
                        last::incrementAndGet)));

        assertSame(shared, wrapped.getCause());
        assertEquals(0, shared.getSuppressed().length);
        assertEquals(1, last.get());
    }

    @Test
    void previouslyThrownConsumerFailureRemainsPrimaryDuringCleanup() throws Exception {
        Method preserve = method("runAfterPreservingFailure", Throwable.class, Runnable.class);
        IllegalStateException primary = new IllegalStateException("mailbox consumer");
        AssertionError cleanup = new AssertionError("committed completion");
        AtomicInteger calls = new AtomicInteger();

        preserve.invoke(null, primary, (Runnable) () -> {
            calls.incrementAndGet();
            throw cleanup;
        });

        assertEquals(1, calls.get());
        assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
    }

    @Test
    void cleanupFailurePropagatesWhenNoPriorFailureExists() throws Exception {
        Method preserve = method("runAfterPreservingFailure", Throwable.class, Runnable.class);
        IllegalStateException cleanup = new IllegalStateException("attachment");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> preserve.invoke(null, null, (Runnable) () -> { throw cleanup; }));
        assertSame(cleanup, thrown.getCause());
    }

    @Test
    void redundantPrimaryExceptionCannotSuppressItself() throws Exception {
        Method preserve = method("runAfterPreservingFailure", Throwable.class, Runnable.class);
        RuntimeException shared = new RuntimeException("same exception");
        preserve.invoke(null, shared, (Runnable) () -> { throw shared; });
        assertEquals(0, shared.getSuppressed().length);
    }
}
