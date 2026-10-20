package dev.oreslang.compiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Backend-neutral receipt for Oreslang whole-program reachability.
 *
 * <p>The manifest is deliberately independent of JVM/GraalVM details so LLVM,
 * embedded, Wasm, mobile, desktop and hardware-oriented backends can consume
 * the same pruning decision. Every list is sorted and deduplicated to make the
 * JSON representation deterministic and suitable for build attestation.</p>
 */
public record ReachabilityManifest(
        String schema,
        List<String> entryPoints,
        boolean preservePublicApi,
        List<String> retainedSymbols,
        List<String> removedSymbols,
        List<String> externalBindings,
        List<String> languageFeatures,
        List<String> runtimeOperations) {

    public static final String SCHEMA = "oreslang.reachability/1";

    public ReachabilityManifest {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported reachability manifest schema '" + schema + "'");
        }
        entryPoints = sortedUnique(entryPoints);
        retainedSymbols = sortedUnique(retainedSymbols);
        removedSymbols = sortedUnique(removedSymbols);
        externalBindings = sortedUnique(externalBindings);
        languageFeatures = sortedUnique(languageFeatures);
        runtimeOperations = sortedUnique(runtimeOperations);

        LinkedHashSet<String> overlap = new LinkedHashSet<>(retainedSymbols);
        overlap.retainAll(removedSymbols);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "reachability manifest cannot both retain and remove symbols: " + overlap);
        }
    }

    static ReachabilityManifest create(
            BuildOptions options,
            Collection<String> retainedSymbols,
            Collection<String> removedSymbols,
            Collection<String> externalBindings,
            Collection<String> languageFeatures,
            Collection<String> runtimeOperations) {
        Objects.requireNonNull(options, "options");
        return new ReachabilityManifest(
                SCHEMA,
                new ArrayList<>(options.entryPoints()),
                options.preservePublicApi(),
                new ArrayList<>(retainedSymbols),
                new ArrayList<>(removedSymbols),
                new ArrayList<>(externalBindings),
                new ArrayList<>(languageFeatures),
                new ArrayList<>(runtimeOperations));
    }

    /** Deterministic JSON with fixed key order and sorted set-like arrays. */
    public String toCanonicalJson() {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendStringField(json, "schema", schema);
        json.append(',');
        appendStringArrayField(json, "entryPoints", entryPoints);
        json.append(',').append("\"preservePublicApi\":").append(preservePublicApi);
        json.append(',');
        appendStringArrayField(json, "retainedSymbols", retainedSymbols);
        json.append(',');
        appendStringArrayField(json, "removedSymbols", removedSymbols);
        json.append(',');
        appendStringArrayField(json, "externalBindings", externalBindings);
        json.append(',');
        appendStringArrayField(json, "languageFeatures", languageFeatures);
        json.append(',');
        appendStringArrayField(json, "runtimeOperations", runtimeOperations);
        json.append('}');
        return json.toString();
    }

    private static List<String> sortedUnique(Collection<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static void appendStringField(StringBuilder json, String key, String value) {
        appendQuoted(json, key);
        json.append(':');
        appendQuoted(json, value);
    }

    private static void appendStringArrayField(StringBuilder json, String key, List<String> values) {
        appendQuoted(json, key);
        json.append(':').append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            appendQuoted(json, values.get(i));
        }
        json.append(']');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        json.append('"');
    }
}
