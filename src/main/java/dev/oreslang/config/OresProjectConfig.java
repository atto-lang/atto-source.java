package dev.oreslang.config;

import dev.oreslang.imports.ImportRules;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlInvalidTypeException;
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
 * <p>Package manifests (.ores.pkg.toml) bind the source project root and
 * take precedence over legacy compiler manifests. Without a package manifest,
 * the nearest .oreslangc.cfg.toml keeps its previous semantics. Host import
 * resolution uses project roots first, then ORES_PATHS and ORESLANG_PATH.</p>
 */
public final class OresProjectConfig {
    public static final String MANIFEST_NAME = ".oreslangc.cfg.toml";
    public static final String PACKAGE_MANIFEST_NAME = ".ores.pkg.toml";
    public static final String ENV_ORES_MANIFEST_PATH = "ORES_MANIFEST_PATH";
    public static final String ENV_ORES_PROJECT_ROOT = "ORES_PROJECT_ROOT";
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
        if (start == null) throw new IllegalArgumentException("config discovery start path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        // Entry-point overrides only. Transitive imports deliberately use the
        // private discover(..., boundary) overload and independently find their
        // own nearest package manifest.
        String manifestText = environment.get(ENV_ORES_MANIFEST_PATH);
        String rootText = environment.get(ENV_ORES_PROJECT_ROOT);
        boolean hasManifest = manifestText != null;
        boolean hasRoot = rootText != null;
        if (hasManifest || hasRoot) {
            if (hasManifest && manifestText.isBlank()) {
                throw new IllegalArgumentException(ENV_ORES_MANIFEST_PATH + " cannot be blank");
            }
            if (hasRoot && rootText.isBlank()) {
                throw new IllegalArgumentException(ENV_ORES_PROJECT_ROOT + " cannot be blank");
            }
            Path root = hasRoot ? Path.of(rootText).toAbsolutePath().normalize() : null;
            Path manifest = hasManifest ? Path.of(manifestText).toAbsolutePath().normalize()
                    : root.resolve(PACKAGE_MANIFEST_NAME);
            if (!PACKAGE_MANIFEST_NAME.equals(manifest.getFileName().toString())) {
                throw new IllegalArgumentException(ENV_ORES_MANIFEST_PATH
                        + " must select " + PACKAGE_MANIFEST_NAME);
            }
            Path manifestRoot = manifest.getParent();
            if (root != null && !root.equals(manifestRoot)
                    && (!Files.isDirectory(root) || !Files.isDirectory(manifestRoot)
                        || !root.toRealPath().equals(manifestRoot.toRealPath()))) {
                throw new IllegalArgumentException(ENV_ORES_PROJECT_ROOT + " and "
                        + ENV_ORES_MANIFEST_PATH + " identify different package directories");
            }
            OresProjectConfig selected = load(manifest, environment);
            Path absoluteStart = start.toAbsolutePath().normalize();
            // A generated entrypoint may not exist yet. Resolve its closest
            // existing ancestor physically, so a symlinked parent cannot
            // move a future source outside the explicit +/ authority.
            // A dangling source/ancestor symlink also fails closed.
            Path existingAncestor = absoluteStart;
            while (existingAncestor != null && !Files.exists(existingAncestor)) {
                if (Files.isSymbolicLink(existingAncestor)) {
                    throw new IllegalArgumentException(
                            "entry source traverses a dangling symlink: " + absoluteStart);
                }
                existingAncestor = existingAncestor.getParent();
            }
            if (existingAncestor == null
                    || !existingAncestor.toRealPath().startsWith(manifestRoot.toRealPath())) {
                throw new IllegalArgumentException("entry source is outside explicitly selected package root: "
                        + absoluteStart);
            }
            return selected;
        }
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
            // +/ must always bind the nearest package manifest, even when a
            // legacy compiler config exists in a lower source directory.
            Path packageManifest = cursor.resolve(PACKAGE_MANIFEST_NAME);
            // Even an invalid package marker must shadow ancestor package
            // roots. Falling back through a dangling symlink or directory
            // silently changes what +/ and pkg: imports are authorized to see.
            if (Files.exists(packageManifest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(packageManifest)) {
                    throw new IllegalArgumentException(
                            "nearest package manifest is not a regular file: " + packageManifest);
                }
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
        // A .ores.pkg.toml is root authority, not just configuration text:
        // importing a foreign manifest through a symlink must not grant a
        // different directory's contents authority over this +/ namespace.
        if (packageManifest && !absoluteManifest.toRealPath().getParent()
                .equals(absoluteManifest.getParent().toRealPath())) {
            throw new IllegalArgumentException(
                    ".ores.pkg.toml must physically reside within its package directory");
        }
        TomlParseResult toml = Toml.parse(absoluteManifest);
        if (!toml.errors().isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid " + manifestName + ": " + toml.errors().getFirst());
        }

        String schemaVersion = optionalString(toml, "schema_version");
        if (schemaVersion == null || !SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    manifestName + " requires schema_version = \"" + SCHEMA_VERSION + "\"");
        }

        Path manifestDir = absoluteManifest.getParent();
        // +/ always means the directory containing .ores.pkg.toml.
        // Legacy compiler manifests keep their explicit project.root behavior.
        String explicitRoot = optionalString(toml, "project.root");
        if (explicitRoot == null && toml.get("project.root") != null) {
            throw new IllegalArgumentException(manifestName + " project.root must be a string");
        }
        String rootText = defaultString(explicitRoot, ".");
        if (packageManifest && !".".equals(rootText)) {
            throw new IllegalArgumentException(PACKAGE_MANIFEST_NAME
                    + " cannot override its physical manifest directory with project.root");
        }
        Path projectRoot = packageManifest ? manifestDir : resolvePath(manifestDir, rootText);

        List<String> sourceRootValues = stringArray(toml, "source.roots");
        // An omitted roots field defaults to the project root, but an
        // explicitly empty array disables implicit bare-source lookups.
        // Treating [] as ["."] silently expands the import search surface.
        if (tomlValue(toml, "source.roots") == null) sourceRootValues = List.of(".");
        List<Path> sourceRoots = resolvePaths(projectRoot, sourceRootValues);
        List<Path> importPaths = resolvePaths(projectRoot, stringArray(toml, "source.import_paths"));

        String mainText = optionalString(toml, "entrypoints.main");
        if (mainText == null && toml.get("entrypoints.main") != null) {
            throw new IllegalArgumentException(manifestName + " entrypoints.main must be a string");
        }
        Path mainEntrypoint = mainText == null ? null : resolvePath(projectRoot, mainText);
        // A package manifest's entrypoint must remain within the package
        // project root. Legacy compiler manifests retain their historical
        // project.root and absolute-entrypoint behavior.
        if (packageManifest && mainText != null
                && (mainText.isBlank() || Path.of(mainText).isAbsolute()
                    || !mainEntrypoint.startsWith(projectRoot))) {
            throw new IllegalArgumentException(PACKAGE_MANIFEST_NAME
                    + " entrypoints.main must be a nonblank project-relative path inside the package root");
        }
        if (packageManifest && mainEntrypoint != null) {
            // Reject symlinks that escape through any existing ancestor,
            // including when the final entrypoint has not yet been generated.
            Path cursor = mainEntrypoint;
            while (cursor != null && !Files.exists(cursor)) {
                if (Files.isSymbolicLink(cursor)) {
                    throw new IllegalArgumentException(PACKAGE_MANIFEST_NAME
                            + " entrypoints.main points through a dangling symbolic link");
                }
                cursor = cursor.getParent();
            }
            if (cursor != null && !cursor.toRealPath().startsWith(projectRoot.toRealPath())) {
                throw new IllegalArgumentException(PACKAGE_MANIFEST_NAME
                        + " entrypoints.main resolves outside the package root");
            }
        }

        return new OresProjectConfig(
                absoluteManifest,
                projectRoot,
                optionalString(toml, packageManifest ? "package.name" : "project.name"),
                optionalString(toml, packageManifest ? "package.version" : "project.version"),
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

    /**
     * Physical source identity alone is insufficient when a symlinked unit
     * could be parsed with different +/ or pkg: meanings. This immutable
     * context identifies the owning resolver namespace for one compilation.
     */
    public record ImportContext(Path projectRoot, Path manifestPath, Path packageBoundary) { }

    public ImportContext importContext() {
        // Linking is keyed by physical source paths. Context identity must
        // likewise be physical, or different symlinks to the SAME package
        // manifest falsely appear to be incompatible root namespaces.
        return new ImportContext(
                contextIdentity(projectRoot),
                manifestPath == null ? null : contextIdentity(manifestPath),
                discoveryBoundary == null ? null : contextIdentity(discoveryBoundary));
    }

    private static Path contextIdentity(Path path) {
        try {
            return path.toRealPath();
        } catch (java.nio.file.NoSuchFileException missing) {
            // Preserve prior semantics for not-yet-materialized legacy roots.
            return path.toAbsolutePath().normalize();
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot resolve import context identity: " + path, failure);
        }
    }

    private OresProjectConfig withDiscoveryBoundary(Path boundary) {
        if (boundary == null) return this;
        // A legacy manifest inside a dependency can name a project root that
        // is lexically nested but physically escapes via a directory symlink.
        // For generated roots, canonicalize the closest existing ancestor.
        if (!physicalProjectedPath(projectRoot).startsWith(contextIdentity(boundary))) {
            throw new IllegalArgumentException("imported project root escapes installed package: " + projectRoot);
        }
        return new OresProjectConfig(manifestPath, projectRoot, projectName, projectVersion,
                sourceRoots, importPaths, environmentPaths, dependencyRoots, mainEntrypoint,
                environment, boundary);
    }

    private static Path physicalProjectedPath(Path source) {
        Path absolute = source.toAbsolutePath().normalize();
        Path ancestor = absolute;
        while (ancestor != null && !Files.exists(ancestor)) {
            if (Files.isSymbolicLink(ancestor)) {
                throw new IllegalArgumentException("imported project root has a dangling symlink: " + source);
            }
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) throw new IllegalArgumentException("cannot locate physical ancestor: " + source);
        try {
            return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot resolve imported project root: " + source, error);
        }
    }

    /** Re-evaluate the nearest manifest without crossing an installed package boundary. */
    public OresProjectConfig forImportedSource(Path importedSource) throws IOException {
        OresProjectConfig found = discover(importedSource, environment, discoveryBoundary);
        if (found.manifestPath != null) return found;
        if (discoveryBoundary == null) {
            if (manifestPath == null
                    || MANIFEST_NAME.equals(manifestPath.getFileName().toString())) {
                // Preserve old transitive bare-import search semantics for
                // manifest-less and legacy projects.
                return this;
            }
            // An ambient ORES_PATHS source with no own manifest must retain
            // the consumer's bare search paths, but must NOT acquire its +/
            // root or pkg: dependency aliases as an ambient capability.
            return new OresProjectConfig(null, found.projectRoot, null, null,
                    sourceRoots, importPaths, environmentPaths, Map.of(),
                    null, environment, null);
        }
        return found;
    }

    /** Enter a declared zed dependency, treating its physical mount root as a boundary. */
    public OresProjectConfig forImportedPackage(Path importedSource, String importPath) throws IOException {
        if (importedSource == null) {
            throw new IllegalArgumentException("imported source path cannot be null");
        }
        String normalizedImport = importPath == null ? null : importPath.replace('\\', '/');
        if (normalizedImport == null || !normalizedImport.startsWith("pkg:")) {
            throw new IllegalArgumentException("expected pkg:<alias>/<source> import: " + importPath);
        }
        String remainder = normalizedImport.substring("pkg:".length());
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash == remainder.length() - 1) {
            throw new IllegalArgumentException("expected pkg:<alias>/<source> import: " + importPath);
        }
        String alias = remainder.substring(0, slash);
        Path packageRoot = dependencyRoots.get(alias);
        if (packageRoot == null) {
            throw new IllegalArgumentException("undeclared package alias '" + alias + "'");
        }
        return discover(importedSource, environment, packageRoot);
    }

    /**
     * Ordered import search roots. Project-local paths intentionally win over
     * ambient ORES_PATHS and ORESLANG_PATH entries for reproducible builds.
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
     *   <li>+/ imports use the nearest package manifest directory.</li>
     *   <li>pkg: aliases use declared zed dependency roots.</li>
     *   <li>bare imports search project roots, ORES_PATHS, then ORESLANG_PATH.</li>
     * </ul>
     */
    public Optional<Path> resolveImport(Path importer, String importPath) {
        if (importer == null) throw new IllegalArgumentException("importer path cannot be null");
        if (importPath == null || importPath.isBlank()) {
            throw new IllegalArgumentException("import path cannot be blank");
        }
        if (ImportRules.isJavaPath(importPath)) return Optional.empty();

        // Explicit package namespaces have one portable spelling. In
        // particular, +/foo\\bar must not become +/foo/bar on Windows
        // while naming a different file on POSIX.
        if ((importPath.startsWith("+") || importPath.startsWith("pkg:"))
                && importPath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "rooted import requires forward-slash separators: " + importPath);
        }

        // Source import syntax is platform-independent: +/ is required even
        // on Windows. Do not silently normalize +\\ into a rooted import.
        if (importPath.startsWith("+") && !importPath.startsWith("+/")) {
            throw new IllegalArgumentException("invalid project-root import '" + importPath
                    + "': expected +/<path> (for example +/src/lib/store)");
        }
        String unix = importPath.replace('\\', '/');
        if (unix.startsWith("+/")) {
            if (manifestPath == null || !PACKAGE_MANIFEST_NAME.equals(manifestPath.getFileName().toString())) {
                throw new IllegalArgumentException("+/ imports require a " + PACKAGE_MANIFEST_NAME);
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
        if (unix.startsWith("+")) {
            throw new IllegalArgumentException("invalid project-root import '" + importPath
                    + "': expected +/<path> (for example +/src/lib/store)");
        }
        if (unix.startsWith("@") || unix.startsWith("pkg:")) {
            throw new IllegalArgumentException("invalid import prefix: " + importPath);
        }
        Path raw = Path.of(unix);
        if (raw.isAbsolute()) return existingSource(raw);

        // Only explicit ./ and ../ forms are relative to the importing
        // source. A bare module starting with a dot (e.g. .hidden/util)
        // still searches configured roots; treating any leading dot as a
        // relative prefix changes namespace resolution silently.
        if (unix.equals(".") || unix.equals("..")
                || unix.startsWith("./") || unix.startsWith("../")) {
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
            String org = optionalString(metadata, "package.org");
            String name = optionalString(metadata, "package.name");
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
        // Rooted paths must be canonical: neither traversal nor ambiguous
        // empty/dot components may be silently normalized away. LLVM applies
        // the same rule to the experimental +/ implementation.
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "rooted import contains empty/dot/traversal component: " + importPath);
            }
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
        // Installed dependency mounts may themselves be symlinks, but a +/
        // or pkg: source may not traverse a symlink OUT of its selected
        // physical package root. Canonicalize both sides, not just the file.
        Optional<Path> resolved = existingSource(candidate);
        if (resolved.isPresent()) {
            Path physicalRoot = realPath(normalizedRoot);
            if (!resolved.get().startsWith(physicalRoot)) {
                throw new IllegalArgumentException(
                        "rooted import escapes physical package root: " + importPath);
            }
            // A parent package must not import into a child package through
            // +/ or pkg: and thereby bypass that child's own resolver context.
            // Check the canonical path, including symlink aliases that land
            // in a nested package.
            for (Path parent = resolved.get().getParent();
                    parent != null && !parent.equals(physicalRoot);
                    parent = parent.getParent()) {
                if (Files.exists(parent.resolve(PACKAGE_MANIFEST_NAME),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException(
                            "rooted import crosses nested package boundary: " + importPath);
                }
            }
        }
        return resolved;
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
        if (raw == null || raw.isEmpty()) return;
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (String part : raw.split(java.util.regex.Pattern.quote(delimiter), -1)) {
            // Preserve spaces: PATH-like segments are literal filenames, not trimmed tokens.
            // Only a truly empty segment is ignored; it never grants implicit cwd.
            if (part.isEmpty()) continue;
            Path path = Path.of(part);
            if (!path.isAbsolute()) path = cwd.resolve(path);
            paths.add(path.toAbsolutePath().normalize());
        }
    }

    private static Map<String, Path> parseDependencies(TomlParseResult toml, Path root) throws IOException {
        Object dependencyValue = tomlValue(toml, "dependencies");
        TomlTable dependencies = dependencyValue instanceof TomlTable t ? t : null;
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
                identifier = optionalString(table, "zed");
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
        String directory = optionalString(zed, "install.dir");
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
        Object arrayValue = tomlValue(toml, key);
        TomlArray array = arrayValue instanceof TomlArray values ? values : null;
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

    /**
     * tomlj typed getters throw TomlInvalidTypeException (a RuntimeException,
     * not an IllegalArgumentException) on wrong types. The compiler must
     * report malformed manifests consistently as argument errors instead.
     */
    private static Object tomlValue(TomlTable table, String key) {
        try {
            return table.get(key);
        } catch (TomlInvalidTypeException invalid) {
            throw new IllegalArgumentException("invalid TOML key '" + key
                    + "': expected a table for each parent component", invalid);
        }
    }

    private static String optionalString(TomlTable table, String key) {
        Object value = tomlValue(table, key);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw new IllegalArgumentException("manifest key '" + key + "' must be a string");
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
