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
import java.util.Locale;
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
    public static final String ENV_ORESLANG_PATH = "ORESLANG_PATH";
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
            Path mainEntrypoint,
            LibraryProfile libraryProfile) {
        this.manifestPath = manifestPath;
        this.projectRoot = projectRoot;
        this.projectName = projectName;
        this.projectVersion = projectVersion;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.importPaths = List.copyOf(importPaths);
        this.environmentPaths = List.copyOf(environmentPaths);
        this.mainEntrypoint = mainEntrypoint;
        this.libraryProfile = java.util.Objects.requireNonNull(libraryProfile, "libraryProfile");
    }

    public static OresProjectConfig discover(Path start, Map<String, String> environment) throws IOException {
        if (start == null) throw new IllegalArgumentException("config discovery start path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path searchFrom = directoryFor(start);
        for (Path cursor = searchFrom; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(MANIFEST_NAME);
            if (Files.isRegularFile(candidate)) return load(candidate, environment);
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
                null,
                LibraryProfile.STD);
    }

    public static OresProjectConfig load(Path manifest, Map<String, String> environment) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path absoluteManifest = manifest.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteManifest)) {
            throw new IllegalArgumentException("not an Oreslang project manifest: " + absoluteManifest);
        }

        TomlParseResult toml = Toml.parse(absoluteManifest);
        if (!toml.errors().isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid " + MANIFEST_NAME + ": " + toml.errors().getFirst());
        }

        String schemaVersion = toml.getString("schema_version");
        if (schemaVersion == null || !SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    MANIFEST_NAME + " requires schema_version = \"" + SCHEMA_VERSION + "\"");
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

        List<String> sourceRootValues = stringArray(toml, "source.roots");
        if (sourceRootValues.isEmpty()) sourceRootValues = List.of(".");
        List<Path> sourceRoots = resolvePaths(projectRoot, sourceRootValues);
        List<Path> importPaths = resolvePaths(projectRoot, stringArray(toml, "source.import_paths"));

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

        // Reject noncanonical *importer* identities as well as imported
        // targets. Otherwise consumer.ATTO inherits .atto import semantics
        // on one filesystem while remaining distinct on another.
        final Path importerFileName = importer.getFileName();
        final String importerName = importerFileName == null ? "" : importerFileName.toString();
        final String importerLower = importerName.toLowerCase(Locale.ROOT);
        if ((importerLower.endsWith(".atto") || importerLower.endsWith(".ores")
                || importerLower.endsWith(".java"))
                && !(importerName.endsWith(".atto") || importerName.endsWith(".ores")
                        || importerName.endsWith(".java"))) {
            throw new IllegalArgumentException(
                    "noncanonical importer source extension: use exactly .atto, .ores or .java");
        }
        final boolean canonicalAttoImporter = importerName.endsWith(".atto");
        String unix = importPath.replace('\\', '/');
        Path raw = Path.of(unix);
        if (raw.isAbsolute()) return existingSource(raw, canonicalAttoImporter);

        if (unix.startsWith(".")) {
            Path parent = importer.toAbsolutePath().normalize().getParent();
            if (parent == null) parent = Path.of("").toAbsolutePath().normalize();
            return existingSource(parent.resolve(raw).normalize(), canonicalAttoImporter);
        }

        for (Path root : searchRoots()) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path candidate = normalizedRoot.resolve(raw).normalize();
            if (!candidate.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException(
                        "bare import '" + importPath + "' escapes configured search root '" + normalizedRoot + "'");
            }
            Optional<Path> resolved = existingSource(candidate, canonicalAttoImporter);
            if (resolved.isPresent()) return resolved;
        }
        return Optional.empty();
    }

    private Optional<Path> existingSource(Path candidate, boolean canonicalAttoImporter) {
        // Never query existence until the candidate belongs to an authorized
        // root and all lexically named descendants are checked for symlinks.
        Path normalized = requireSafeSourceCandidate(candidate);
        String sourceName = normalized.getFileName().toString();
        String lowerName = sourceName.toLowerCase(Locale.ROOT);
        // Source suffixes are part of the logical source identity: accepting
        // .ATTO/.OrEs on one filesystem and case-folding them on another
        // silently aliases different source units. Deny an explicitly
        // noncanonical spelling before probing that file's existence.
        if (lowerName.endsWith(".atto") || lowerName.endsWith(".ores")
                || lowerName.endsWith(".java")) {
            if (!(sourceName.endsWith(".atto") || sourceName.endsWith(".ores")
                    || sourceName.endsWith(".java"))) {
                throw new IllegalArgumentException(
                        "noncanonical source extension: use exactly .atto, .ores or .java");
            }
            return Files.isRegularFile(normalized)
                    ? Optional.of(normalized) : Optional.empty();
        }

        // A canonical .atto importer may select only the canonical spelling
        // when extensionless. Do not probe a legacy sibling (or silently
        // downgrade into host .java) if the .atto candidate is absent.
        Path atto = requireSafeSourceCandidate(Path.of(normalized.toString() + ".atto"));
        if (canonicalAttoImporter) {
            return Files.isRegularFile(atto) ? Optional.of(atto) : Optional.empty();
        }

        // Legacy importers must spell a twin's extension explicitly.
        Path ores = requireSafeSourceCandidate(Path.of(normalized.toString() + ".ores"));
        boolean hasAtto = Files.isRegularFile(atto);
        boolean hasOres = Files.isRegularFile(ores);
        if (hasAtto && hasOres) {
            throw new IllegalArgumentException(
                    "ambiguous legacy source import: both .atto and .ores exist; use explicit extension");
        }
        if (hasAtto) return Optional.of(atto.toAbsolutePath().normalize());
        if (hasOres) return Optional.of(ores.toAbsolutePath().normalize());

        Path java = requireSafeSourceCandidate(Path.of(normalized.toString() + ".java"));
        if (Files.isRegularFile(java)) return Optional.of(java);

        return Optional.empty();
    }

    /**
     * Source-root authority must precede extensionless suffix probing.
     * Only the selected root (which may itself be a trusted checkout symlink)
     * is exempt from the descendant symlink check.
     */
    private Path requireSafeSourceCandidate(Path candidate) {
        final Path normalized = candidate.toAbsolutePath().normalize();
        Path authorizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!normalized.startsWith(authorizedRoot)) {
            authorizedRoot = null;
            for (Path root : searchRoots()) {
                Path approved = root.toAbsolutePath().normalize();
                if (normalized.startsWith(approved)) {
                    authorizedRoot = approved;
                    break;
                }
            }
        }
        if (authorizedRoot == null || normalized.equals(authorizedRoot)) {
            throw new IllegalArgumentException(
                    "source file is outside the project root and configured import roots");
        }
        Path cursor = authorizedRoot;
        for (Path part : authorizedRoot.relativize(normalized)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException(
                        "source import traverses a symlink alias before file admission");
            }
        }
        return normalized;
    }

    private static Path directoryFor(Path start) {
        Path absolute = start.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) return absolute;
        Path parent = absolute.getParent();
        return parent == null ? Path.of("").toAbsolutePath().normalize() : parent;
    }

    private static List<Path> parseEnvironmentPaths(Map<String, String> environment) {
        String raw = environment.get(ENV_ORESLANG_PATH);
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

    private static List<String> stringArray(TomlParseResult toml, String key) {
        TomlArray array = toml.getArray(key);
        if (array == null) return List.of();

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
