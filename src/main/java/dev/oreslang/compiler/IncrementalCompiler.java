package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File-granular incremental compiler.
 *
 * Each source file is an independently versioned compilation/code unit. The
 * current invalidation rule is deliberately conservative: a source change
 * recompiles that unit and the full reverse-import closure. Future ABI digests
 * may narrow that closure without changing the unit model.
 */
public final class IncrementalCompiler {
    private final Map<String, CompiledUnit> cache = new LinkedHashMap<>();

    public synchronized BuildResult compile(Map<String, String> sources) {
        if (sources.isEmpty()) return new BuildResult(Map.of(), Set.of(), Set.of());

        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String id = normalizeUnitId(entry.getKey());
            if (normalized.putIfAbsent(id, entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate source unit '" + id + "'");
            }
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, Ast.Program> parsed = new LinkedHashMap<>();
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            hashes.put(entry.getKey(), digest(entry.getValue()));
            Ast.Program program = Parser.parse(entry.getValue());
            parsed.put(entry.getKey(), program);
            dependencies.put(entry.getKey(), resolveDependencies(entry.getKey(), program, normalized.keySet()));
        }

        LinkedHashSet<String> dirty = new LinkedHashSet<>();
        for (String id : normalized.keySet()) {
            CompiledUnit previous = cache.get(id);
            if (previous == null || !previous.sourceDigest().equals(hashes.get(id))) dirty.add(id);
        }

        Map<String, Set<String>> reverse = reverseDependencies(dependencies);
        ArrayDeque<String> queue = new ArrayDeque<>(dirty);
        while (!queue.isEmpty()) {
            String changed = queue.removeFirst();
            for (String dependent : reverse.getOrDefault(changed, Set.of())) {
                if (dirty.add(dependent)) queue.addLast(dependent);
            }
        }

        LinkedHashMap<String, CompiledUnit> next = new LinkedHashMap<>();
        LinkedHashSet<String> rebuilt = new LinkedHashSet<>();
        LinkedHashSet<String> reused = new LinkedHashSet<>();

        for (String id : normalized.keySet()) {
            if (!dirty.contains(id) && cache.containsKey(id)) {
                next.put(id, cache.get(id));
                reused.add(id);
                continue;
            }

            Ast.Program checked = OresCompiler.parseAndTypeCheck(normalized.get(id));
            CompiledUnit unit = new CompiledUnit(
                    id,
                    packageId(id, checked),
                    checked.namespace(),
                    hashes.get(id),
                    dependencies.get(id),
                    normalized.get(id),
                    checked);
            next.put(id, unit);
            rebuilt.add(id);
        }

        cache.keySet().retainAll(normalized.keySet());
        cache.putAll(next);
        return new BuildResult(Map.copyOf(next), Set.copyOf(rebuilt), Set.copyOf(reused));
    }

    public synchronized void clear() {
        cache.clear();
    }

    private static Set<String> resolveDependencies(String unitId, Ast.Program program, Set<String> available) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Path parent = Path.of(unitId).getParent();
        for (Ast.ImportDecl imported : program.imports()) {
            String raw = imported.path().replace('\\', '/');
            Path candidatePath = raw.startsWith(".")
                    ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                    : Path.of(raw).normalize();
            String candidate = normalizeUnitId(candidatePath.toString());
            if (!available.contains(candidate) && !candidate.endsWith(".ores") && available.contains(candidate + ".ores")) {
                candidate += ".ores";
            }
            if (available.contains(candidate)) result.add(candidate);
        }
        return Set.copyOf(result);
    }

    private static Map<String, Set<String>> reverseDependencies(Map<String, Set<String>> dependencies) {
        LinkedHashMap<String, Set<String>> reverse = new LinkedHashMap<>();
        for (String id : dependencies.keySet()) reverse.put(id, new LinkedHashSet<>());
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                reverse.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        LinkedHashMap<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : reverse.entrySet()) frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        return Map.copyOf(frozen);
    }

    private static String packageId(String unitId, Ast.Program program) {
        if (program.namespace() != null) return program.namespace();
        String id = unitId;
        if (id.endsWith(".ores")) id = id.substring(0, id.length() - ".ores".length());
        return id;
    }

    private static String normalizeUnitId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("source unit id cannot be blank");
        return Path.of(id).normalize().toString().replace('\\', '/');
    }

    private static String digest(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record CompiledUnit(
            String unitId,
            String packageId,
            String namespace,
            String sourceDigest,
            Set<String> dependencies,
            String sourceText,
            Ast.Program program) {
        public CompiledUnit {
            dependencies = Set.copyOf(dependencies);
        }
    }

    public record BuildResult(
            Map<String, CompiledUnit> units,
            Set<String> rebuiltUnits,
            Set<String> reusedUnits) {
        public BuildResult {
            units = Map.copyOf(units);
            rebuiltUnits = Set.copyOf(rebuiltUnits);
            reusedUnits = Set.copyOf(reusedUnits);
        }

        public boolean rebuilt(String unitId) { return rebuiltUnits.contains(normalizeUnitId(unitId)); }
        public boolean reused(String unitId) { return reusedUnits.contains(normalizeUnitId(unitId)); }
    }
}
