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
 * Supervisor-owned immutable source images for exactly ONE untrusted Graal Engine.
 *
 * <p>Never register these Sources or checked ASTs in the trusted ProcessCodeEngine
 * or SharedCodeImageStore. Code identity is (logical unit, exact source digest);
 * actor identities, contexts, mailboxes, capabilities, and mutable module state
 * are never shared. A Source cache hit is NOT proof of compiled machine-code
 * sharing while the language still uses ContextPolicy.EXCLUSIVE.</p>
 *
 * <p>References are pinned until their owning sandbox Context has been
 * <em>confirmed</em> closed, including during hot-reload generation overlap.
 * The cache retains no idle entry after the final lease is released.</p>
 */
final class UntrustedSourceImageCache {
    private static final int MAX_CODE_UNIT_CHARS = 512;
    private static final int MAX_SOURCE_BYTES = 1_048_576;
    private record Key(String codeUnitId, String sha256) { }

    private final Map<Key, Entry> live = new HashMap<>();
    private final int maxImages;
    private final long maxBytes;
    private long pinnedBytes;

    UntrustedSourceImageCache() {
        this(512, 16L * 1024 * 1024);
    }

    UntrustedSourceImageCache(int maxImages, long maxBytes) {
        if (maxImages < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("source-image budgets must be positive");
        }
        this.maxImages = maxImages;
        this.maxBytes = maxBytes;
    }

    synchronized Lease acquire(String codeUnitId, String sourceText) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(sourceText, "sourceText");
        if (codeUnitId.isBlank() || codeUnitId.length() > MAX_CODE_UNIT_CHARS) {
            throw new IllegalArgumentException("invalid bounded untrusted code-unit identity");
        }
        byte[] bytes = sourceText.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("untrusted source byte budget exceeded");
        }
        Key key = new Key(codeUnitId, sha256(bytes));
        Entry entry = live.get(key);
        if (entry != null) {
            // The digest is an index, never authorization or equality evidence.
            if (!entry.originalText.equals(sourceText)) {
                throw new SecurityException("untrusted code-image digest collision");
            }
            entry.leases = Math.addExact(entry.leases, 1);
            return new Lease(this, entry);
        }
        if (live.size() >= maxImages || bytes.length > maxBytes - pinnedBytes) {
            throw new IllegalStateException("untrusted code-image cache capacity exceeded");
        }
        // Stable content identity makes engine-level Source caching possible;
        // UUID actor/generation fencing is handled separately by the caller.
        // Preserve the original module/import identity in Source.getName().
        // The full SHA-256 remains in the private cache key rather than
        // silently changing import resolution or diagnostics during hot reload.
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                .mimeType(OresLanguage.MIME_TYPE)
                .cached(true)
                .buildLiteral();
        entry = new Entry(key, sourceText, source, bytes.length);
        live.put(key, entry);
        pinnedBytes += bytes.length;
        return new Lease(this, entry);
    }

    synchronized int liveImages() { return live.size(); }
    synchronized long pinnedBytes() { return pinnedBytes; }

    private synchronized void release(Entry entry) {
        if (live.get(entry.key) != entry || entry.leases < 1) {
            throw new IllegalStateException("untrusted source-image lease mismatch");
        }
        if (--entry.leases == 0) {
            live.remove(entry.key);
            pinnedBytes -= entry.byteLength;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static final class Entry {
        private final Key key;
        private final String originalText;
        private final Source source;
        private final int byteLength;
        private int leases = 1;

        private Entry(Key key, String originalText, Source source, int byteLength) {
            this.key = key;
            this.originalText = originalText;
            this.source = source;
            this.byteLength = byteLength;
        }
    }

    static final class Lease implements AutoCloseable {
        private final UntrustedSourceImageCache owner;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(UntrustedSourceImageCache owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        Source source() {
            if (closed.get()) throw new IllegalStateException("code-image lease has been retired");
            return entry.source;
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) owner.release(entry);
        }
    }
}
