package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Source;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supervisor-owned immutable source images for exactly ONE untrusted Graal Engine.
 *
 * <p>Never register these Sources or checked ASTs in the trusted ProcessCodeEngine
 * or SharedCodeImageStore. Code identity is (logical unit, exact source digest);
 * actor identities, contexts, mailboxes, capabilities, and mutable module state
 * are never shared. A Source cache hit is NOT proof of compiled machine-code
 * sharing without an explicit Engine and ContextPolicy.SHARED; even then,
 * physical executable-page deduplication needs native measurements.</p>
 *
 * <p>References are pinned until their owning sandbox Context has been
 * <em>confirmed</em> closed, including during hot-reload generation overlap.
 * The cache retains no idle entry after the final lease is released.</p>
 */
final class UntrustedSourceImageCache {
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

    /**
     * Cheap bounded preflight for the trusted supervisor; this does not parse
     * or type-check attacker-controlled Oreslang or grant import authority.
     * Call it BEFORE building a costly native guest isolate.
     */
    static void validateCandidate(String codeUnitId, String sourceText) {
        UntrustedSourceIdentity.inspect(codeUnitId, sourceText);
    }

    synchronized Lease acquire(String codeUnitId, String sourceText) {
        UntrustedSourceIdentity.Validated identity =
                UntrustedSourceIdentity.inspect(codeUnitId, sourceText);
        Key key = new Key(identity.codeUnitId(), identity.sha256());
        Entry entry = live.get(key);
        if (entry != null) {
            // The digest is an index, never authorization or equality evidence.
            if (!entry.originalText.equals(sourceText)) {
                throw new SecurityException("untrusted code-image digest collision");
            }
            entry.leases = Math.addExact(entry.leases, 1);
            return new Lease(this, entry);
        }
        if (live.size() >= maxImages || identity.byteLength() > maxBytes - pinnedBytes) {
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
        entry = new Entry(key, sourceText, source, identity.byteLength());
        live.put(key, entry);
        pinnedBytes += identity.byteLength();
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

        @Override public synchronized void close() {
            if (closed.get()) return;
            // Do not publish successful lease retirement before the cache's
            // actual reference count has been decremented. A failed release
            // must remain retryable without losing source lifetime authority.
            owner.release(entry);
            closed.set(true);
        }
    }
}
