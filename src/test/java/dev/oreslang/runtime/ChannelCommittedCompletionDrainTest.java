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
