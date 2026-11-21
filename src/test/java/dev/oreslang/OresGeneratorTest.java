package dev.oreslang;

import dev.oreslang.runtime.OresGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresGeneratorTest {
    @Test
    void yieldsMultipleValuesThenExposesFinalReturnValue() {
        AtomicBoolean closed = new AtomicBoolean();

        final class Frame implements OresGenerator.Frame<Integer, String, Integer> {
            private int pc;

            @Override
            public OresGenerator.Step<Integer, String> resume(
                    OresGenerator.Resume<Integer> resume) {
                return switch (pc++) {
                    case 0 -> {
                        assertEquals(OresGenerator.ResumeKind.START, resume.kind());
                        yield new OresGenerator.Yielded<>(10);
                    }
                    case 1 -> {
                        assertEquals(OresGenerator.ResumeKind.NEXT, resume.kind());
                        assertEquals(7, resume.value());
                        yield new OresGenerator.Yielded<>(20);
                    }
                    case 2 -> new OresGenerator.Complete<>("done");
                    default -> throw new AssertionError("frame resumed after completion");
                };
            }

            @Override
            public void close() {
                assertTrue(closed.compareAndSet(false, true), "frame close must be exactly once");
            }
        }

        var generator = new OresGenerator<Integer, String, Integer>(new Frame());

        var first = generator.next(999);
        assertFalse(first.done());
        assertEquals(10, first.yieldedValue());
        assertThrows(IllegalStateException.class, first::returnValue);

        var second = generator.next(7);
        assertFalse(second.done());
        assertEquals(20, second.yieldedValue());

        var complete = generator.next();
        assertTrue(complete.done());
        assertEquals("done", complete.returnValue());
        assertEquals("done", generator.completionValue());
        assertTrue(generator.isDone());
        assertTrue(closed.get());

        var repeated = generator.next();
        assertTrue(repeated.done());
        assertEquals("done", repeated.returnValue());
    }

    @Test
    void throwIntoIsDeliveredAtSuspendedYieldPoint() {
        AtomicReference<Throwable> observed = new AtomicReference<>();

        final class Frame implements OresGenerator.Frame<String, Integer, Void> {
            private int pc;

            @Override
            public OresGenerator.Step<String, Integer> resume(
                    OresGenerator.Resume<Void> resume) {
                if (pc++ == 0) return new OresGenerator.Yielded<>("ready");
                assertEquals(OresGenerator.ResumeKind.THROW, resume.kind());
                observed.set(resume.failure());
                return new OresGenerator.Complete<>(42);
            }
        }

        var generator = new OresGenerator<String, Integer, Void>(new Frame());
        assertEquals("ready", generator.next().yieldedValue());

        RuntimeException injected = new RuntimeException("boom");
        var completion = generator.throwInto(injected);

        assertSame(injected, observed.get());
        assertTrue(completion.done());
        assertEquals(42, completion.returnValue());
    }

    @Test
    void iteratorViewConsumesYieldedValuesAndDiscardsReturnValue() {
        final class Frame implements OresGenerator.Frame<Integer, String, Void> {
            private int pc;

            @Override
            public OresGenerator.Step<Integer, String> resume(
                    OresGenerator.Resume<Void> resume) {
                return switch (pc++) {
                    case 0 -> new OresGenerator.Yielded<>(1);
                    case 1 -> new OresGenerator.Yielded<>(2);
                    case 2 -> new OresGenerator.Yielded<>(3);
                    case 3 -> new OresGenerator.Complete<>("finished");
                    default -> throw new AssertionError("frame resumed after completion");
                };
            }
        }

        var generator = new OresGenerator<Integer, String, Void>(new Frame());
        List<Integer> values = new ArrayList<>();
        for (int value : generator) values.add(value);

        assertEquals(List.of(1, 2, 3), values);
        assertEquals("finished", generator.completionValue());
    }

    @Test
    void closeDeterministicallyUnwindsSuspendedFrame() {
        AtomicBoolean cleaned = new AtomicBoolean();

        var generator = new OresGenerator<Integer, Void, Void>(
                new OresGenerator.Frame<>() {
                    @Override
                    public OresGenerator.Step<Integer, Void> resume(
                            OresGenerator.Resume<Void> resume) {
                        return new OresGenerator.Yielded<>(1);
                    }

                    @Override
                    public void close() {
                        cleaned.set(true);
                    }
                });

        assertEquals(1, generator.next().yieldedValue());
        generator.close();

        assertTrue(cleaned.get());
        assertTrue(generator.isClosed());
        assertThrows(IllegalStateException.class, generator::next);
    }

    @Test
    void frameFailureFailsClosedAndRunsCleanup() {
        AtomicBoolean cleaned = new AtomicBoolean();

        var generator = new OresGenerator<Integer, Void, Void>(
                new OresGenerator.Frame<>() {
                    @Override
                    public OresGenerator.Step<Integer, Void> resume(
                            OresGenerator.Resume<Void> resume) {
                        throw new IllegalArgumentException("bad generator");
                    }

                    @Override
                    public void close() {
                        cleaned.set(true);
                    }
                });

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                generator::next);
        assertEquals("bad generator", failure.getMessage());
        assertTrue(cleaned.get());
        assertTrue(generator.isDone());
        assertSame(failure, generator.failure());
        assertThrows(IllegalStateException.class, generator::next);
    }

    @Test
    void reentrantResumeIsRejected() {
        AtomicReference<OresGenerator<Integer, Void, Void>> self = new AtomicReference<>();

        var generator = new OresGenerator<Integer, Void, Void>(
                resume -> {
                    IllegalStateException failure = assertThrows(
                            IllegalStateException.class,
                            () -> self.get().next());
                    assertTrue(failure.getMessage().contains("reentrantly"));
                    return new OresGenerator.Complete<>(null);
                });
        self.set(generator);

        assertTrue(generator.next().done());
    }
}
