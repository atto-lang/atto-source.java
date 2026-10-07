package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-owned, process-local registry for immutable Oreslang code images.
 *
 * <p>Actor isolation is about guest <em>state</em> and capabilities, not about
 * copying read-only source/compiled metadata into every actor. A leased Source
 * may be reused across independently constructed Graal contexts, including
 * private/untrusted actor contexts. Nothing in this registry is a guest value,
 * a mutable module environment, an ActorRef, a closure, or a host capability.
 *
 * <p>This guarantees Source <em>object</em> identity reuse within this host
 * process. It does NOT by itself guarantee sharing of JIT machine code,
 * physical pages across OS processes, or compiled Truffle call targets; those
 * require separate backend-specific proofs.
 */
public final class SharedCodeRegistry {
    private record Key(String codeUnitId, String sha256) { }

    private static final SharedCodeRegistry PROCESS = new SharedCodeRegistry();

    private final Map<Key, Entry> images = new HashMap<>();

    /** This registry is owned by the supervisor, never exposed to guest code. */
    public static SharedCodeRegistry process() {
        return PROCESS;
    }

    /**
     * Acquire a pinned immutable image for one code-unit identity and version.
     * A source name is part of the key because diagnostics and import resolution
     * must not inherit another unit's source identity.
     */
    public synchronized Lease acquire(String codeUnitId, String sourceText) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(sourceText, "sourceText");
        if (codeUnitId.isBlank()) {
            throw new IllegalArgumentException("codeUnitId cannot be blank");
        }
        Key key = new Key(codeUnitId, digest(sourceText));
        Entry entry = images.get(key);
        if (entry == null) {
            Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            entry = new Entry(key, source, sourceText);
            images.put(key, entry);
        } else if (!entry.originalText.equals(sourceText)) {
            // Never accept digest equivalence as authority to execute different
            // text. A cryptographic collision must fail closed.
            throw new SecurityException("different source text has the same code-image digest");
        }
        entry.references++;
        return new Lease(this, entry);
    }

    /** Supervisor-only diagnostic; no handles or source contents are exposed. */
    public synchronized int liveImages() {
        return images.size();
    }

    private synchronized void release(Entry entry) {
        Entry active = images.get(entry.key);
        if (active != entry || entry.references <= 0) {
            throw new IllegalStateException("code-image lease ownership mismatch");
        }
        if (--entry.references == 0) {
            images.remove(entry.key);
        }
    }

    private static String digest(String sourceText) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(sourceText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class Entry {
        private final Key key;
        private final Source source;
        private final String originalText;
        private int references;

        private Entry(Key key, Source source, String originalText) {
            this.key = key;
            this.source = source;
            this.originalText = originalText;
        }
    }

    /**
     * Pins an image while a code generation may still execute it. Closing the
     * final lease removes it from the strong cache; the JVM may then reclaim it.
     */
    public static final class Lease implements AutoCloseable {
        private final SharedCodeRegistry owner;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(SharedCodeRegistry owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        public Source source() {
            if (closed.get()) {
                throw new IllegalStateException("code-image lease is closed");
            }
            return entry.source;
        }

        public String sha256() {
            return entry.key.sha256();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(entry);
            }
        }
    }
}
