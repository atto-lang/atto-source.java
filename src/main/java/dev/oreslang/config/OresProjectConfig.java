package dev.oreslang.config;

import dev.oreslang.imports.ImportRules;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Project-level Oreslang compiler configuration.
 *
 * <p>The nearest .ores.pkg.toml (or legacy .oreslangc.cfg.toml) found by walking upward from the source
 * file (or current working directory) owns the project. Manifest paths are
 * resolved relative to [project].root. Ambient ORESLANG_PATH entries are
 * process-relative and are searched after project-local roots.</p>
 */
public final class OresProjectConfig {
    public static final String MANIFEST_NAME = ".ores.pkg.toml";
    public static final String LEGACY_MANIFEST_NAME = ".oreslangc.cfg.toml";
    public static final String ENV_PROJECT_ROOT = "ORES_PROJECT_ROOT";
    // Canonical cross-backend search path: colon-separated even on Windows.
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
    private final Path mainEntrypoint;

    private OresProjectConfig(
            Path manifestPath,
            Path projectRoot,
            String projectName,
            String projectVersion,
            List<Path> sourceRoots,
            List<Path> importPaths,
            List<Path> environmentPaths,
            Path mainEntrypoint) {
        this.manifestPath = manifestPath;
        this.projectRoot = projectRoot;
        this.projectName = projectName;
        this.projectVersion = projectVersion;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.importPaths = List.copyOf(importPaths);
        this.environmentPaths = List.copyOf(environmentPaths);
        this.mainEntrypoint = mainEntrypoint;
    }

    public static OresProjectConfig discover(Path start, Map<String, String> environment) throws IOException {
        if (start == null) throw new IllegalArgumentException("config discovery start path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path searchFrom = directoryFor(start);
        String override = environment.get(ENV_PROJECT_ROOT);
        if (override != null && !override.isBlank()) {
            Path explicitRoot = Path.of(override).toAbsolutePath().normalize();
            if (!Files.isDirectory(explicitRoot)) {
                throw new IllegalArgumentException("project root is not a directory: " + explicitRoot);
            }
            Path manifest = manifestInDirectory(explicitRoot);
            if (manifest == null) {
                throw new IllegalArgumentException("project root lacks " + MANIFEST_NAME
                        + " or " + LEGACY_MANIFEST_NAME + ": " + explicitRoot);
            }
            return load(manifest, environment);
        }

        for (Path cursor = searchFrom; cursor != null; cursor = cursor.getParent()) {
            Path manifest = manifestInDirectory(cursor);
            if (manifest != null) return load(manifest, environment);
        }

        Path root = searchFrom.toAbsolutePath().normalize();
        return new OresProjectConfig(
                null,
                root,
                null,
                null,
                List.of(root),
                List.of(),
                parseEnvironmentPaths(environment),
                null);
    }

    private static Path manifestInDirectory(Path directory) {
        // An existing modern marker owns discovery priority even when it is
        // a dangling symlink, directory or unreadable file. Deferring to
        // load() lets it reject the malformed marker instead of silently
        // granting a valid legacy manifest authority over this directory.
        Path modern = directory.resolve(MANIFEST_NAME);
        if (Files.exists(modern, LinkOption.NOFOLLOW_LINKS)) return modern;
        Path legacy = directory.resolve(LEGACY_MANIFEST_NAME);
        return Files.exists(legacy, LinkOption.NOFOLLOW_LINKS) ? legacy : null;
    }

    public static OresProjectConfig load(Path manifest, Map<String, String> environment) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path absoluteManifest = manifest.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteManifest)) {
            throw new IllegalArgumentException("not an Oreslang project manifest: " + absoluteManifest);
        }
        // A manifest symlink can move package-root authority outside the
        // selected directory. Require the exact manifest file, not a target
        // reached through an independently controlled symbolic link.
        if (Files.isSymbolicLink(absoluteManifest)) {
            throw new IllegalArgumentException("project manifest symlink is not allowed: " + absoluteManifest);
        }

        // Parse one UTF-8 snapshot rather than re-opening the manifest for
        // the TOML parser after the admission checks. LF and CRLF are valid
        // TOML line endings, but bare CR is not: it could otherwise conceal
        // a second project.root declaration behind a '#' comment.
        String manifestContents = Files.readString(absoluteManifest);
        for (int i = 0; i < manifestContents.length(); i++) {
            if (manifestContents.charAt(i) == '\r'
                    && (i + 1 == manifestContents.length()
                        || manifestContents.charAt(i + 1) != '\n')) {
                throw new IllegalArgumentException("bare carriage return in project manifest");
            }
        }
        TomlParseResult toml = Toml.parse(manifestContents);
        if (!toml.errors().isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid " + MANIFEST_NAME + ": " + toml.errors().getFirst());
        }

        String schemaVersion = toml.getString("schema_version");
        if (schemaVersion == null || !SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    MANIFEST_NAME + " requires schema_version = \"" + SCHEMA_VERSION + "\"");
        }

        Path manifestDir = absoluteManifest.getParent();
        String rootText = manifestString(toml, "project.root", ".");
        Path projectRoot = absoluteManifest.getFileName().toString().equals(MANIFEST_NAME)
                ? resolveModernProjectRoot(manifestDir, rootText)
                : resolvePath(manifestDir, rootText);

        // Omitted roots defaults to "."; an explicit [] MUST remain empty.
        // Empty roots are often used to deny implicit bare-source imports.
        List<String> sourceRootValues = stringArray(toml, "source.roots");
        if (toml.get("source.roots") == null) sourceRootValues = List.of(".");
        List<Path> sourceRoots = resolvePaths(projectRoot, sourceRootValues);
        List<Path> importPaths = resolvePaths(projectRoot, stringArray(toml, "source.import_paths"));

        String mainText = manifestString(toml, "entrypoints.main", null);
        Path mainEntrypoint = mainText == null ? null : resolvePath(projectRoot, mainText);

        return new OresProjectConfig(
                absoluteManifest,
                projectRoot,
                toml.getString("project.name"),
                toml.getString("project.version"),
                sourceRoots,
                importPaths,
                parseEnvironmentPaths(environment),
                mainEntrypoint);
    }

    public Optional<Path> manifestPath() { return Optional.ofNullable(manifestPath); }
    public Path projectRoot() { return projectRoot; }
    public Optional<String> projectName() { return Optional.ofNullable(projectName); }
    public Optional<String> projectVersion() { return Optional.ofNullable(projectVersion); }
    public List<Path> sourceRoots() { return sourceRoots; }
    public List<Path> importPaths() { return importPaths; }
    public List<Path> environmentPaths() { return environmentPaths; }
    public Optional<Path> mainEntrypoint() { return Optional.ofNullable(mainEntrypoint); }

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

        if (importPath.startsWith("+") && importPath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("project-root imports require forward slashes: " + importPath);
        }
        String unix = importPath.replace('\\', '/');
        if (unix.startsWith("+")) {
            if (!unix.startsWith("+/")) {
                throw new IllegalArgumentException("project-local imports must start with '+/': " + importPath);
            }
            if (manifestPath == null) {
                throw new IllegalArgumentException("project-root import requires " + MANIFEST_NAME
                        + " (or " + LEGACY_MANIFEST_NAME + "): " + importPath);
            }
            String local = unix.substring(2);
            if (local.isEmpty() || java.util.Arrays.stream(local.split("/", -1))
                    .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
                throw new IllegalArgumentException("invalid project-root import path: " + importPath);
            }
            Path root = projectRoot.toAbsolutePath().normalize();
            Path candidate = root.resolve(local).normalize();
            if (!candidate.startsWith(root)) {
                throw new IllegalArgumentException("project-root import escapes root: " + importPath);
            }
            Optional<Path> source = existingSource(candidate);
            if (source.isEmpty()) return Optional.empty();
            try {
                Path realRoot = root.toRealPath();
                Path realTarget = source.get().toRealPath();
                if (!realTarget.startsWith(realRoot)) {
                    throw new IllegalArgumentException(
                            "project-root import escapes root through symlink: " + importPath);
                }
                // A nested package owns its own +/ authority; an importing
                // parent may not treat it as an ordinary directory. Check
                // both spelled and physical paths to block in-root symlink
                // aliases that conceal a nested manifest.
                requireNoNestedPackage(root, source.get(), importPath);
                requireNoNestedPackage(realRoot, realTarget, importPath);
                // Preserve the validated canonical identity throughout import closure loading.
                // In-root symlink aliases must not create duplicate source identities.
                return Optional.of(realTarget);
            } catch (IOException error) {
                throw new IllegalArgumentException(
                        "cannot verify project-root import: " + importPath, error);
            }
        }
        // Installed dependency aliases require an authenticated package
        // identity and a per-dependency resolver context. The broader
        // experimental importer implements that separately; this +/ branch
        // must fail closed rather than treat pkg: as a bare filesystem name.
        if (unix.startsWith("pkg:")) {
            throw new IllegalArgumentException(
                    "pkg: imports require verified installed dependency resolution: " + importPath);
        }
        if (unix.startsWith("@/")) {
            throw new IllegalArgumentException("retired @/ root import prefix is unsupported: " + importPath);
        }
        Path raw = Path.of(unix);
        if (raw.isAbsolute()) return existingSource(raw);

        // Only explicit ./ and ../ (or the degenerate . / .. forms)
        // are importer-relative. Dot-prefixed bare names such as
        // .hidden/helper and ..backup/helper use ordinary source roots.
        if (unix.equals(".") || unix.equals("..") ||
                unix.startsWith("./") || unix.startsWith("../")) {
            Path parent = importer.toAbsolutePath().normalize().getParent();
            if (parent == null) parent = Path.of("").toAbsolutePath().normalize();
            return projectOwnedSource(existingSource(parent.resolve(raw).normalize()), importPath);
        }

        for (Path root : searchRoots()) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path candidate = normalizedRoot.resolve(raw).normalize();
            if (!candidate.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException(
                        "bare import '" + importPath + "' escapes configured search root '" + normalizedRoot + "'");
            }
            Optional<Path> resolved = projectOwnedSource(existingSource(candidate), importPath);
            if (resolved.isPresent()) return resolved;
        }
        return Optional.empty();
    }

    /**
     * Relative and bare imports must not use a different path to bypass the
     * same nested-package isolation enforced for +/. External search roots
     * remain independently owned; only sources spelled through this project
     * root are checked against its namespace.
     */
    private Optional<Path> projectOwnedSource(Optional<Path> found, String specifier) {
        if (found.isEmpty()) return found;
        Path spelled = found.get().toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!spelled.startsWith(root)) return found;
        try {
            Path physicalRoot = root.toRealPath();
            Path physical = spelled.toRealPath();
            if (!physical.startsWith(physicalRoot)) {
                throw new IllegalArgumentException(
                        "source import escapes project through symlink: " + specifier);
            }
            requireNoNestedPackage(root, spelled, specifier);
            requireNoNestedPackage(physicalRoot, physical, specifier);
            return Optional.of(physical);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "cannot verify source import package identity: " + specifier, error);
        }
    }

    private static void requireNoNestedPackage(Path root, Path source, String specifier) {
        requireNoNestedPackageDirectory(root, source.getParent(), specifier);
    }

    private static void requireNoNestedPackageDirectory(Path root, Path directory, String specifier) {
        for (Path dir = directory; dir != null && !dir.equals(root); dir = dir.getParent()) {
            if (!dir.startsWith(root)) {
                throw new IllegalArgumentException("project-root import escapes package: " + specifier);
            }
            if (Files.exists(dir.resolve(MANIFEST_NAME), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(dir.resolve(LEGACY_MANIFEST_NAME), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "project-root import crosses nested package boundary: " + specifier);
            }
        }
    }

    private static Optional<Path> existingSource(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) return Optional.of(normalized);

        String text = normalized.toString().toLowerCase();
        if (text.endsWith(".ores") || text.endsWith(".java")) return Optional.empty();

        Path ores = Path.of(normalized.toString() + ".ores");
        if (Files.isRegularFile(ores)) return Optional.of(ores.toAbsolutePath().normalize());

        Path java = Path.of(normalized.toString() + ".java");
        if (Files.isRegularFile(java)) return Optional.of(java.toAbsolutePath().normalize());

        return Optional.empty();
    }

    private static Path directoryFor(Path start) {
        Path absolute = start.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) return absolute;
        Path parent = absolute.getParent();
        return parent == null ? Path.of("").toAbsolutePath().normalize() : parent;
    }

    private static List<Path> parseEnvironmentPaths(Map<String, String> environment) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        // Project source.roots and import_paths always precede both ambient
        // search lists; duplicate directories keep the first occurrence.
        addEnvironmentPaths(paths, environment.get(ENV_ORES_PATHS), ":");
        addEnvironmentPaths(paths, environment.get(ENV_ORESLANG_PATH), File.pathSeparator);
        return List.copyOf(paths);
    }

    private static void addEnvironmentPaths(
            LinkedHashSet<Path> paths, String raw, String separator) {
        if (raw == null || raw.isEmpty()) return;
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (String entry : raw.split(java.util.regex.Pattern.quote(separator), -1)) {
            // Unlike POSIX PATH, empty entries do not grant implicit CWD.
            // Preserve spaces in nonempty directory names verbatim.
            if (entry.isEmpty()) continue;
            Path path = Path.of(entry);
            if (!path.isAbsolute()) path = cwd.resolve(path);
            paths.add(path.toAbsolutePath().normalize());
        }
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

    private static Path resolveModernProjectRoot(Path manifestDir, String rootText) throws IOException {
        if (rootText.isEmpty() || rootText.length() > 1024
                || rootText.indexOf('\\') >= 0 || rootText.indexOf(':') >= 0
                || rootText.codePoints().anyMatch(c -> c < 32 || c == 127)
                || (!rootText.equals(".") && java.util.Arrays.stream(rootText.split("/", -1))
                        .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))) {
            throw new IllegalArgumentException("invalid modern project.root: " + rootText);
        }
        Path configured = Path.of(rootText);
        if (configured.isAbsolute()) {
            throw new IllegalArgumentException("modern project.root must be relative: " + rootText);
        }
        Path base = manifestDir.toAbsolutePath().normalize();
        Path selected = base.resolve(configured).normalize();
        if (!selected.startsWith(base) || !Files.isDirectory(selected)) {
            throw new IllegalArgumentException(
                    "modern project.root must select an existing directory within the manifest: " + rootText);
        }
        Path physicalBase = base.toRealPath();
        Path physicalSelected = selected.toRealPath();
        if (!physicalSelected.startsWith(physicalBase)) {
            throw new IllegalArgumentException("modern project.root escapes physical manifest: " + rootText);
        }
        // A nested package manifest in either the spelled or physical
        // configured path cannot be inherited from the parent manifest.
        requireNoNestedPackageDirectory(base, selected, rootText);
        requireNoNestedPackageDirectory(physicalBase, physicalSelected, rootText);
        return selected;
    }

    private static String manifestString(TomlParseResult toml, String key, String fallback) {
        Object raw = toml.get(key);
        if (raw == null) return fallback;
        if (raw instanceof String value) return value;
        throw new IllegalArgumentException(MANIFEST_NAME + " key '" + key + "' must be a string");
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
                throw new IllegalArgumentException(
                        MANIFEST_NAME + " key '" + key + "' must be an array of strings");
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
