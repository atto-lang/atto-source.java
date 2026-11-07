package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VM-internal, immutable Symbol literal images shared by trusted actors.
 *
 * <p>This is a bounded JVM/Graal managed-value foundation, not an mmap/OS
 * read-only page, a guest-visible symbol registry, an interning API, or proof
 * that an actor's full object graph is safe to share. No guest/tenant may
 * forge an image generation or use the internal ordinal as a capability.
 * Physical reuse never changes OresSymbol's logical kind/name equality.</p>
 *
 * <p>A staged image is fully detached before publication. Existing leases
 * retain their immutable generation across hot replacement; the last release
 * permits retirement. All mutation of the registry/pin ledger is serialized.
 * Individual lease reads are synchronized against closing that lease.</p>
 */
final class OresSymbolLiteralImageStore implements AutoCloseable {
    static final int MAX_LITERALS = 4_096;
    static final long MAX_PAYLOAD_UTF8_BYTES = 1_048_576L;
    static final int MAX_LIVE_GENERATIONS = 8;
    // VM-local sequence: a numeric generation from another image store must
    // never accidentally select a different store's first/second generation.
    // Still internal metadata, NOT a guest capability or durable identifier.
    private static final AtomicLong NEXT_IMAGE_GENERATION = new AtomicLong(1L);

    private static final class Image {
        final long generation;
        final List<OresSymbol> literals;
        final Map<OresSymbol, Integer> ordinals;
        final long payloadBytes;
        int pins;

        Image(long generation, List<OresSymbol> literals,
              Map<OresSymbol, Integer> ordinals, long payloadBytes) {
            this.generation = generation;
            this.literals = literals;
            this.ordinals = ordinals;
            this.payloadBytes = payloadBytes;
        }
    }

    /** Only the trusted runtime may mint a lease; ordinal is image-relative. */
    static final class Lease implements AutoCloseable {
        private final OresSymbolLiteralImageStore owner;
        private final Image image;
        private boolean closed;

        private Lease(OresSymbolLiteralImageStore owner, Image image) {
            this.owner = owner;
            this.image = image;
        }

        synchronized long generation() {
            ensureOpen();
            return image.generation;
        }

        synchronized int size() {
            ensureOpen();
            return image.literals.size();
        }

        synchronized long payloadBytes() {
            ensureOpen();
            return image.payloadBytes;
        }

        /** Trusted same-domain actors may reuse the immutable Symbol instance. */
        synchronized OresSymbol get(int ordinal) {
            ensureOpen();
            return image.literals.get(ordinal);
        }

        synchronized int indexOf(OresSymbol symbol) {
            ensureOpen();
            Integer found = image.ordinals.get(Objects.requireNonNull(symbol, "symbol"));
            return found == null ? -1 : found;
        }

        /**
         * Narrow v1 untrusted transport adapter. The caller must independently
         * authenticate the destination and apply all non-Symbol ingress checks.
         * It returns a NEW immutable value, never an image handle or ordinal.
         * Tenant-scoped manifest exports are intentionally unsupported here.
         */
        synchronized OresSymbol copyForUntrustedCoreV1(int ordinal) {
            OresSymbol value = get(ordinal);
            if (!value.publicToUntrustedCoreV1()) {
                throw new SecurityException("Symbol is not in the public untrusted core manifest");
            }
            return OresSymbol.process(value.name());
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Symbol image lease is closed");
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.release(image);
        }
    }

    private final Map<Long, Image> live = new HashMap<>();
    private Image current;
    private volatile boolean closed;
    // Reentrancy is per publisher thread, not a process-wide staging lock:
    // iterating a host collection can execute arbitrary callbacks and must
    // NEVER hold the registry monitor or strand actor readers/carriers.
    private final ThreadLocal<Boolean> staging = new ThreadLocal<>();

    /**
     * Prepare a bounded immutable snapshot OUTSIDE the registry lock.
     * Input collections (and iterator callbacks) are untrusted: never retain
     * them, trust size(), or invoke them while holding the store monitor.
     */
    long publish(Collection<OresSymbol> source) {
        Objects.requireNonNull(source, "source");
        if (closed) throw new IllegalStateException("Symbol image store is closed");
        if (Boolean.TRUE.equals(staging.get())) {
            throw new IllegalStateException("recursive Symbol image publication");
        }
        staging.set(Boolean.TRUE);
        try {
            List<OresSymbol> values = new ArrayList<>();
            Map<OresSymbol, Integer> positions = new LinkedHashMap<>();
            long bytes = 0L;
            for (OresSymbol symbol : source) {
                Objects.requireNonNull(symbol, "null Symbol literal");
                if (values.size() >= MAX_LITERALS) {
                    throw new IllegalArgumentException("Symbol image literal-count quota exceeded");
                }
                if (positions.containsKey(symbol)) {
                    throw new IllegalArgumentException("duplicate canonical Symbol literal");
                }
                bytes = Math.addExact(bytes,
                        symbol.name().getBytes(StandardCharsets.UTF_8).length);
                if (bytes > MAX_PAYLOAD_UTF8_BYTES) {
                    throw new IllegalArgumentException("Symbol image UTF-8 payload quota exceeded");
                }
                positions.put(symbol, values.size());
                values.add(symbol);
            }

            List<OresSymbol> sealed = List.copyOf(values);
            Map<OresSymbol, Integer> lookup = Map.copyOf(positions);

            // Commit is brief, serialized and transactional: no source
            // callbacks occur here. Close and competing publishers serialize.
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Symbol image store closed during publication");
                }
                int afterPublication = live.size() + 1
                        - (current != null && current.pins == 0 ? 1 : 0);
                if (afterPublication > MAX_LIVE_GENERATIONS) {
                    throw new IllegalStateException("too many pinned Symbol image generations");
                }
                // Allocate the image and reserve the map entry before changing
                // the visible current generation. Neither map nor Image may
                // call host callbacks during this commit.
                long candidate;
                while (true) {
                    candidate = NEXT_IMAGE_GENERATION.get();
                    if (candidate == Long.MAX_VALUE) {
                        throw new IllegalStateException("Symbol generation counter exhausted");
                    }
                    if (NEXT_IMAGE_GENERATION.compareAndSet(candidate, candidate + 1)) break;
                }
                Image image = new Image(candidate, sealed, lookup, bytes);
                live.put(image.generation, image);
                Image previous = current;
                current = image;
                retireIfUnused(previous);
                return image.generation;
            }
        } finally {
            staging.remove();
        }
    }

    synchronized Lease pinLatest() {
        if (closed) throw new IllegalStateException("Symbol image store is closed");
        if (current == null) throw new IllegalStateException("no Symbol image published");
        Lease lease = new Lease(this, current);
        current.pins = Math.addExact(current.pins, 1);
        return lease;
    }

    /** Reject stale expected generations; never reinterpret an old ordinal. */
    synchronized Lease pinExpected(long expectedGeneration) {
        if (closed) throw new IllegalStateException("Symbol image store is closed");
        if (current == null || current.generation != expectedGeneration) {
            throw new IllegalStateException("stale Symbol image generation");
        }
        return pinLatest();
    }

    synchronized int liveGenerationCount() {
        return live.size();
    }

    private synchronized void release(Image image) {
        if (image.pins <= 0) {
            throw new IllegalStateException("Symbol image pin-ledger underflow");
        }
        --image.pins;
        retireIfUnused(image);
    }

    private void retireIfUnused(Image image) {
        if (image != null && image != current && image.pins == 0) {
            live.remove(image.generation);
        }
    }

    /**
     * Stops new pins/publications; already pinned immutable images remain
     * readable until their leases close. Never invalidate live reader data.
     */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        current = null;
        live.values().removeIf(image -> image.pins == 0);
    }
}
