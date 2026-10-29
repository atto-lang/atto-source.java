package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manifest-authorized namespace provider index. Namespace spelling alone never
 * establishes ownership: every provider is admitted by one explicit v2
 * manifest [packages.<key>].files list, scoped to its canonical manifest.
 *
 * This is build/link metadata; it does not construct runtime namespace objects.
 */
public final class PackageNamespaceIndex {
    public record Symbol(String name, String kind, int arity, String sourceUnitId) { }
    public record Binding(String packageId, String namespace,
                          Map<String, Symbol> exports, Set<String> providerUnitIds) {
        public Binding {
            exports = Map.copyOf(exports);
            providerUnitIds = Set.copyOf(providerUnitIds);
        }
    }
    private record NamespaceKey(String packageId, String namespace) { }

    private final Map<String, String> ownerByUnit;
    private final Map<NamespaceKey, Binding> bindings;
    private final Map<String, String> dependencyAliases;

    private PackageNamespaceIndex(Map<String, String> owners,
                                  Map<NamespaceKey, Binding> bindings,
                                  Map<String, String> aliases) {
        ownerByUnit = Map.copyOf(owners);
        this.bindings = Map.copyOf(bindings);
        dependencyAliases = Map.copyOf(aliases);
    }

    /**
     * Dependency aliases are supplied by a trusted, pinned package resolver,
     * mapping an import alias to an *exact* package instance ID returned by
     * packageId(manifest,key). No ambient directory scan or package-name match.
     *
     * The admitted source set must contain the exact canonical file paths.
     */
    public static PackageNamespaceIndex load(
            List<Path> manifests,
            Map<String, String> sources,
            Map<String, String> pinnedDependencyAliases) throws IOException {
        if (manifests == null || sources == null || pinnedDependencyAliases == null) {
            throw new IllegalArgumentException("namespace index inputs cannot be null");
        }
        if (manifests.size() > 128 || sources.size() > 4096) {
            throw new IllegalArgumentException("namespace admission budget exceeded");
        }

        Map<String, String> admittedSources = new LinkedHashMap<>();
        long sourceBytes = 0;
        for (Map.Entry<String, String> source : sources.entrySet()) {
            if (source.getValue() == null) throw new IllegalArgumentException("null admitted source");
            int unitBytes = source.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            sourceBytes += unitBytes;
            if (unitBytes > 8 * 1024 * 1024 || sourceBytes > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("namespace source snapshot exceeds byte budget");
            }
            String canonical = unitId(Path.of(source.getKey()).toRealPath());
            if (admittedSources.putIfAbsent(canonical, source.getValue()) != null) {
                throw new IllegalArgumentException("source path alias/duplicate: " + canonical);
            }
        }

        Map<String, String> owners = new LinkedHashMap<>();
        Map<NamespaceKey, Map<String, Symbol>> exports = new LinkedHashMap<>();
        Map<NamespaceKey, Set<String>> providers = new LinkedHashMap<>();
        Set<String> packageIds = new LinkedHashSet<>();
        for (Path manifest : manifests) {
            Path manifestReal = manifest.toRealPath();
            if (!".ores.pkg.toml".equals(manifestReal.getFileName().toString())) {
                throw new IllegalArgumentException("expected .ores.pkg.toml: " + manifest);
            }
            TomlParseResult toml = Toml.parse(manifestReal);
            if (!toml.errors().isEmpty()) {
                throw new IllegalArgumentException("invalid package manifest: " + toml.errors().getFirst());
            }
            if (!"2".equals(toml.getString("schema_version"))) {
                throw new IllegalArgumentException(".ores.pkg.toml requires schema_version = \"2\"");
            }
            Path manifestDir = manifestReal.getParent();
            String rootText = toml.getString("project.root");
            Path root = boundedRelative(manifestDir, rootText == null ? "." : rootText, manifestDir);
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("package project root is not a directory: " + root);
            }
            Object packageObject = toml.get("packages");
            if (!(packageObject instanceof TomlTable packageTable) || packageTable.size() == 0) {
                throw new IllegalArgumentException("manifest must declare [packages.<key>].files");
            }

            for (String packageKey : packageTable.keySet()) {
                Object raw = packageTable.get(packageKey);
                if (!(raw instanceof TomlTable definition)) {
                    throw new IllegalArgumentException("package entry must be a table: " + packageKey);
                }
                if (!packageKey.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) {
                    throw new IllegalArgumentException("invalid package key: " + packageKey);
                }
                if (definition.size() != 1 || !(definition.get("files") instanceof TomlArray files)
                        || files.size() == 0 || files.size() > 4096) {
                    throw new IllegalArgumentException("package must declare bounded explicit files: " + packageKey);
                }
                String packageId = packageId(manifestReal, packageKey);
                if (!packageIds.add(packageId)) {
                    throw new IllegalArgumentException("duplicate package instance: " + packageId);
                }
                for (int i = 0; i < files.size(); i++) {
                    String filePath = files.getString(i);
                    if (filePath == null || !filePath.endsWith(".ores")) {
                        throw new IllegalArgumentException("package member must be an .ores file");
                    }
                    Path sourcePath = boundedRelative(root, filePath, root);
                    // A nested manifest cannot silently transfer ownership
                    // of a child source into an ancestor package's file list.
                    for (Path cursor = sourcePath.getParent(); cursor != null && !cursor.equals(root);
                         cursor = cursor.getParent()) {
                        if (Files.isRegularFile(cursor.resolve(".ores.pkg.toml"))) {
                            throw new IllegalArgumentException(
                                    "package member crosses nested manifest boundary: " + sourcePath);
                        }
                    }
                    if (!Files.isRegularFile(sourcePath)) {
                        throw new IllegalArgumentException("missing package member: " + filePath);
                    }
                    String sourceId = unitId(sourcePath.toRealPath());
                    String owner = owners.putIfAbsent(sourceId, packageId);
                    if (owner != null) {
                        throw new IllegalArgumentException("file belongs to multiple/duplicate packages: " + sourceId);
                    }
                    String source = admittedSources.get(sourceId);
                    if (source == null) {
                        throw new IllegalArgumentException("manifest member not in admitted source snapshot: " + sourceId);
                    }
                    Ast.Program parsed = Parser.parse(source);
                    if (parsed.namespace() == null || Parser.ROOT_MODULE.equals(parsed.namespace())) {
                        throw new IllegalArgumentException("package member needs a nonreserved namespace: " + sourceId);
                    }
                    NamespaceKey ns = new NamespaceKey(packageId, parsed.namespace());
                    providers.computeIfAbsent(ns, unused -> new LinkedHashSet<>()).add(sourceId);
                    Map<String, Symbol> namespaceExports =
                            exports.computeIfAbsent(ns, unused -> new LinkedHashMap<>());
                    for (Ast.ModuleDecl module : parsed.modules()) {
                        if (!Parser.ROOT_MODULE.equals(module.name())) {
                            // Named modules have their own value scopes. Export
                            // only the module itself; never flatten its public
                            // members into the file-root namespace.
                            put(namespaceExports, new Symbol(module.name(), "module", -1, sourceId));
                            continue;
                        }
                        for (Ast.Decl decl : module.declarations()) {
                            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                                put(namespaceExports, new Symbol(fn.name(), "function",
                                        fn.parameters().size(), sourceId));
                            } else if (decl instanceof Ast.ClassDecl klass
                                    && klass.visibility() == Ast.Visibility.PUBLIC) {
                                put(namespaceExports, new Symbol(klass.name(),
                                        klass.actorKind() == Ast.ActorKind.NONE ? "class" : "actor", -1, sourceId));
                            } else if (decl instanceof Ast.InterfaceDecl iface
                                    && iface.visibility() == Ast.Visibility.PUBLIC) {
                                put(namespaceExports, new Symbol(iface.name(),
                                        iface.moduleContract() ? "contract" : "interface", -1, sourceId));
                            } else if (decl instanceof Ast.TypeAliasDecl alias
                                    && alias.visibility() == Ast.Visibility.PUBLIC) {
                                put(namespaceExports, new Symbol(alias.name(), "type", -1, sourceId));
                            } else if (decl instanceof Ast.FieldDecl field
                                    && field.visibility() == Ast.Visibility.PUBLIC) {
                                put(namespaceExports, new Symbol(field.name(), "field", -1, sourceId));
                            }
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, String> alias : pinnedDependencyAliases.entrySet()) {
            if (!alias.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,127}")
                    || !packageIds.contains(alias.getValue())) {
                throw new IllegalArgumentException("dependency alias is not pinned to an admitted package: "
                        + alias.getKey());
            }
        }

        Map<NamespaceKey, Binding> all = new LinkedHashMap<>();
        for (Map.Entry<NamespaceKey, Set<String>> entry : providers.entrySet()) {
            NamespaceKey ns = entry.getKey();
            all.put(ns, new Binding(ns.packageId(), ns.namespace(),
                    exports.getOrDefault(ns, Map.of()), entry.getValue()));
        }
        return new PackageNamespaceIndex(owners, all, pinnedDependencyAliases);
    }

    public static String packageId(Path manifest, String packageKey) throws IOException {
        if (packageKey == null || !packageKey.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("invalid package key");
        }
        return "pkg:" + unitId(manifest.toRealPath()) + "#" + packageKey;
    }

    public String ownerOf(String sourceUnitId) {
        return ownerByUnit.get(unitId(Path.of(sourceUnitId)));
    }

    public Binding resolve(String importerUnitId, String packageLocator, String namespace) {
        if (namespace == null || namespace.isBlank() || Parser.ROOT_MODULE.equals(namespace)) {
            throw new IllegalArgumentException("invalid imported namespace");
        }
        String packageId;
        if ("+/".equals(packageLocator)) {
            packageId = ownerOf(importerUnitId);
            if (packageId == null) throw new IllegalArgumentException(
                    "same-package import requires manifest-owned importer: " + importerUnitId);
        } else {
            packageId = dependencyAliases.get(packageLocator);
            if (packageId == null) throw new IllegalArgumentException(
                    "namespace dependency alias is not pinned: " + packageLocator);
        }
        Binding binding = bindings.get(new NamespaceKey(packageId, namespace));
        if (binding == null) {
            throw new IllegalArgumentException(
                    "namespace '" + namespace + "' has no providers in resolved package " + packageId);
        }
        return binding;
    }

    private static void put(Map<String, Symbol> exports, Symbol symbol) {
        String key = symbol.kind() + ":" + symbol.name() + ":" + symbol.arity();
        for (Symbol prior : exports.values()) {
            if (!prior.name().equals(symbol.name())) continue;
            boolean bothFunctions = "function".equals(prior.kind())
                    && "function".equals(symbol.kind());
            if (bothFunctions && prior.arity() != symbol.arity()) continue;
            // A namespace qualifier cannot decide which physical provider to
            // use for a field/module/class and a function with the same name.
            throw new IllegalArgumentException("ambiguous exported namespace symbol "
                    + symbol.name() + " from " + prior.sourceUnitId()
                    + " and " + symbol.sourceUnitId());
        }
        Symbol old = exports.putIfAbsent(key, symbol);
        if (old != null) {
            throw new IllegalArgumentException("ambiguous exported namespace symbol " + key
                    + " from " + old.sourceUnitId() + " and " + symbol.sourceUnitId());
        }
    }

    private static Path boundedRelative(Path base, String value, Path boundary) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0
                || value.indexOf('\u0000') >= 0 || value.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid package path");
        }
        Path relative = Path.of(value);
        if (relative.isAbsolute()) throw new IllegalArgumentException("absolute package path rejected");
        for (Path part : relative) {
            if ("..".equals(part.toString())) throw new IllegalArgumentException("package path traversal rejected");
        }
        Path absolute = base.resolve(relative).normalize();
        if (!absolute.startsWith(boundary)) throw new IllegalArgumentException("package path escapes root");
        Path current = boundary;
        if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("symlinked package root rejected");
        for (Path part : boundary.relativize(absolute)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("package symlink rejected: " + current);
        }
        if (Files.exists(absolute) && !absolute.toRealPath().startsWith(boundary.toRealPath())) {
            throw new IllegalArgumentException("canonical package path escapes root");
        }
        return absolute;
    }

    private static String unitId(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
