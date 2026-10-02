package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Versioned source hot loader. Each generation gets a fresh Polyglot Context;
 * production hosts may additionally map that context to a Graal polyglot/native
 * isolate. No JNI/FFI or OS dynamic-library loading is required.
 */
public final class HotReloadManager implements AutoCloseable {
    private final IsolatePolicy policy;
    private final ExecutionProfile executionProfile;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();

    public HotReloadManager(IsolatePolicy policy, ExecutionProfile executionProfile) {
        this.policy = policy;
        this.executionProfile = executionProfile;
        if (!policy.allows(IsolatePolicy.Capability.HOT_CODE_LOAD)) {
            throw new SecurityException("HOT_CODE_LOAD capability is required");
        }
    }

    public synchronized Generation load(String name, String sourceText) {
        long id = sequence.incrementAndGet();
        Context context = policy.restrictedContextBuilder(executionProfile).build();
        try {
            Source source = Source.newBuilder(OresLanguage.ID, sourceText, name)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            context.eval(source);
            Generation generation = new Generation(id, digest(sourceText), context, executionProfile);
            generations.put(id, generation);
            active.set(generation);
            return generation;
        } catch (RuntimeException failure) {
            context.close(true);
            throw failure;
        }
    }

    public Generation active() { return active.get(); }

    /** Explicit retirement permits old actors/requests to drain before teardown. */
    public synchronized void retire(long generationId) {
        Generation generation = generations.remove(generationId);
        if (generation != null) {
            if (active.compareAndSet(generation, null)) { }
            generation.context().close(true);
        }
    }

    public synchronized int liveGenerations() { return generations.size(); }

    @Override
    public synchronized void close() {
        for (Generation generation : generations.values()) generation.context().close(true);
        generations.clear();
        active.set(null);
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Generation(long id, String sha256, Context context, ExecutionProfile executionProfile) { }
}
