package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in, fail-closed admission of a complete Oreslang source import closure.
 *
 * This is a compile-time portability contract, not a runtime sandbox. Callers
 * must still enforce capabilities at the host boundary (e.g. IsolatePolicy).
 * Mixed Java source islands and unresolved external imports are intentionally
 * outside this v1 contract and are rejected rather than silently trusted.
 */
public final class TargetAdmission {
    private static final Set<String> TARGETS = Set.of(
            "server", "desktop", "browser", "ios", "android",
            "embedded", "edge-js", "edge-wasi");
    private static final Set<String> CAPABILITIES = Set.of(
            "clock", "random", "net.client", "net.server", "fs.read",
            "fs.write", "threads", "interop.java", "interop.native",
            "device.camera", "device.gpio", "dynamic.code", "heap", "gc");

    public record Metadata(Set<String> targets, Set<String> requires) {
        public Metadata {
            targets = Set.copyOf(targets);
            requires = Set.copyOf(requires);
        }
    }

    private TargetAdmission() { }

    /**
     * Enforce that the entry and every reachable Oreslang dependency declare
     * the build target, have requirements within the granted capability budget,
     * and make no unconditional import narrower than their own claimed targets.
     *
     * Unit keys must be source identities, not arbitrary package nicknames.
     * For resolver-generated import maps use the overload below.
     */
    public static Map<String, Metadata> validate(
            String entry, Map<String, String> sources, String target, Set<String> granted) {
        return validate(entry, sources, Map.of(), target, granted);
    }

    public static Map<String, Metadata> validate(
            String entry,
            Map<String, String> sources,
            Map<String, Map<String, String>> importResolutions,
            String target,
            Set<String> granted) {
        if (entry == null || sources == null || importResolutions == null
                || target == null || granted == null) {
            throw new IllegalArgumentException("target admission inputs cannot be null");
        }
        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("unknown Oreslang target environment '" + target + "'");
        }
        for (String cap : granted) {
            if (!CAPABILITIES.contains(cap)) {
                throw new IllegalArgumentException("unknown granted capability '" + cap + "'");
            }
        }

        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> unit : sources.entrySet()) {
            String id = ImportGraph.normalizeUnitId(unit.getKey());
            if (unit.getValue() == null || normalized.putIfAbsent(id, unit.getValue()) != null) {
                throw new IllegalArgumentException("null or duplicate source identity '" + id + "'");
            }
            if (!id.endsWith(".ores")) {
                throw new IllegalArgumentException("target admission v1 only accepts .ores units: " + id);
            }
        }

        String entryId = ImportGraph.normalizeUnitId(entry);
        if (!normalized.containsKey(entryId)) {
            throw new IllegalArgumentException("entry source not supplied: " + entryId);
        }

        Map<String, Metadata> metadata = new LinkedHashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(entryId);
        while (!pending.isEmpty()) {
            String unit = pending.removeFirst();
            if (metadata.containsKey(unit)) continue;

            String source = normalized.get(unit);
            Metadata own = header(unit, source);
            if (!own.targets().contains(target)) {
                throw new IllegalArgumentException(unit + " does not allow environment '" + target + "'");
            }
            if (!granted.containsAll(own.requires())) {
                throw new IllegalArgumentException(unit + " requires capabilities not granted for "
                        + target + ": " + difference(own.requires(), granted));
            }
            metadata.put(unit, own);

            Ast.Program program = Parser.parse(source);
            for (Ast.ImportDecl imported : program.imports()) {
                if (ImportRules.isJavaPath(imported.path())) {
                    if (!(target.equals("server") || target.equals("desktop"))
                            || !own.requires().contains("interop.java")) {
                        throw new IllegalArgumentException(unit + " imports Java host code '"
                                + imported.path() + "' without supported environment and interop.java");
                    }
                    // This declaration does not replace IsolatePolicy host-class allowlisting.
                    continue;
                }

                String dependency = ImportGraph.resolveImportUnitId(
                        unit, imported, normalized.keySet(), importResolutions);
                if (dependency == null) {
                    throw new IllegalArgumentException(unit + " has unresolved import '"
                            + imported.path() + "' in target admission closure");
                }
                Metadata other = header(dependency, normalized.get(dependency));
                if (!other.targets().containsAll(own.targets())) {
                    throw new IllegalArgumentException(unit + " claims environments " + own.targets()
                            + " but dependency " + dependency + " supports only " + other.targets());
                }
                if (!own.requires().containsAll(other.requires())) {
                    throw new IllegalArgumentException(unit + " must declare imported capabilities "
                            + difference(other.requires(), own.requires()) + " from " + dependency);
                }
                pending.add(dependency);
            }
        }
        return Map.copyOf(metadata);
    }

    private static Set<String> difference(Set<String> lhs, Set<String> rhs) {
        LinkedHashSet<String> result = new LinkedHashSet<>(lhs);
        result.removeAll(rhs);
        return Set.copyOf(result);
    }

    /**
     * v1 uses comment directives so existing parser grammar stays compatible.
     * The first physical line is mandatory; the second line is optional.
     * A future syntax revision may promote these to first-class AST headers.
     */
    public static Metadata header(String unit, String source) {
        if (unit == null || source == null) {
            throw new IllegalArgumentException("unit and source cannot be null");
        }
        String normalized = source.startsWith("\uFEFF") ? source.substring(1) : source;
        String[] lines = normalized.split("\\R", -1);
        if (lines.length == 0 || !lines[0].startsWith("// ores:targets=")) {
            throw new IllegalArgumentException(unit + " must begin with // ores:targets=...");
        }
        Set<String> targets = values(unit, "targets",
                lines[0].substring("// ores:targets=".length()), TARGETS);
        Set<String> requires = Set.of();
        int headerCount = 1;
        if (lines.length > 1 && lines[1].startsWith("// ores:requires=")) {
            requires = values(unit, "requires",
                    lines[1].substring("// ores:requires=".length()), CAPABILITIES);
            headerCount = 2;
        }
        for (int i = headerCount; i < lines.length; i++) {
            if (lines[i].startsWith("// ores:")) {
                throw new IllegalArgumentException(unit + ":" + (i + 1)
                        + " has misplaced, duplicate, or unknown ores header");
            }
        }
        return new Metadata(targets, requires);
    }

    private static Set<String> values(String unit, String key, String raw, Set<String> known) {
        if (raw.isBlank() || raw.length() > 256) {
            throw new IllegalArgumentException(unit + ": empty/oversized ores:" + key);
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",", -1)) {
            String name = token.trim();
            if (!known.contains(name)) {
                throw new IllegalArgumentException(unit + ": unknown ores:" + key + " value '" + name + "'");
            }
            if (!values.add(name)) {
                throw new IllegalArgumentException(unit + ": duplicate ores:" + key + " value '" + name + "'");
            }
        }
        return Set.copyOf(values);
    }
}
