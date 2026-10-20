package dev.oreslang.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic whole-build wrapper around per-code-unit reachability receipts. */
public record BuildReachabilityManifest(
        String schema,
        String entryUnit,
        List<String> retainedUnits,
        List<String> removedUnits,
        Map<String, ReachabilityManifest> units) {

    public static final String SCHEMA = "oreslang.build-reachability/1";

    public BuildReachabilityManifest {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                    "unsupported build reachability schema '" + schema + "'");
        }
        if (entryUnit == null || entryUnit.isBlank()) {
            throw new IllegalArgumentException("entry unit cannot be blank");
        }
        retainedUnits = sortedUnique(retainedUnits);
        removedUnits = sortedUnique(removedUnits);

        java.util.LinkedHashSet<String> overlap =
                new java.util.LinkedHashSet<>(retainedUnits);
        overlap.retainAll(removedUnits);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "build manifest cannot both retain and remove units: " + overlap);
        }
        if (!retainedUnits.contains(entryUnit)) {
            throw new IllegalArgumentException(
                    "entry unit must be retained: " + entryUnit);
        }

        LinkedHashMap<String, ReachabilityManifest> ordered = new LinkedHashMap<>();
        units.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(
                        requireCanonicalUnitId(entry.getKey()),
                        Objects.requireNonNull(entry.getValue(), "unit manifest")));
        if (!ordered.keySet().equals(new java.util.LinkedHashSet<>(retainedUnits))) {
            throw new IllegalArgumentException(
                    "unit manifests must exactly cover retained units");
        }
        units = Map.copyOf(ordered);
    }

    public String toCanonicalJson() {
        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        appendQuoted(json, "schema");
        json.append(':');
        appendQuoted(json, schema);
        json.append(',');
        appendQuoted(json, "entryUnit");
        json.append(':');
        appendQuoted(json, entryUnit);
        json.append(',');
        appendArray(json, "retainedUnits", retainedUnits);
        json.append(',');
        appendArray(json, "removedUnits", removedUnits);
        json.append(',');
        appendQuoted(json, "units");
        json.append(":{");
        boolean first = true;
        for (String unit : retainedUnits) {
            ReachabilityManifest manifest = units.get(unit);
            if (manifest == null) {
                throw new IllegalStateException(
                        "missing retained unit manifest for " + unit);
            }
            if (!first) json.append(',');
            first = false;
            appendQuoted(json, unit);
            json.append(':').append(manifest.toCanonicalJson());
        }
        json.append("}}");
        return json.toString();
    }

    private static List<String> sortedUnique(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(BuildReachabilityManifest::requireCanonicalUnitId)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String requireCanonicalUnitId(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("unit id must be a non-empty canonical string");
        }
        return value;
    }

    private static void appendArray(
            StringBuilder json,
            String key,
            List<String> values) {
        appendQuoted(json, key);
        json.append(":[");
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
                case '"' -> json.append("\\"");
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
