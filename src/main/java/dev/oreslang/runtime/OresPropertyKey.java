package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Tagged, immutable property-key identity for compiler shapes and collection
 * schemas. String and Symbol namespaces must never coerce into one another.
 *
 * This is metadata/value infrastructure, not a dynamic-property escape hatch:
 * closed records, structs and classes still require a checked declared field.
 */
public sealed interface OresPropertyKey
        permits OresPropertyKey.Text, OresPropertyKey.Symbolic {
    enum Domain { TEXT, PROCESS_SYMBOL, STABLE_SYMBOL }

    Domain domain();

    record Text(String name) implements OresPropertyKey {
        public Text { Objects.requireNonNull(name, "property name"); }
        @Override public Domain domain() { return Domain.TEXT; }
    }

    record Symbolic(OresSymbol symbol) implements OresPropertyKey {
        public Symbolic { Objects.requireNonNull(symbol, "symbol"); }
        @Override public Domain domain() {
            return symbol.kind() == OresSymbol.Kind.PROCESS
                    ? Domain.PROCESS_SYMBOL : Domain.STABLE_SYMBOL;
        }
    }

    static OresPropertyKey from(Object key) {
        if (key instanceof String text) return new Text(text);
        if (key instanceof OresSymbol symbol) return new Symbolic(symbol);
        throw new IllegalArgumentException(
                "property key must be a String or Symbol; implicit coercion is forbidden");
    }
}
