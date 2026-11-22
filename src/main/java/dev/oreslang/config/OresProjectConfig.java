package dev.oreslang.config;

import dev.oreslang.imports.ImportRules;
import dev.oreslang.imports.ImportPathInterpolation;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

/**
 * Project-level Oreslang compiler configuration.
 *
 * <p>The nearest .atto.pkg.toml or legacy .oreslangc.cfg.toml found by walking
 * upward from the source file (or current working directory) owns the project.
 * Two manifests in the same directory are ambiguous. Manifest paths are
 * resolved relative to [project].root. Ambient ORESLANG_PATH entries are
 * process-relative and are searched after project-local roots.</p>
 */
public final class OresProjectConfig {
    public static final String MANIFEST_NAME = ".oreslangc.cfg.toml";
    public static final String ATTO_MANIFEST_NAME = ".atto.pkg.toml";
    public static final String ENV_ORESLANG_PATH = "ORESLANG_PATH";
    public static final String ENV_ORES_PATHS = "ORES_PATHS";
    public static final String ENV_ATTO_PATHS = "ATTO_PATHS";
    public static final String SCHEMA_VERSION = "1";

    /**
     * Oreslang exposes exactly two public library layers.
     * CORE is freestanding/no-OS; STD is the hosted/platform layer.
     * Java/Graal is a hosted backend, so it executes STD builds only.
     */
    public enum LibraryProfile {
        STD("std"),
        CORE("core");

        private final String manifestValue;

        LibraryProfile(String manifestValue) {
            this.manifestValue = manifestValue;
        }

        public String manifestValue() {
            return manifestValue;
        }

        static LibraryProfile parse(Object value) {
            if (value == null) return STD;
            if (!(value instanceof String name)) {
                throw new IllegalArgumentException("build.library_profile must be a string");
            }
            for (LibraryProfile profile : values()) {
                if (profile.manifestValue.equals(name)) return profile;
            }
            throw new IllegalArgumentException(
                    "unsupported build.library_profile: " + name + " (expected core or std)");
        }
    }

    private final Path manifestPath;
    private final Path projectRoot;
    private final String projectName;
    private final String projectVersion;
    private final List<Path> sourceRoots;
    private final List<Path> importPaths;
    private final List<Path> environmentPaths;
    private final Map<String, String> environment;
    private final Path mainEntrypoint;
    private final LibraryProfile libraryProfile;

    private OresProjectConfig(
            Path manifestPath,
            Path projectRoot,
            String projectName,
            String projectVersion,
            List<Path> sourceRoots,
            List<Path> importPaths,
            List<Path> environmentPaths,
            Map<String, String> environment,
            Path mainEntrypoint,
            LibraryProfile libraryProfile) {
        this.manifestPath = manifestPath;
        this.projectRoot = projectRoot;
        this.projectName = projectName;
        this.projectVersion = projectVersion;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.importPaths = List.copyOf(importPaths);
        this.environmentPaths = List.copyOf(environmentPaths);
        this.environment = Map.copyOf(environment);
        this.mainEntrypoint = mainEntrypoint;
        this.libraryProfile = java.util.Objects.requireNonNull(libraryProfile, "libraryProfile");
    }

    public static OresProjectConfig discover(Path start, Map<String, String> environment) throws IOException {
        if (start == null) throw new IllegalArgumentException("config discovery start path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path searchFrom = directoryFor(start);
        for (Path cursor = searchFrom; cursor != null; cursor = cursor.getParent()) {
            Path atto = cursor.resolve(ATTO_MANIFEST_NAME);
            Path legacy = cursor.resolve(MANIFEST_NAME);
            // Denied metadata must not be treated as a missing project.
            boolean hasAtto = existsNoFollow(atto);
            boolean hasLegacy = existsNoFollow(legacy);
            if (hasAtto && hasLegacy) {
                throw new IllegalArgumentException(
                        "ambiguous AttoLang project manifests in " + cursor + ": "
                                + ATTO_MANIFEST_NAME + " and " + MANIFEST_NAME);
            }
            if (hasAtto) return load(atto, environment);
            if (hasLegacy) return load(legacy, environment);
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
                environment,
                null,
                LibraryProfile.STD);
    }

    public static OresProjectConfig load(Path manifest, Map<String, String> environment) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path absoluteManifest = manifest.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absoluteManifest)) {
            throw new IllegalArgumentException(
                    "symlinked AttoLang project manifest is forbidden: " + absoluteManifest);
        }
        if (!Files.isRegularFile(absoluteManifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("not an AttoLang project manifest: " + absoluteManifest);
        }

        String manifestName = absoluteManifest.getFileName().toString();
        TomlParseResult toml = Toml.parse(absoluteManifest);
        if (!toml.errors().isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid " + manifestName + ": " + toml.errors().getFirst());
        }

        String schemaVersion = toml.getString("schema_version");
        // Zed's native AttoLang package marker is [package] name="...".
        // Accept that form only for the modern manifest name; the compiler's
        // explicit schema_version = "1" form remains valid. Never permit an
        // absent schema without a validated package identity.
        if (schemaVersion == null
                && ATTO_MANIFEST_NAME.equals(absoluteManifest.getFileName().toString())) {
            String packageName = toml.getString("package.name");
            if (packageName == null || !packageName.matches("[a-z0-9][a-z0-9-]*")) {
                throw new IllegalArgumentException(
                        ATTO_MANIFEST_NAME + " requires schema_version = \"1\" or validated [package].name");
            }
        } else if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    manifestName + " requires schema_version = \"" + SCHEMA_VERSION + "\"");
        }

        Object rawBuild = toml.get("build");
        if (rawBuild != null && !(rawBuild instanceof TomlTable)) {
            throw new IllegalArgumentException("[build] must be a table");
        }
        TomlTable buildTable = (TomlTable) rawBuild;
        if (buildTable != null) {
            for (String key : buildTable.keySet()) {
                if (!"library_profile".equals(key)) {
                    throw new IllegalArgumentException(
                            "unknown build configuration key: " + key);
                }
            }
        }
        LibraryProfile libraryProfile = LibraryProfile.parse(
                buildTable == null ? null : buildTable.get("library_profile"));

        Path manifestDir = absoluteManifest.getParent();
        String rootText = defaultString(toml.getString("project.root"), ".");
        Path projectRoot = resolvePath(manifestDir, rootText);

        List<String> sourceRootValues = stringArray(toml, "source.roots", manifestName);
        if (sourceRootValues.isEmpty()) sourceRootValues = List.of(".");
        List<Path> sourceRoots = resolvePaths(projectRoot, sourceRootValues);
        List<Path> importPaths = resolvePaths(projectRoot, stringArray(toml, "source.import_paths", manifestName));

        String mainText = toml.getString("entrypoints.main");
        Path mainEntrypoint = mainText == null ? null : resolvePath(projectRoot, mainText);

        return new OresProjectConfig(
                absoluteManifest,
                projectRoot,
                toml.getString("project.name"),
                toml.getString("project.version"),
                sourceRoots,
                importPaths,
                parseEnvironmentPaths(environment),
                environment,
                mainEntrypoint,
                libraryProfile);
    }

    public Optional<Path> manifestPath() { return Optional.ofNullable(manifestPath); }
    public Path projectRoot() { return projectRoot; }
    public Optional<String> projectName() { return Optional.ofNullable(projectName); }
    public Optional<String> projectVersion() { return Optional.ofNullable(projectVersion); }
    public List<Path> sourceRoots() { return sourceRoots; }
    public List<Path> importPaths() { return importPaths; }
    public List<Path> environmentPaths() { return environmentPaths; }
    public Optional<Path> mainEntrypoint() { return Optional.ofNullable(mainEntrypoint); }
    public LibraryProfile libraryProfile() { return libraryProfile; }

    /**
     * Java/Graal is hosted. A core build must fail before import traversal,
     * parsing, compiler emission or guest execution rather than being
     * mislabeled as a freestanding artifact.
     */
    public void requireStdJavaBackend() {
        if (libraryProfile != LibraryProfile.STD) {
            throw new IllegalArgumentException(
                    "build.library_profile = \"" + libraryProfile.manifestValue()
                            + "\" requests ores.core freestanding execution, which is not yet "
                            + "supported by the hosted Java/Graal backend; no freestanding code was compiled");
        }
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

        // Expand only explicit ${NAME} placeholders from the caller-supplied
        // compilation snapshot. $repo is NOT a magic root alias; +/ retains
        // its existing independent project-root namespace.
        String expanded = ImportPathInterpolation.expand(importPath, environment);
        if (expanded.startsWith("https://") || expanded.startsWith("http://")) {
            throw new IllegalArgumentException("remote source imports require locked Zed URL admission (not implemented)");
        }
        // Do not let environment interpolation turn a static filesystem
        // import into an implicit java:, file:, ftp:, or other URI import.
        // Windows drive-qualified absolute paths are the only colon prefix
        // admitted here; they still go through physical source authorization.
        if (expanded.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            boolean windowsDrive = expanded.length() >= 3
                    && Character.isLetter(expanded.charAt(0))
                    && expanded.charAt(1) == ':'
                    && (expanded.charAt(2) == '/' || expanded.charAt(2) == '\\');
            if (!windowsDrive) {
                throw new IllegalArgumentException(
                        "unsupported import URI scheme; use a verified filesystem or locked Zed source");
            }
        }
        if (expanded.startsWith("+")) {
            if (!expanded.startsWith("+/")) {
                throw new IllegalArgumentException("project-root source imports require canonical +/ prefix");
            }
            return Optional.of(resolveProjectRootSource(importer, expanded));
        }
        String unix = expanded.replace('\\', '/');
        Path raw = Path.of(unix);
        if (raw.isAbsolute()) {
            Optional<Path> selected = existingSource(raw);
            if (importPath.contains("${")) {
                // Env-derived absolute paths are NOT unrestricted reads.
                // The source must be owned by the importing project, or by an
                // explicit manifest import path. Never let ambient paths grant
                // package authority to an environment-variable substitution.
                if (selected.isEmpty()) {
                    throw new IllegalArgumentException("expanded absolute import source not found");
                }
                requireAuthorizedEnvironmentSource(selected.get());
            }
            return selected;
        }

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
        if (importPath.contains("${")) {
            throw new IllegalArgumentException("expanded import source not found");
        }
        return Optional.empty();
    }

    private Path resolveProjectRootSource(Path importer, String expanded) {
        if (manifestPath == null || !ATTO_MANIFEST_NAME.equals(manifestPath.getFileName().toString())) {
            throw new IllegalArgumentException("canonical +/ source imports require nearest .atto.pkg.toml");
        }
        String suffix = expanded.substring(2);
        if (suffix.isEmpty() || expanded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
            throw new IllegalArgumentException("invalid canonical project-root import length");
        }
        for (String component : suffix.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || !component.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("invalid canonical project-root import component");
            }
        }
        Path root = checkedPhysicalRoot(projectRoot);
        Path manifestDirectory = checkedPhysicalRoot(manifestPath.getParent());
        if (!root.startsWith(manifestDirectory)) {
            throw new IllegalArgumentException("configured project.root escapes physical manifest directory");
        }
        Path spelled = projectRoot.resolve(suffix);
        if (!spelled.normalize().startsWith(projectRoot)) {
            throw new IllegalArgumentException("project-root import escapes selected root");
        }
        Optional<Path> found = existingSource(spelled);
        if (found.isEmpty()) throw new IllegalArgumentException("project-root import source not found");
        Path target = checkedPhysicalSource(found.get());
        requireWithinPackage(root, target);
        requireNoNestedManifest(projectRoot.toAbsolutePath().normalize(), spelled); // logical vs logical
        requireNoNestedManifest(root, target);
        return target;
    }

    private void requireAuthorizedEnvironmentSource(Path candidate) {
        if (manifestPath == null) {
            throw new IllegalArgumentException(
                    "environment-derived absolute imports require an explicit project manifest");
        }
        Path target = checkedPhysicalSource(candidate);
        // This is the package root or the manifest's explicitly configured
        // import paths ONLY. ATTO_PATHS/ORES_PATHS are search hints, not an
        // authorization to read arbitrary env-selected absolute files.
        LinkedHashSet<Path> authorized = new LinkedHashSet<>();
        authorized.add(projectRoot);
        authorized.addAll(importPaths);
        for (Path root : authorized) {
            if (!Files.isDirectory(root)) continue;
            Path physicalRoot = checkedPhysicalRoot(root);
            if (target.startsWith(physicalRoot) && !target.equals(physicalRoot)) {
                // Compare like with like: the caller's logical spelling against the logical
                // root, and the resolved target against the resolved root (OS aliases such
                // as macOS /var -> /private/var differ between the two).
                requireNoNestedManifest(root.toAbsolutePath().normalize(), candidate);
                requireNoNestedManifest(physicalRoot, target);
                return;
            }
        }
        throw new IllegalArgumentException(
                "environment-derived absolute import is outside authorized project source roots");
    }

    private static Path checkedPhysicalRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot resolve verified import root", error);
        }
    }

    private static Path checkedPhysicalSource(Path source) {
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("import source must be a regular non-symlink file");
            }
            return source.toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot resolve verified import source", error);
        }
    }

    private static void requireWithinPackage(Path root, Path target) {
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("import escapes physical package root");
        }
    }

    private static void requireNoNestedManifest(Path root, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        requireWithinPackage(root, normalized);
        for (Path cursor = normalized.getParent(); !cursor.equals(root); cursor = cursor.getParent()) {
            if (cursor == null || cursor.equals(cursor.getRoot())) {
                throw new IllegalArgumentException("import escapes package boundary");
            }
            for (String name : List.of(ATTO_MANIFEST_NAME, MANIFEST_NAME)) {
                if (Files.exists(cursor.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("import crosses nested package boundary");
                }
            }
        }
    }

    private static Optional<Path> existingSource(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        String filename = normalized.getFileName().toString();
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        // Explicit foreign-source paths must never pass through this resolver.
        // Preserve existing .ores and Java interop while admitting canonical .atto.
        if (lowerFilename.endsWith(".atto")) {
            // Any canonical directory entry (regular file, directory or link)
            // wins suffix selection. The bounded no-follow reader then rejects
            // a non-regular or linked .atto; do not fall back to a legacy peer.
            return existsNoFollow(normalized) ? Optional.of(normalized) : Optional.empty();
        }
        if (lowerFilename.endsWith(".ores") || lowerFilename.endsWith(".java")) {
            return Files.isRegularFile(normalized) ? Optional.of(normalized) : Optional.empty();
        }
        if (filename.contains(".")) return Optional.empty();

        // Canonical extension wins over legacy sources when both exist.
        // An explicitly suffixed import can still select the legacy file.
        for (String extension : List.of(".atto", ".ores", ".java")) {
            Path source = Path.of(normalized.toString() + extension);
            // Inspect the canonical .atto path without following links. Even a
            // dangling symlink or directory occupies the canonical name and
            // must not silently fall through to a shadowing .ores file.
            if (extension.equals(".atto") ? existsNoFollow(source) : Files.isRegularFile(source))
                return Optional.of(source);
        }
        return Optional.empty();
    }

    private static boolean existsNoFollow(Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        } catch (IOException failure) {
            // An unreadable canonical entry is not equivalent to absence.
            throw new UncheckedIOException("cannot inspect canonical .atto source entry", failure);
        }
    }

    private static Path directoryFor(Path start) {
        Path absolute = start.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) return absolute;
        Path parent = absolute.getParent();
        return parent == null ? Path.of("").toAbsolutePath().normalize() : parent;
    }

    private static List<Path> parseEnvironmentPaths(Map<String, String> environment) {
        // Canonical ATTO_PATHS wins when present, even when explicitly empty.
        // ORES_PATHS and ORESLANG_PATH remain strictly ordered legacy fallbacks.
        String raw = environment.containsKey(ENV_ATTO_PATHS)
                ? environment.get(ENV_ATTO_PATHS)
                : environment.containsKey(ENV_ORES_PATHS)
                ? environment.get(ENV_ORES_PATHS)
                : environment.get(ENV_ORESLANG_PATH);
        if (raw == null || raw.isBlank()) return List.of();

        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        String[] parts = raw.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (String part : parts) {
            String trimmed = part.trim();
            // Unlike PATH, an empty entry never means the current directory.
            // That avoids accidental dependency injection via leading/trailing
            // separators or "::".
            if (trimmed.isEmpty()) continue;
            Path path = Path.of(trimmed);
            if (!path.isAbsolute()) path = cwd.resolve(path);
            paths.add(path.toAbsolutePath().normalize());
        }
        return List.copyOf(paths);
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

    private static List<String> stringArray(TomlParseResult toml, String key, String manifestName) {
        TomlArray array = toml.getArray(key);
        if (array == null) return List.of();

        ArrayList<String> result = new ArrayList<>((int) array.size());
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException(manifestName + " key '" + key + "' must be an array of strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
