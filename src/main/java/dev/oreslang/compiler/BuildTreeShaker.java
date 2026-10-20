package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closed-world, cross-code-unit tree shaking for an already validated
 * IncrementalCompiler build.
 *
 * <p>Validation deliberately happens before this pass. This class never turns
 * unreachable invalid source into valid source. It only derives the smaller
 * artifact graph from checked programs.</p>
 */
public final class BuildTreeShaker {
    private BuildTreeShaker() { }

    public record Result(
            IncrementalCompiler.BuildResult build,
            Map<String, Map<String, String>> importResolutions,
            Map<String, ReachabilityManifest> manifests,
            Set<String> removedUnits) {
        public Result {
            importResolutions = freezeNested(importResolutions);
            manifests = Map.copyOf(manifests);
            removedUnits = Set.copyOf(removedUnits);
        }
    }

    private record Demand(Set<String> roots, boolean preservePublicApi) {
        private Demand {
            roots = Set.copyOf(roots);
        }

        Demand merge(Collection<String> additionalRoots, boolean preserve) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(roots);
            merged.addAll(additionalRoots);
            return new Demand(merged, preservePublicApi || preserve);
        }
    }

    private record ImportDemand(Set<String> roots, boolean preservePublicApi) {
        private ImportDemand {
            roots = Set.copyOf(roots);
        }
    }

    public static Result shake(
            IncrementalCompiler.BuildResult checkedBuild,
            Map<String, Map<String, String>> importResolutions,
            String entryUnitId,
            BuildOptions entryOptions) {
        if (checkedBuild == null) throw new IllegalArgumentException("checked build cannot be null");
        if (importResolutions == null) throw new IllegalArgumentException("import resolutions cannot be null");
        if (entryOptions == null) throw new IllegalArgumentException("build options cannot be null");
        if (entryUnitId == null || entryUnitId.isBlank()) {
            throw new IllegalArgumentException("entry unit id cannot be blank");
        }
        if (!checkedBuild.units().containsKey(entryUnitId)) {
            throw new IllegalArgumentException("entry unit is not present in checked build: " + entryUnitId);
        }

        Map<String, Boolean> initializationEffects =
                computeInitializationEffects(checkedBuild.units());

        LinkedHashMap<String, Demand> demands = new LinkedHashMap<>();
        ArrayDeque<String> work = new ArrayDeque<>();
        mergeDemand(
                demands,
                work,
                entryUnitId,
                entryOptions.entryPoints(),
                entryOptions.preservePublicApi());

        LinkedHashMap<String, TreeShaker.Result> shaken = new LinkedHashMap<>();
        LinkedHashMap<String, Ast.Program> prunedPrograms = new LinkedHashMap<>();
        LinkedHashMap<String, List<Ast.ImportDecl>> retainedImports = new LinkedHashMap<>();

        while (!work.isEmpty()) {
            String unitId = work.removeFirst();
            IncrementalCompiler.CompiledUnit unit = checkedBuild.units().get(unitId);
            if (unit == null) {
                throw new IllegalArgumentException("reachable import target is missing: " + unitId);
            }

            Demand demand = demands.get(unitId);
            LinkedHashSet<String> roots = new LinkedHashSet<>(demand.roots());
            roots.addAll(initRoots(unit.program()));

            // Build defines currently belong to the entry compilation unit.
            // Imported-unit overrides need an explicit unit-qualified syntax
            // before they can be propagated without ambiguity.
            Map<String, String> defines = unitId.equals(entryUnitId)
                    ? entryOptions.defines()
                    : Map.of();

            TreeShaker.Result result = TreeShaker.shake(
                    unit.program(),
                    new BuildOptions(defines, roots, demand.preservePublicApi()));

            LinkedHashSet<Ast.ImportDecl> usedImports =
                    new LinkedHashSet<>(result.program().imports());
            LinkedHashSet<Ast.ImportDecl> imports =
                    new LinkedHashSet<>(usedImports);

            // Preserve initialization semantics. If importing a unit can cause
            // a direct or transitive init hook to execute, the edge itself is
            // observable even when no imported symbol is otherwise referenced.
            for (Ast.ImportDecl original : unit.program().imports()) {
                String target = resolvedTarget(importResolutions, unitId, original.path());
                if (target != null && initializationEffects.getOrDefault(target, false)) {
                    imports.add(original);
                } else if (target == null && !ImportRules.isJavaPath(original.path())) {
                    // An unresolved Ores/package import is open-world: its
                    // initialization behavior is unknowable at this stage.
                    imports.add(original);
                }
            }

            Ast.Program pruned = new Ast.Program(
                    result.program().namespace(),
                    List.copyOf(imports),
                    result.program().modules());

            shaken.put(unitId, result);
            prunedPrograms.put(unitId, pruned);
            retainedImports.put(unitId, List.copyOf(imports));

            for (Ast.ImportDecl imported : imports) {
                String targetId = resolvedTarget(importResolutions, unitId, imported.path());
                if (targetId == null) continue;
                IncrementalCompiler.CompiledUnit target = checkedBuild.units().get(targetId);
                if (target == null) {
                    throw new IllegalArgumentException(
                            "resolved import target '" + targetId + "' is not in checked build");
                }
                ImportDemand importedDemand = usedImports.contains(imported)
                        ? demandForImport(imported, target.program())
                        : new ImportDemand(Set.of(), false);
                mergeDemand(
                        demands,
                        work,
                        targetId,
                        importedDemand.roots(),
                        importedDemand.preservePublicApi());
            }
        }

        LinkedHashSet<String> retainedUnitIds = new LinkedHashSet<>(demands.keySet());
        LinkedHashSet<String> removedUnits = new LinkedHashSet<>(checkedBuild.units().keySet());
        removedUnits.removeAll(retainedUnitIds);

        LinkedHashMap<String, IncrementalCompiler.CompiledUnit> units = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, String>> prunedResolutions = new LinkedHashMap<>();
        LinkedHashMap<String, ReachabilityManifest> manifests = new LinkedHashMap<>();

        for (String unitId : retainedUnitIds) {
            IncrementalCompiler.CompiledUnit original = checkedBuild.units().get(unitId);
            Ast.Program pruned = prunedPrograms.get(unitId);
            if (pruned == null) {
                throw new IllegalStateException("tree-shake result missing for retained unit " + unitId);
            }

            LinkedHashMap<String, String> resolutions = new LinkedHashMap<>();
            LinkedHashSet<String> dependencies = new LinkedHashSet<>();
            for (Ast.ImportDecl imported : retainedImports.getOrDefault(unitId, List.of())) {
                String target = resolvedTarget(importResolutions, unitId, imported.path());
                if (target == null) continue;
                if (!retainedUnitIds.contains(target)) {
                    throw new IllegalStateException(
                            "retained import points at removed unit '" + target + "'");
                }
                resolutions.put(imported.path(), target);
                dependencies.add(target);
            }
            if (!resolutions.isEmpty()) prunedResolutions.put(unitId, Map.copyOf(resolutions));

            units.put(unitId, new IncrementalCompiler.CompiledUnit(
                    original.unitId(),
                    original.packageId(),
                    original.namespace(),
                    original.sourceDigest(),
                    original.abiDigest(),
                    dependencies,
                    original.sourceText(),
                    pruned));
            manifests.put(unitId, shaken.get(unitId).manifest());
        }

        LinkedHashSet<String> rebuilt = new LinkedHashSet<>(checkedBuild.rebuiltUnits());
        rebuilt.retainAll(retainedUnitIds);
        LinkedHashSet<String> reused = new LinkedHashSet<>(checkedBuild.reusedUnits());
        reused.retainAll(retainedUnitIds);

        List<List<String>> initializationGroups = new ArrayList<>();
        for (List<String> group : checkedBuild.initializationGroups()) {
            List<String> retained = group.stream()
                    .filter(retainedUnitIds::contains)
                    .toList();
            if (!retained.isEmpty()) initializationGroups.add(retained);
        }

        IncrementalCompiler.BuildResult prunedBuild =
                new IncrementalCompiler.BuildResult(
                        units,
                        rebuilt,
                        reused,
                        initializationGroups);
        return new Result(prunedBuild, prunedResolutions, manifests, removedUnits);
    }

    private static void mergeDemand(
            Map<String, Demand> demands,
            ArrayDeque<String> work,
            String unitId,
            Collection<String> roots,
            boolean preservePublicApi) {
        Demand previous = demands.get(unitId);
        Demand next = previous == null
                ? new Demand(new LinkedHashSet<>(roots), preservePublicApi)
                : previous.merge(roots, preservePublicApi);
        if (!next.equals(previous)) {
            demands.put(unitId, next);
            work.addLast(unitId);
        }
    }

    private static Set<String> initRoots(Ast.Program program) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function
                        && function.name().equals("init")) {
                    roots.add(qualify(module.name(), function.name()));
                }
            }
        }
        return roots;
    }

    private static Map<String, Boolean> computeInitializationEffects(
            Map<String, IncrementalCompiler.CompiledUnit> units) {
        LinkedHashMap<String, Boolean> effectful = new LinkedHashMap<>();
        for (Map.Entry<String, IncrementalCompiler.CompiledUnit> entry : units.entrySet()) {
            effectful.put(entry.getKey(), !initRoots(entry.getValue().program()).isEmpty());
        }

        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, IncrementalCompiler.CompiledUnit> entry : units.entrySet()) {
                if (effectful.get(entry.getKey())) continue;
                for (String dependency : entry.getValue().dependencies()) {
                    if (effectful.getOrDefault(dependency, false)) {
                        effectful.put(entry.getKey(), true);
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return Map.copyOf(effectful);
    }

    private static ImportDemand demandForImport(
            Ast.ImportDecl imported,
            Ast.Program target) {
        if (imported.wildcard()
                || imported.kind() == Ast.ImportKind.MODULE
                || imported.kind() == Ast.ImportKind.ALL
                || imported.names().isEmpty()) {
            return new ImportDemand(Set.of(), true);
        }

        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (String name : imported.names()) {
            LinkedHashSet<String> modules = new LinkedHashSet<>();
            for (Ast.ModuleDecl module : target.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (name.equals(declarationName(declaration))) modules.add(module.name());
                }
            }
            if (modules.size() != 1) {
                // The validated linker understands more import context than
                // this generic pass. Fall back to public-API retention rather
                // than guessing across an ambiguous/opaque selection.
                return new ImportDemand(Set.of(), true);
            }
            roots.add(qualify(modules.getFirst(), name));
        }
        return new ImportDemand(roots, false);
    }

    private static String resolvedTarget(
            Map<String, Map<String, String>> importResolutions,
            String importerId,
            String importPath) {
        Map<String, String> importer = importResolutions.get(importerId);
        return importer == null ? null : importer.get(importPath);
    }

    private static String declarationName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl function) return function.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        return null;
    }

    private static String qualify(String module, String name) {
        return module + "." + name;
    }

    private static Map<String, Map<String, String>> freezeNested(
            Map<String, Map<String, String>> values) {
        LinkedHashMap<String, Map<String, String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
            frozen.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }
}
