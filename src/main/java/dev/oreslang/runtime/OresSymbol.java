package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable canonical Symbol identity used for structural/class member keys.
 *
 * <p>PROCESS identities are canonical for the lifetime of this VM process.
 * STABLE identities are keyed by canonical UUID text.  Symbol.create is the
 * ergonomic alias for Symbol.process; it does not create a fresh JS-style
 * identity because AOT member shapes require stable compile-time identity.</p>
 */
public final class OresSymbol {
    public enum Scope { PROCESS, STABLE }

    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_PROCESS_SYMBOLS = 65_536;
    public static final int MAX_STABLE_SYMBOLS = 65_536;

    private static final ConcurrentHashMap<String, OresSymbol> PROCESS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, OresSymbol> STABLE = new ConcurrentHashMap<>();

    private final Scope scope;
    private final String key;

    private OresSymbol(Scope scope, String key) {
        this.scope = Objects.requireNonNull(scope);
        this.key = Objects.requireNonNull(key);
    }

    /** Canonical process Symbol; intentionally an alias rather than a fresh identity. */
    public static OresSymbol create(String key) {
        return process(key);
    }

    public static OresSymbol process(String key) {
        String normalized = validateKey(key);
        OresSymbol existing = PROCESS.get(normalized);
        if (existing != null) return existing;
        if (PROCESS.size() >= MAX_PROCESS_SYMBOLS) {
            throw new IllegalStateException(
                    "process Symbol table limit exceeded: " + MAX_PROCESS_SYMBOLS);
        }
        OresSymbol created = new OresSymbol(Scope.PROCESS, normalized);
        OresSymbol raced = PROCESS.putIfAbsent(normalized, created);
        return raced == null ? created : raced;
    }

    public static OresSymbol stable(String uuidKey) {
        String raw = validateKey(uuidKey);
        final UUID uuid;
        try {
            uuid = UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("stable Symbol key must be a canonical UUID", invalid);
        }
        String canonical = uuid.toString();
        if (!canonical.equals(raw.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("stable Symbol key must be a canonical UUID");
        }
        OresSymbol existing = STABLE.get(uuid);
        if (existing != null) return existing;
        if (STABLE.size() >= MAX_STABLE_SYMBOLS) {
            throw new IllegalStateException(
                    "stable Symbol table limit exceeded: " + MAX_STABLE_SYMBOLS);
        }
        OresSymbol created = new OresSymbol(Scope.STABLE, canonical);
        OresSymbol raced = STABLE.putIfAbsent(uuid, created);
        return raced == null ? created : raced;
    }

    public static OresSymbol iterator() {
        return process("Symbol.iterator");
    }

    public Scope scope() {
        return scope;
    }

    public String key() {
        return key;
    }

    /** Compiler/runtime map key shared by records, class slots, and method selectors. */
    public String memberKey() {
        return Ast.symbolMemberKey(scope == Scope.PROCESS ? "process" : "stable", key);
    }

    private static String validateKey(String key) {
        Objects.requireNonNull(key, "Symbol key");
        if (key.isBlank()) throw new IllegalArgumentException("Symbol key cannot be blank");
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Symbol key exceeds " + MAX_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isISOControl(ch)) {
                throw new IllegalArgumentException("Symbol key cannot contain control characters");
            }
        }
        return key;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OresSymbol symbol
                && symbol.scope == scope
                && symbol.key.equals(key);
    }

    @Override
    public int hashCode() {
        return 31 * scope.hashCode() + key.hashCode();
    }

    @Override
    public String toString() {
        return scope == Scope.PROCESS
                ? "Symbol.process(\"" + key + "\")"
                : "Symbol.stable(\"" + key + "\")";
    }
}
