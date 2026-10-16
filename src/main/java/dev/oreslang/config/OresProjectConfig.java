package dev.oreslang.config;

import dev.oreslang.imports.ImportRules;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Project-level Oreslang compiler configuration.
 *
 * <p>The nearest .oreslangc.cfg.toml found by walking upward from the source
 * file (or current working directory) owns the project. Manifest paths are
 * resolved relative to [project].root. Ambient ORESLANG_PATH entries are
 * process-relative and are searched after project-local roots.</p>
 */
public final class OresProjectConfig {
    public static final String MANIFEST_NAME = ".oreslangc.cfg.toml";
    public static final String PACKAGE_MANIFEST_NAME = ".ores.pkg.toml";
    public static final String ENV_ORES_PATHS = "ORES_PATHS";
    public static final String ENV_ORESLANG_PATH = "ORESLANG_PATH";
    public static final String SCHEMA_VERSION = "1";

    private final Path manifestPath;
    private final Path projectRoot;
    private final String projectName;
    private final String projectVersion;
    private final List<Path> sourceRoots;
    private final List<Path> importPaths;
    private final List<Path> environmentPaths;
    private final Map<String, Path> dependencyRoots;
    private final Path mainEntrypoint;
    private final Map<String, String> environment;
    // Physical root of the installed package, if this is a dependency context.
    private final Path discoveryBoundary;

    private OresProjectConfig(
            Path manifestPath,
            Path projectRoot,
            String projectName,
            String projectVersion,
            List<Path> sourceRoots,
            List<Path> importPaths,
            List<Path> environmentPaths,
            Map<String, Path> dependencyRoots,
            Path mainEntrypoint,
            Map<String, String> environment,
            Path discoveryBoundary) {
        this.manifestPath = manifestPath;
        this.projectRoot = projectRoot;
        this.projectName = projectName;
        this.projectVersion = projectVersion;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.importPaths = List.copyOf(importPaths);
        this.environmentPaths = List.copyOf(environmentPaths);
        this.dependencyRoots = Map.copyOf(dependencyRoots);
        this.mainEntrypoint = mainEntrypoint;
        this.environment = Map.copyOf(environment);
        this.discoveryBoundary = discoveryBoundary;
    }

    public static OresProjectConfig discover(Path start, Map<String, String> environment) throws IOException {
        return discover(start, environment, null);
    }

    private static OresProjectConfig discover(
            Path start, Map<String, String> environment, Path boundary) throws IOException {
        if (start == null) throw new IllegalArgumentException("config discovery start path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path searchFrom = directoryFor(start);
        Path physicalBoundary = boundary == null ? null : boundary.toRealPath();
        // Individual source symlinks may lead outside the installed package.
        // Such a source still inherits its declared package's root; never
        // discover manifests in an unrelated physical parent directory.
        if (physicalBoundary != null && !searchFrom.startsWith(physicalBoundary)) {
            searchFrom = physicalBoundary;
        }
        Path nearestLegacy = null;
        for (Path cursor = searchFrom; cursor != null; cursor = cursor.getParent()) {
            if (physicalBoundary != null && !cursor.startsWith(physicalBoundary)) break;
            // @/ must always bind the nearest package manifest, even when a
            // legacy compiler config exists in a lower source directory.
            Path packageManifest = cursor.resolve(PACKAGE_MANIFEST_NAME);
            if (Files.isRegularFile(packageManifest)) {
                return load(packageManifest, environment).withDiscoveryBoundary(physicalBoundary);
            }
            Path legacyManifest = cursor.resolve(MANIFEST_NAME);
            if (nearestLegacy == null && Files.isRegularFile(legacyManifest)) {
                nearestLegacy = legacyManifest;
            }
        }
        if (nearestLegacy != null) {
            return load(nearestLegacy, environment).withDiscoveryBoundary(physicalBoundary);
        }

        Path root = physicalBoundary == null ? searchFrom.toAbsolutePath().normalize() : physicalBoundary;
        return new OresProjectConfig(
                null,
                root,
                null,
                null,
                List.of(root),
                List.of(),
                parseEnvironmentPaths(environment),
                Map.of(),
                null,
                environment,
                physicalBoundary);
    }

    public static OresProjectConfig load(Path manifest, Map<String, String> environment) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path absoluteManifest = manifest.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteManifest)) {
            throw new IllegalArgumentException("not an Oreslang project manifest: " + absoluteManifest);
        }

        String manifestName = absoluteManifest.getFileName().toString();
        boolean packageManifest = PACKAGE_MANIFEST_NAME.equals(manifestName);
        if (!packageManifest && !MANIFEST_NAME.equals(manifestName)) {
            throw new IllegalArgumentException("unsupported Oreslang manifest: " + absoluteManifest);
        }
        TomlParseResult toml = Toml.parse(absoluteManifest);
        if (!toml.errors().isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid " + manifestName + ": " + toml.errors().getFirst());
        }

        String schemaVersion = toml.getString("schema_version");
        if (schemaVersion == null || !SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    manifestName + " requires schema_version = \"" + SCHEMA_VERSION + "\"");
        }

        Path manifestDir = absoluteManifest.getParent();
        // @/ always means the directory containing .ores.pkg.toml.
        // Legacy compiler manifests keep their explicit project.root behavior.
        String rootText = defaultString(toml.getString("project.root"), ".");
        if (packageManifest && !".".equals(rootText)) {
            throw new IllegalArgumentException(PACKAGE_MANIFEST_NAME
                    + " cannot override its physical manifest directory with project.root");
        }
        Path projectRoot = packageManifest ? manifestDir : resolvePath(manifestDir, rootText);

        List<String> sourceRootValues = stringArray(toml, "source.roots");
        if (sourceRootValues.isEmpty()) sourceRootValues = List.of(".");
        List<Path> sourceRoots = resolvePaths(projectRoot, sourceRootValues);
        List<Path> importPaths = resolvePaths(projectRoot, stringArray(toml, "source.import_paths"));

        String mainText = toml.getString("entrypoints.main");
        Path mainEntrypoint = mainText == null ? null : resolvePath(projectRoot, mainText);

        return new OresProjectConfig(
                absoluteManifest,
                projectRoot,
                toml.getString(packageManifest ? "package.name" : "project.name"),
                toml.getString(packageManifest ? "package.version" : "project.version"),
                sourceRoots,
                importPaths,
                parseEnvironmentPaths(environment),
                packageManifest ? parseDependencies(toml, projectRoot) : Map.of(),
                mainEntrypoint,
                environment,
                null);
    }

    public Optional<Path> manifestPath() { return Optional.ofNullable(manifestPath); }
    public Path projectRoot() { return projectRoot; }
    public Optional<String> projectName() { return Optional.ofNullable(projectName); }
    public Optional<String> projectVersion() { return Optional.ofNullable(projectVersion); }
    public List<Path> sourceRoots() { return sourceRoots; }
    public List<Path> importPaths() { return importPaths; }
    public List<Path> environmentPaths() { return environmentPaths; }
    public Map<String, Path> dependencyRoots() { return dependencyRoots; }
    public Optional<Path> mainEntrypoint() { return Optional.ofNullable(mainEntrypoint); }

    private OresProjectConfig withDiscoveryBoundary(Path boundary) {
        if (boundary == null) return this;
        // A compiler manifest inside a dependency must not redirect its
        // declared project root upward into the importing application.
        if (!projectRoot.toAbsolutePath().normalize().startsWith(boundary.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("imported project root escapes installed package: " + projectRoot);
        }
        return new OresProjectConfig(manifestPath, projectRoot, projectName, projectVersion,
                sourceRoots, importPaths, environmentPaths, dependencyRoots, mainEntrypoint,
                environment, boundary);
    }

    /** Re-evaluate the nearest manifest without crossing an installed package boundary. */
    public OresProjectConfig forImportedSource(Path importedSource) throws IOException {
        OresProjectConfig found = discover(importedSource, environment, discoveryBoundary);
        if (found.manifestPath != null) return found;
        if (discoveryBoundary == null
                && (manifestPath == null
                    || MANIFEST_NAME.equals(manifestPath.getFileName().toString()))) {
            // The original resolver propagated its source roots through the
            // entire import closure. Keep that behavior for manifest-less and
            // legacy projects, without overriding an explicit nested manifest.
            return this;
        }
        return found;
    }

    /** Enter a declared zed dependency, treating its physical mount root as a boundary. */
    public OresProjectConfig forImportedPackage(Path importedSource, String importPath) throws IOException {
        String remainder = importPath.substring("pkg:".length());
        String alias = remainder.substring(0, remainder.indexOf('/'));
        Path packageRoot = dependencyRoots.get(alias);
        if (packageRoot == null) {
            throw new IllegalArgumentException("undeclared package alias '" + alias + "'");
        }
        return discover(importedSource, environment, packageRoot);
    }

    /**
     * Ordered import search roots. Project-local paths intentionally win over
     * ambient ORESLANG_PATH entries for deterministic/reproducible builds.
     */
    public List<Path> searchRoots() {
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();
        ordered.addAll(sourceRoots);
        ordered.addAll(importPaths);
        ordered.addAll(environmentPaths);
        return List.copyOf(ordered);
    }

    /**
     * Resolve one Oreslang filesystem import.
     *
     * <ul>
     *   <li>java: imports are not filesystem imports.</li>
     *   <li>./ and ../ imports resolve only relative to the importing file.</li>
     *   <li>absolute imports resolve directly.</li>
     *   <li>bare imports search source.roots, source.import_paths, then ORESLANG_PATH.</li>
     * </ul>
     */
    public Optional<Path> resolveImport(Path importer, String importPath) {
        if (importer == null) throw new IllegalArgumentException("importer path cannot be null");
        if (importPath == null || importPath.isBlank()) {
            throw new IllegalArgumentException("import path cannot be blank");
        }
        if (ImportRules.isJavaPath(importPath)) return Optional.empty();

        String unix = importPath.replace('\\', '/');
        if (unix.startsWith("@/")) {
            if (manifestPath == null || !PACKAGE_MANIFEST_NAME.equals(manifestPath.getFileName().toString())) {
                throw new IllegalArgumentException("@/ imports require a " + PACKAGE_MANIFEST_NAME);
            }
            return rootedSource(projectRoot, unix.substring(2), importPath);
        }
        if (unix.startsWith("pkg:")) {
            String remainder = unix.substring(4);
            int slash = remainder.indexOf('/');
            if (slash <= 0 || slash == remainder.length() - 1) {
                throw new IllegalArgumentException("expected pkg:<alias>/<source> import: " + importPath);
            }
            String alias = remainder.substring(0, slash);
            Path dependency = dependencyRoots.get(alias);
            if (dependency == null) {
                throw new IllegalArgumentException("undeclared package alias '" + alias + "' in " + importPath);
            }
            validateInstalledPackage(dependency);
            return rootedSource(dependency, remainder.substring(slash + 1), importPath);
        }
        if (unix.startsWith("@") || unix.startsWith("pkg:")) {
            throw new IllegalArgumentException("invalid import prefix: " + importPath);
        }
        Path raw = Path.of(unix);
        if (raw.isAbsolute()) return existingSource(raw);

        if (unix.startsWith(".")) {
            Path parent = importer.toAbsolutePath().normalize().getParent();
            if (parent == null) parent = Path.of("").toAbsolutePath().normalize();
            return existingSource(parent.resolve(raw).normalize());
        }

        for (Path root : searchRoots()) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path candidate = normalizedRoot.resolve(raw).normalize();
            if (!candidate.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException(
                        "bare import '" + importPath + "' escapes configured search root '" + normalizedRoot + "'");
            }
            Optional<Path> resolved = existingSource(candidate);
            if (resolved.isPresent()) return resolved;
        }
        return Optional.empty();
    }

    /**
     * The lockfile remains zed's authority. When package metadata exists,
     * reject an install tree whose identity disagrees with the declared
     * org/name instead of compiling an unrelated source checkout.
     */
    private static void validateInstalledPackage(Path expectedDirectory) {
        Path zpkg = expectedDirectory.resolve(".zpkg.toml");
        if (!Files.isRegularFile(zpkg)) return;
        try {
            TomlParseResult metadata = Toml.parse(zpkg);
            if (!metadata.errors().isEmpty()) {
                throw new IllegalArgumentException("invalid installed .zpkg.toml: "
                        + metadata.errors().getFirst());
            }
            String org = metadata.getString("package.org");
            String name = metadata.getString("package.name");
            String expected = expectedDirectory.getParent().getFileName()
                    + "/" + expectedDirectory.getFileName();
            if (org == null || name == null || !expected.equals(org + "/" + name)) {
                throw new IllegalArgumentException("installed zed package identity mismatch: expected "
                        + expected + ", got " + org + "/" + name);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read installed zed package: " + zpkg, failure);
        }
    }

    private static Optional<Path> rootedSource(Path root, String relative, String importPath) {
        if (relative.isBlank() || relative.startsWith("/")) {
            throw new IllegalArgumentException("invalid rooted import: " + importPath);
        }
        Path component = Path.of(relative);
        if (component.isAbsolute()) {
            throw new IllegalArgumentException("absolute component in rooted import: " + importPath);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(component).normalize();
        if (!candidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("rooted import escapes its root: " + importPath);
        }
        // Symlinked source files and package mounts are intentional. Their
        // canonical physical identity is returned by existingSource().
        return existingSource(candidate);
    }

    private static Optional<Path> existingSource(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) return Optional.of(realPath(normalized));

        String text = normalized.toString().toLowerCase();
        if (text.endsWith(".ores") || text.endsWith(".java")) return Optional.empty();

        Path ores = Path.of(normalized.toString() + ".ores");
        if (Files.isRegularFile(ores)) return Optional.of(realPath(ores));

        Path java = Path.of(normalized.toString() + ".java");
        if (Files.isRegularFile(java)) return Optional.of(realPath(java));

        return Optional.empty();
    }

    private static Path realPath(Path source) {
        try {
            return source.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot resolve source path: " + source, failure);
        }
    }

    private static Path directoryFor(Path start) {
        Path absolute = start.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) return absolute;
        Path parent = absolute.getParent();
        return parent == null ? Path.of("").toAbsolutePath().normalize() : parent;
    }

    private static List<Path> parseEnvironmentPaths(Map<String, String> environment) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        // ORES_PATHS is explicitly colon-delimited, independent of the legacy
        // host-native ORESLANG_PATH. Project-configured paths remain first.
        addEnvironmentPaths(paths, environment.get(ENV_ORES_PATHS), ":");
        addEnvironmentPaths(paths, environment.get(ENV_ORESLANG_PATH), File.pathSeparator);
        return List.copyOf(paths);
    }

    private static void addEnvironmentPaths(LinkedHashSet<Path> paths, String raw, String delimiter) {
        if (raw == null || raw.isBlank()) return;
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (String part : raw.split(java.util.regex.Pattern.quote(delimiter), -1)) {
            String trimmed = part.trim();
            // Empty segments are ignored, never implicit cwd.
            if (trimmed.isEmpty()) continue;
            Path path = Path.of(trimmed);
            if (!path.isAbsolute()) path = cwd.resolve(path);
            paths.add(path.toAbsolutePath().normalize());
        }
    }

    private static Map<String, Path> parseDependencies(TomlParseResult toml, Path root) throws IOException {
        TomlTable dependencies = toml.getTable("dependencies");
        if (dependencies == null) {
            if (toml.get("dependencies") != null) {
                throw new IllegalArgumentException(PACKAGE_MANIFEST_NAME + " dependencies must be a TOML table");
            }
            return Map.of();
        }
        Path installationRoot = zedInstallRoot(root);
        java.util.LinkedHashMap<String, Path> result = new java.util.LinkedHashMap<>();
        for (String alias : dependencies.keySet()) {
            if (!alias.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("invalid dependency alias: " + alias);
            }
            Object declaration = dependencies.get(alias);
            String identifier;
            if (declaration instanceof String text) {
                identifier = text;
            } else if (declaration instanceof TomlTable table) {
                identifier = table.getString("zed");
                if (table.keySet().size() != 1 || identifier == null) {
                    throw new IllegalArgumentException("dependency '" + alias
                            + "' expects only zed = \"org/name\"");
                }
            } else {
                throw new IllegalArgumentException("invalid dependency declaration: " + alias);
            }
            if (identifier == null || !identifier.matches("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*")
                    || identifier.contains("..")) {
                throw new IllegalArgumentException("invalid zed-pkg identifier: " + identifier);
            }
            result.put(alias, installationRoot.resolve(identifier).toAbsolutePath().normalize());
        }
        return Map.copyOf(result);
    }

    private static Path zedInstallRoot(Path root) throws IOException {
        Path zedManifest = root.resolve(".zpkg.toml");
        if (!Files.isRegularFile(zedManifest)) return root.resolve("zed_modules");
        TomlParseResult zed = Toml.parse(zedManifest);
        if (!zed.errors().isEmpty()) {
            throw new IllegalArgumentException("invalid .zpkg.toml: " + zed.errors().getFirst());
        }
        String directory = zed.getString("install.dir");
        if (directory == null) {
            if (zed.get("install.dir") != null) {
                throw new IllegalArgumentException(".zpkg.toml install.dir must be a string");
            }
            directory = "zed_modules";
        }
        if (directory.isBlank()) {
            throw new IllegalArgumentException("empty .zpkg.toml install.dir");
        }
        Path relative = Path.of(directory);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException(".zpkg.toml install.dir must be project-relative");
        }
        Path installed = root.resolve(relative).toAbsolutePath().normalize();
        if (!installed.startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(".zpkg.toml install.dir escapes the Oreslang project");
        }
        return installed;
    }

    private static List<Path> resolvePaths(Path base, List<String> values) {
        ArrayList<Path> paths = new ArrayList<>(values.size());
        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("manifest path list entries cannot be blank");
            }
            Path path = resolvePath(base, value);
            if (seen.add(path)) paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static Path resolvePath(Path base, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) path = base.resolve(path);
        return path.toAbsolutePath().normalize();
    }

    private static List<String> stringArray(TomlParseResult toml, String key) {
        TomlArray array = toml.getArray(key);
        if (array == null) {
            if (toml.get(key) != null) {
                throw new IllegalArgumentException("project manifest key '" + key + "' must be an array");
            }
            return List.of();
        }

        ArrayList<String> result = new ArrayList<>((int) array.size());
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException(MANIFEST_NAME + " key '" + key + "' must be an array of strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
