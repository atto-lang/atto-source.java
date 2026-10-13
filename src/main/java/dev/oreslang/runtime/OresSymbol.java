package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable Symbol value. Equality is semantic (kind + canonical Unicode key),
 * never a VM address, registry index, carrier thread, or actor/arena identity.
 *
 * <p>This intentionally does NOT intern arbitrary strings: untrusted input
 * must never allocate permanent process-wide atoms. Possessing a Symbol is
 * never evidence of authorization. Actor ingress applies separate policy.</p>
 */
public final class OresSymbol {
    public enum Kind { PROCESS, STABLE }
    public static final int MAX_KEY_UTF8_BYTES = 256;
    public static final int MAX_LITERAL_UTF8_BYTES = 128;

    private final Kind kind;
    private final String name;

    private OresSymbol(Kind kind, String name) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = Objects.requireNonNull(name, "name");
    }

    /** Canonical process Symbol. This is a bounded value constructor, NOT an intern operation. */
    public static OresSymbol process(String name) {
        return new OresSymbol(Kind.PROCESS, canonicalName(name));
    }

    /** Stable UUID kind is intentionally nominally distinct from an ordinary process Symbol. */
    public static OresSymbol stable(String uuid) {
        String normalized = canonicalName(uuid);
        if (!normalized.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("stable Symbol requires lowercase canonical UUID");
        }
        UUID parsed = UUID.fromString(normalized);
        if (!parsed.toString().equals(normalized)) {
            throw new IllegalArgumentException("stable Symbol requires canonical UUID");
        }
        return new OresSymbol(Kind.STABLE, normalized);
    }

    /** Used for a compile-time #identifier / #quoted-literal (not a dynamic untrusted key). */
    public static OresSymbol literal(String name) {
        String canonical = canonicalName(name);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_UTF8_BYTES) {
            throw new IllegalArgumentException("Symbol literal exceeds maximum length");
        }
        return new OresSymbol(Kind.PROCESS, canonical);
    }

    public Kind kind() { return kind; }
    public String name() { return name; }

    /**
     * Normal wire encoding is non-interning. Wire input must already be NFC
     * canonical; malformed Unicode and alternate key forms fail closed.
     */
    public static OresSymbol fromWire(Kind kind, String wireName) {
        Objects.requireNonNull(kind, "kind");
        String canonical = canonicalName(wireName);
        if (!canonical.equals(wireName)) {
            throw new IllegalArgumentException("noncanonical Symbol wire name");
        }
        return kind == Kind.STABLE ? stable(canonical) : process(canonical);
    }

    public boolean publicCore() {
        return kind == Kind.PROCESS && switch (name) {
            // Narrow bootstrap set; final public manifest must be versioned and signed.
            case "ready", "error", "Symbol.iterator" -> true;
            default -> false;
        };
    }

    private static String canonicalName(String name) {
        Objects.requireNonNull(name, "Symbol name");
        if (name.isEmpty()) throw new IllegalArgumentException("Symbol name cannot be empty");
        // Java UTF-8 encoding silently replaces bad surrogate sequences:
        // inspect UTF-16 explicitly before computing the UTF-8 byte bound.
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(i + 1))) {
                    throw new IllegalArgumentException("unpaired high surrogate in Symbol name");
                }
                i++;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("unpaired low surrogate in Symbol name");
            }
        }
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_UTF8_BYTES) {
            throw new IllegalArgumentException("Symbol name exceeds " + MAX_KEY_UTF8_BYTES + " UTF-8 bytes");
        }
        for (int i = 0; i < normalized.length(); i++) {
            int c = normalized.codePointAt(i);
            if (c > 0xffff) i++;
            if (c == 0 || c <= 0x1f || (c >= 0x7f && c <= 0x9f)
                    || c == 0x061c || c == 0x200e || c == 0x200f
                    || (c >= 0x202a && c <= 0x202e)
                    || (c >= 0x2066 && c <= 0x2069)) {
                throw new IllegalArgumentException("control/bidi character in Symbol name");
            }
        }
        return normalized;
    }

    @Override public boolean equals(Object other) {
        return other instanceof OresSymbol symbol
                && kind == symbol.kind && name.equals(symbol.name);
    }

    @Override public int hashCode() {
        return Objects.hash(kind, name);
    }

    @Override public String toString() {
        if (kind == Kind.STABLE) return "Symbol.stable(\"" + name + "\")";
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")
                && name.getBytes(StandardCharsets.UTF_8).length <= MAX_LITERAL_UTF8_BYTES) {
            return "#" + name;
        }
        String escaped = name.replace("\\", "\\\\").replace("\"", "\\\"");
        return "#\"" + escaped + "\"";
    }
}
