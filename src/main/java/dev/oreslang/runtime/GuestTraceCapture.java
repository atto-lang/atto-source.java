package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Guest-only callable trace; never expose the JVM stack, receivers or host paths. */
public final class GuestTraceCapture {
    private static final int MAX_FRAMES = 64;
    private static final ThreadLocal<ArrayDeque<String>> CURRENT =
            ThreadLocal.withInitial(ArrayDeque::new);

    private GuestTraceCapture() { }

    /** Nested synchronous guest invocation frame, not a Java stack frame. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    public static Scope enter(String callable) {
        if (callable == null || callable.isBlank()) throw new IllegalArgumentException("blank guest frame");
        ArrayDeque<String> frames = CURRENT.get();
        boolean admitted = frames.size() < MAX_FRAMES;
        if (admitted) frames.addLast(callable);
        return () -> {
            if (admitted) {
                String popped = frames.removeLast();
                if (!popped.equals(callable)) {
                    frames.clear();
                    CURRENT.remove();
                    throw new IllegalStateException("guest trace stack corruption");
                }
            }
            if (frames.isEmpty()) CURRENT.remove();
        };
    }

    /** New owned list in outermost-to-innermost order, capped to 64 callables. */
    public static List<String> frames() {
        List<String> frames = new ArrayList<>(CURRENT.get());
        return Collections.unmodifiableList(frames);
    }

    public static String text() {
        return String.join("\n", frames());
    }
}
