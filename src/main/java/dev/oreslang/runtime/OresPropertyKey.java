package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;

/**
 * A tagged property key for compiler-known records, structs and class slots.
 *
 * <p>Textual members are not Symbols. In particular, {@code status},
 * {@code "status"} and {@code #status} are not interchangeable in a static
 * aggregate. This type is internal metadata, not a guest Map or a capability.
 * Existing String-keyed field storage must not be silently reinterpreted.
 */
public sealed interface OresPropertyKey
        permits OresPropertyKey.StringKey, OresPropertyKey.SymbolKey {

    /** Wire discriminator reserved by the portable Symbol-key v1 contract. */
    enum Domain {
        STRING(0), PROCESS_SYMBOL(1), STABLE_SYMBOL(2);

        private final int wireTag;

        Domain(int wireTag) { this.wireTag = wireTag; }
        public int wireTag() { return wireTag; }
    }

    Domain domain();
    String name();

    static StringKey string(String name) {
        return new StringKey(name);
    }

    static SymbolKey symbol(OresSymbol value) {
        return new SymbolKey(value);
    }

    /**
     * Guest-language dynamic key bridge. Only String or Symbol guest values
     * are supported; host objects cannot forge new key domains.
     */
    static OresPropertyKey fromGuestValue(Object value) {
        if (value instanceof String name) return string(name);
        if (value instanceof OresSymbol symbol) return symbol(symbol);
        throw new IllegalArgumentException("property key must be String or Symbol");
    }

    /**
     * Deterministic compiler/layout order: domain tag first, then unsigned
     * canonical UTF-8 bytes. No locale, hash iteration or process-local IDs.
     */
    Comparator<OresPropertyKey> CANONICAL_ORDER = (left, right) -> {
        int domain = Integer.compare(left.domain().wireTag(), right.domain().wireTag());
        if (domain != 0) return domain;
        byte[] a = left.name().getBytes(StandardCharsets.UTF_8);
        byte[] b = right.name().getBytes(StandardCharsets.UTF_8);
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int diff = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (diff != 0) return diff;
        }
        return Integer.compare(a.length, b.length);
    };

    /** String members preserve legacy spelling and are never NFC-collapsed. */
    record StringKey(String name) implements OresPropertyKey {
        public StringKey {
            Objects.requireNonNull(name, "String member key");
        }

        @Override public Domain domain() { return Domain.STRING; }
    }

    /** Symbol members use OresSymbol's canonical, nominal logical identity. */
    record SymbolKey(OresSymbol value) implements OresPropertyKey {
        public SymbolKey {
            Objects.requireNonNull(value, "Symbol member key");
        }

        @Override public Domain domain() {
            return switch (value.kind()) {
                case PROCESS -> Domain.PROCESS_SYMBOL;
                case STABLE -> Domain.STABLE_SYMBOL;
            };
        }

        @Override public String name() { return value.name(); }
    }
}
