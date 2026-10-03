package dev.oreslang.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Immutable process-canonical symbol identity for trusted Oreslang execution.
 *
 * <p>The interner deliberately holds weak references. Ordinary symbols therefore
 * participate in automatic GC once no guest/runtime root references them. A symbol
 * stored in process-singleton state is pinned by that singleton's strong process
 * root; collecting the singleton releases that pin safely without exposing an
 * unsafe free-by-address API.</p>
 *
 * <p>Symbol ids are process-local monotonic handles and are never reused. The
 * canonical key, not the id, is the serialization/wire representation. Adversarial
 * / untrusted isolates are never admitted to this registry.</p>
 */
public final class OresSymbol {
    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_LIVE_SYMBOLS = 65_536;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Object LOCK = new Object();
    private static final Map<String, SymbolRef> INTERNED = new HashMap<>();
    private static final ReferenceQueue<OresSymbol> COLLECTED = new ReferenceQueue<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    private final String key;
    private final long id;

    private OresSymbol(String key, long id) {
        this.key = key;
        this.id = id;
    }

    /**
     * Trusted runtime entry point. Same-key symbols are canonical while live.
     * If a non-pinned symbol becomes unreachable, a later recreation receives a
     * fresh monotonically increasing id.
     */
    public static OresSymbol of(String key) {
        String normalized = validateKey(key);
        synchronized (LOCK) {
            expungeCollectedLocked();
            SymbolRef ref = INTERNED.get(normalized);
            OresSymbol existing = ref == null ? null : ref.get();
            if (existing != null) return existing;
            if (ref != null) INTERNED.remove(normalized, ref);

            if (INTERNED.size() >= MAX_LIVE_SYMBOLS) {
                throw new IllegalStateException(
                        "process symbol limit exceeded: " + MAX_LIVE_SYMBOLS);
            }

            long id = NEXT_ID.getAndIncrement();
            if (id <= 0) {
                throw new IllegalStateException("process symbol id space exhausted");
            }
            OresSymbol created = new OresSymbol(normalized, id);
            INTERNED.put(normalized, new SymbolRef(normalized, created));
            return created;
        }
    }

    /**
     * Guest/runtime admission entry point. Untrusted code cannot create or join
     * the process-wide symbol identity domain even if another capability set was
     * accidentally widened.
     */
    public static OresSymbol of(String key, IsolatePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.adversarial()) {
            throw new SecurityException(
                    "process-wide symbols are unavailable to adversarial/untrusted isolates");
        }
        return of(key);
    }

    public String key() {
        return key;
    }

    /** Process-local monotonic identity handle. Never serialize this value. */
    public long id() {
        return id;
    }

    /** Compatibility/readability alias for identifier-style protocol tags. */
    public String name() {
        return key;
    }

    public static int liveRegistryEntries() {
        synchronized (LOCK) {
            expungeCollectedLocked();
            return INTERNED.size();
        }
    }

    private static void expungeCollectedLocked() {
        SymbolRef stale;
        while ((stale = (SymbolRef) COLLECTED.poll()) != null) {
            INTERNED.remove(stale.key, stale);
        }
    }

    private static String validateKey(String key) {
        Objects.requireNonNull(key, "symbol key");
        if (key.isBlank()) throw new IllegalArgumentException("symbol key cannot be blank");
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "symbol key exceeds " + MAX_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                throw new IllegalArgumentException(
                        "symbol key cannot contain control or unpaired surrogate characters");
            }
        }
        return key;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OresSymbol symbol && symbol.id == id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        if (IDENTIFIER.matcher(key).matches()) return ":" + key;
        return "Symbol(\"" + key
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\")";
    }

    private static final class SymbolRef extends WeakReference<OresSymbol> {
        private final String key;

        private SymbolRef(String key, OresSymbol symbol) {
            super(symbol, COLLECTED);
            this.key = key;
        }
    }
}
