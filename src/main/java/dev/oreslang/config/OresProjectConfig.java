package dev.oreslang.config;

import dev.oreslang.imports.ImportRules;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Project-level Oreslang compiler configuration.
 *
 * <p>The nearest .ores.pkg.toml, or legacy .oreslangc.cfg.toml when no modern
 * marker is present in that directory, owns the project. A modern package root
 * is exactly the directory containing .ores.pkg.toml. Legacy manifests retain
 * their historical [project].root behavior during migration.</p>
 */
public final class OresProjectConfig {
    public static final String PACKAGE_MANIFEST_NAME = ".ores.pkg.toml";
    public static final String MANIFEST_NAME = ".oreslangc.cfg.toml";
    public static final String ENV_ORES_PATHS = "ORES_PATHS";
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
            Path candidate = manifestInDirectory(cursor);
            if (candidate != null) return load(candidate, environment);
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

    private static Path manifestInDirectory(Path directory) {
        Path modern = directory.resolve(PACKAGE_MANIFEST_NAME);
        if (Files.exists(modern, LinkOption.NOFOLLOW_LINKS)) return modern;

        Path legacy = directory.resolve(MANIFEST_NAME);
        return Files.isRegularFile(legacy) ? legacy : null;
    }

    private static boolean isPackageManifest(Path manifest) {
        return manifest != null
                && manifest.getFileName() != null
                && PACKAGE_MANIFEST_NAME.equals(manifest.getFileName().toString());
    }

    private static final int MAX_MANIFEST_BYTES = 65_536;

    private static String readManifestSnapshot(Path manifest) throws IOException {
        BasicFileAttributes before = Files.readAttributes(
                manifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink()) {
            throw new IllegalArgumentException(
                    "project manifest must remain a regular non-symlink file: " + manifest);
        }
        if (before.size() < 0 || before.size() > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("project manifest exceeds 64 KiB: " + manifest);
        }

        ByteBuffer bytes = ByteBuffer.allocate(MAX_MANIFEST_BYTES + 1);
        try (var channel = Files.newByteChannel(
                manifest,
                java.util.Set.<OpenOption>of(
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS))) {
            while (bytes.hasRemaining()) {
                int count = channel.read(bytes);
                if (count == -1) break;
                if (count == 0) throw new IOException("project manifest read made no progress");
            }
        }

        if (bytes.position() > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("project manifest exceeds 64 KiB: " + manifest);
        }

        BasicFileAttributes after = Files.readAttributes(
                manifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        if (!after.isRegularFile()
                || after.isSymbolicLink()
                || (beforeKey != null && afterKey != null && !beforeKey.equals(afterKey))
                || before.size() != after.size()
                || bytes.position() != before.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !before.creationTime().equals(after.creationTime())) {
            throw new IllegalArgumentException(
                    "project manifest changed during bounded snapshot: " + manifest);
        }

        bytes.flip();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(
                    "project manifest requires valid UTF-8: " + manifest,
                    invalid);
        }
    }

    public static OresProjectConfig load(Path manifest, Map<String, String> environment) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest path cannot be null");
        if (environment == null) throw new IllegalArgumentException("environment cannot be null");

        Path absoluteManifest = manifest.toAbsolutePath().normalize();
        boolean modernPackage = isPackageManifest(absoluteManifest);
        String manifestName = absoluteManifest.getFileName().toString();
        TomlParseResult toml;
        if (modernPackage) {
            BasicFileAttributes manifestAttributes;
            try {
                manifestAttributes = Files.readAttributes(
                        absoluteManifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                throw new IllegalArgumentException(
                        "project manifest does not exist: " + absoluteManifest, missing);
            }
            if (manifestAttributes.isSymbolicLink() || !manifestAttributes.isRegularFile()) {
                throw new IllegalArgumentException(
                        "project manifest must be a regular non-symlink file: " + absoluteManifest);
            }
            toml = Toml.parse(readManifestSnapshot(absoluteManifest));
        } else {
            if (!Files.isRegularFile(absoluteManifest)) {
                throw new IllegalArgumentException(
                        "not an Oreslang project manifest: " + absoluteManifest);
            }
            toml = Toml.parse(absoluteManifest);
        }
        if (!toml.errors().isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid " + manifestName + ": " + toml.errors().getFirst());
        }

        String schemaVersion = toml.getString("schema_version");
        if (schemaVersion == null || !SCHEMA_VERSION.equals(schemaVersion)) {
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
        Path projectRoot;
        if (modernPackage) {
            if (!".".equals(rootText)) {
                throw new IllegalArgumentException(
                        PACKAGE_MANIFEST_NAME
                                + " anchors the package root to its own directory; "
                                + "[project].root must be omitted or '.'");
            }
            projectRoot = manifestDir.toRealPath();
            if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "package manifest parent is not a physical directory: " + manifestDir);
            }
        } else {
            projectRoot = resolvePath(manifestDir, rootText);
        }

        List<String> sourceRootValues = stringArray(toml, "source.roots");
        if (toml.get("source.roots") == null) sourceRootValues = List.of(".");
        List<Path> sourceRoots = modernPackage
                ? resolveModernPackagePaths(
                        projectRoot, sourceRootValues, "source.roots", true)
                : resolvePaths(projectRoot, sourceRootValues);
        List<String> importPathValues = stringArray(toml, "source.import_paths");
        List<Path> importPaths = modernPackage
                ? resolveModernPackagePaths(
                        projectRoot, importPathValues, "source.import_paths", true)
                : resolvePaths(projectRoot, importPathValues);

        String mainText = toml.getString("entrypoints.main");
        Path mainEntrypoint = mainText == null
                ? null
                : (modernPackage
                        ? resolveModernPackagePath(
                                projectRoot, mainText, "entrypoints.main", false)
                        : resolvePath(projectRoot, mainText));

        return new OresProjectConfig(
                absoluteManifest,
                projectRoot,
                modernPackage
                        ? defaultString(toml.getString("package.name"), toml.getString("project.name"))
                        : toml.getString("project.name"),
                modernPackage
                        ? defaultString(toml.getString("package.version"), toml.getString("project.version"))
                        : toml.getString("project.version"),
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
    /** Physical source identity captured at the resolver boundary. */
    public record ResolvedSource(Path path, BasicFileAttributes attributes) {
        public ResolvedSource {
            if (path == null || attributes == null) {
                throw new IllegalArgumentException("resolved source identity cannot be null");
            }
            path = path.toAbsolutePath().normalize();
            if (!attributes.isRegularFile()) {
                throw new IllegalArgumentException(
                        "resolved source identity must describe a regular file: " + path);
            }
        }
    }

    public Optional<Path> resolveImport(Path importer, String importPath) {
        return resolveImportSource(importer, importPath).map(ResolvedSource::path);
    }

    /**
     * Internal resolver used by the compiler when the exact physical file
     * identity must remain bound through the later byte snapshot.
     */
    public Optional<ResolvedSource> resolveImportSource(Path importer, String importPath) {
        if (importer == null) throw new IllegalArgumentException("importer path cannot be null");
        if (importPath == null || importPath.isBlank()) {
            throw new IllegalArgumentException("import path cannot be blank");
        }
        if (ImportRules.isJavaPath(importPath)) return Optional.empty();

        String unix = importPath.replace('\\', '/');
        if (ImportRules.isProjectRootPath(importPath)) {
            String local = ImportRules.projectRootRelativePath(importPath);
            PackageAuthority authority = packageAuthorityForImporter(importer, importPath);
            Path root = authority.root();
            Path candidate = root.resolve(local).normalize();
            Optional<ResolvedSource> resolved =
                    existingSourceWithinRoot(candidate, root, importPath);
            if (resolved.isEmpty()) return Optional.empty();

            rejectNestedPackageBoundary(root, resolved.get().path(), importPath);
            return resolved;
        }

        Path raw = Path.of(unix);
        if (raw.isAbsolute()) return existingSource(raw);

        if (ImportRules.isExplicitRelativePath(unix)) {
            Path lexicalImporter = importer.toAbsolutePath().normalize();
            ResolvedSource physicalImporter = existingSource(lexicalImporter)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "relative import importer does not resolve to a regular source: "
                                    + lexicalImporter));
            Path parent = physicalImporter.path().getParent();
            if (parent == null) parent = Path.of("").toAbsolutePath().normalize();

            Optional<ResolvedSource> resolved = existingSource(parent.resolve(raw).normalize());
            if (resolved.isEmpty() || manifestPath == null) return resolved;

            Path physicalRoot;
            try {
                physicalRoot = projectRoot.toRealPath();
            } catch (IOException failure) {
                throw new IllegalArgumentException(
                        "cannot establish physical project root for relative import '"
                                + importPath + "'",
                        failure);
            }
            if (!Files.isDirectory(physicalRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "project root for relative import is not a directory: " + physicalRoot);
            }

            Path physicalSource = resolved.get().path();
            if (!physicalSource.startsWith(physicalRoot)) {
                throw new IllegalArgumentException(
                        "relative import '" + importPath
                                + "' escapes physical project root '" + physicalRoot + "'");
            }
            rejectNestedPackageBoundary(physicalRoot, physicalSource, importPath);
            return resolved;
        }

        for (Path root : searchRoots()) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path candidate = normalizedRoot.resolve(raw).normalize();
            if (!candidate.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException(
                        "bare import '" + importPath + "' escapes configured search root '" + normalizedRoot + "'");
            }
            Optional<ResolvedSource> resolved =
                    existingSourceWithinRoot(candidate, normalizedRoot, importPath);
            if (resolved.isPresent()) return resolved;
        }
        return Optional.empty();
    }

    private record PackageAuthority(Path manifest, Path root) { }

    private PackageAuthority packageAuthorityForImporter(
            Path importer,
            String importPath) {
        ResolvedSource physicalImporter = existingSource(importer)
                .orElseThrow(() -> new IllegalArgumentException(
                        "project-root import importer does not resolve to a regular source: "
                                + importer));

        for (Path cursor = physicalImporter.path().getParent();
                cursor != null;
                cursor = cursor.getParent()) {
            Path modern = cursor.resolve(PACKAGE_MANIFEST_NAME);
            if (Files.exists(modern, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    OresProjectConfig owner = load(modern, Map.of());
                    return new PackageAuthority(
                            owner.manifestPath().orElseThrow(),
                            owner.projectRoot().toAbsolutePath().normalize());
                } catch (IOException failure) {
                    throw new IllegalArgumentException(
                            "cannot load package authority for project-root import '"
                                    + importPath + "'",
                            failure);
                }
            }

            Path legacy = cursor.resolve(MANIFEST_NAME);
            if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "project-root import is blocked by nearer legacy package authority at '"
                                + cursor + "': " + importPath);
            }
        }

        throw new IllegalArgumentException(
                "project-root import requires " + PACKAGE_MANIFEST_NAME + ": " + importPath);
    }

    private static void rejectNestedPackageBoundary(
            Path physicalRoot,
            Path physicalSource,
            String importPath) {
        for (Path cursor = physicalSource.getParent();
                cursor != null && !cursor.equals(physicalRoot);
                cursor = cursor.getParent()) {
            if (hasPackageMarker(cursor)) {
                throw new IllegalArgumentException(
                        "source import '" + importPath
                                + "' crosses nested package boundary at '" + cursor + "'");
            }
        }
    }

    private static boolean hasPackageMarker(Path directory) {
        return Files.exists(
                        directory.resolve(PACKAGE_MANIFEST_NAME),
                        LinkOption.NOFOLLOW_LINKS)
                || Files.exists(
                        directory.resolve(MANIFEST_NAME),
                        LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Bare-import roots may themselves be symlinked (for developer/package
     * layouts), but every existing child component is authoritative for that
     * root. A malformed/dangling/special component must fail closed instead
     * of becoming an artificial miss that falls through to a lower ambient
     * root.
     */
    private static Optional<ResolvedSource> existingSourceWithinRoot(
            Path candidate,
            Path configuredRoot,
            String importPath) {
        Path lexicalRoot = configuredRoot.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        try {
            try {
                Files.readAttributes(
                        lexicalRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missingRoot) {
                return Optional.empty();
            }

            Path physicalRoot = lexicalRoot.toRealPath();
            if (!Files.isDirectory(physicalRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "configured search root does not resolve to a directory: " + lexicalRoot);
            }

            Path relative = lexicalRoot.relativize(normalizedCandidate);
            Path current = lexicalRoot;
            for (int index = 0; index + 1 < relative.getNameCount(); index++) {
                current = current.resolve(relative.getName(index));
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(
                            current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException missingComponent) {
                    return Optional.empty();
                }

                if (attributes.isSymbolicLink()) {
                    Path physicalComponent = current.toRealPath();
                    requirePhysicalContainment(physicalRoot, physicalComponent, importPath);
                    if (!Files.isDirectory(physicalComponent, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalArgumentException(
                                "bare import '" + importPath
                                        + "' is blocked by a non-directory search-root component '"
                                        + current + "'");
                    }
                } else if (!attributes.isDirectory()) {
                    throw new IllegalArgumentException(
                            "bare import '" + importPath
                                    + "' is blocked by a non-directory search-root component '"
                                    + current + "'");
                }
            }

            Optional<ResolvedSource> exact = regularSourceWithinRoot(
                    normalizedCandidate, physicalRoot, importPath);
            if (exact.isPresent()) return exact;

            String text = normalizedCandidate.toString().toLowerCase();
            if (text.endsWith(".ores") || text.endsWith(".java")) return Optional.empty();

            Optional<ResolvedSource> ores = regularSourceWithinRoot(
                    Path.of(normalizedCandidate.toString() + ".ores"),
                    physicalRoot,
                    importPath);
            if (ores.isPresent()) return ores;

            return regularSourceWithinRoot(
                    Path.of(normalizedCandidate.toString() + ".java"),
                    physicalRoot,
                    importPath);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "cannot verify physical source path for bare import '" + importPath + "'", failure);
        }
    }

    private static Optional<ResolvedSource> regularSourceWithinRoot(
            Path candidate,
            Path physicalRoot,
            String importPath) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }

        if (attributes.isDirectory()) return Optional.empty();
        if (!attributes.isRegularFile() && !attributes.isSymbolicLink()) {
            throw new IllegalArgumentException(
                    "bare import '" + importPath + "' resolves through a non-regular source node '"
                            + candidate + "'");
        }

        Path physicalSource = candidate.toRealPath();
        requirePhysicalContainment(physicalRoot, physicalSource, importPath);
        return Optional.of(captureResolvedSource(
                physicalSource,
                "bare import '" + importPath + "' does not resolve to a regular source file"));
    }

    private static void requirePhysicalContainment(
            Path physicalRoot,
            Path physicalSource,
            String importPath) {
        if (!physicalSource.startsWith(physicalRoot)) {
            throw new IllegalArgumentException(
                    "bare import '" + importPath
                            + "' escapes physical configured search root '" + physicalRoot + "'");
        }
    }

    private static Optional<ResolvedSource> existingSource(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        Optional<ResolvedSource> exact = canonicalRegularSource(normalized);
        if (exact.isPresent()) return exact;

        String text = normalized.toString().toLowerCase();
        if (text.endsWith(".ores") || text.endsWith(".java")) return Optional.empty();

        Optional<ResolvedSource> ores = canonicalRegularSource(
                Path.of(normalized.toString() + ".ores"));
        if (ores.isPresent()) return ores;

        return canonicalRegularSource(Path.of(normalized.toString() + ".java"));
    }

    private static Optional<ResolvedSource> canonicalRegularSource(Path candidate) {
        BasicFileAttributes lexical;
        try {
            lexical = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "cannot inspect filesystem source identity: " + candidate,
                    failure);
        }

        if (lexical.isDirectory()) return Optional.empty();
        if (!lexical.isRegularFile() && !lexical.isSymbolicLink()) {
            throw new IllegalArgumentException(
                    "filesystem import resolves through a non-regular source node: "
                            + candidate);
        }

        try {
            Path physical = candidate.toRealPath();
            return Optional.of(captureResolvedSource(
                    physical,
                    "filesystem import does not resolve to a regular physical source: "
                            + candidate));
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "cannot establish canonical physical source identity: " + candidate,
                    failure);
        }
    }

    private static ResolvedSource captureResolvedSource(
            Path physical,
            String nonRegularMessage) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                physical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException(nonRegularMessage);
        }
        return new ResolvedSource(physical, attributes);
    }

    private static Path directoryFor(Path start) {
        Path absolute = start.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) return absolute;
        Path parent = absolute.getParent();
        return parent == null ? Path.of("").toAbsolutePath().normalize() : parent;
    }

    private static List<Path> parseEnvironmentPaths(Map<String, String> environment) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        appendEnvironmentPaths(paths, environment.get(ENV_ORES_PATHS), ":");
        appendEnvironmentPaths(paths, environment.get(ENV_ORESLANG_PATH), File.pathSeparator);
        return List.copyOf(paths);
    }

    private static void appendEnvironmentPaths(
            LinkedHashSet<Path> paths,
            String raw,
            String separator) {
        if (raw == null || raw.isBlank()) return;

        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (String part : raw.split(java.util.regex.Pattern.quote(separator), -1)) {
            String trimmed = part.trim();
            // Empty entries never grant the current directory.
            if (trimmed.isEmpty()) continue;
            Path path = Path.of(trimmed);
            if (!path.isAbsolute()) path = cwd.resolve(path);
            paths.add(path.toAbsolutePath().normalize());
        }
    }

    private static List<Path> resolveModernPackagePaths(
            Path packageRoot,
            List<String> values,
            String key,
            boolean requireDirectoryWhenPresent) {
        ArrayList<Path> paths = new ArrayList<>(values.size());
        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (String value : values) {
            Path path = resolveModernPackagePath(
                    packageRoot, value, key, requireDirectoryWhenPresent);
            if (seen.add(path)) paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static Path resolveModernPackagePath(
            Path packageRoot,
            String value,
            String key,
            boolean requireDirectoryWhenPresent) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    PACKAGE_MANIFEST_NAME + " key '" + key + "' cannot be blank");
        }
        if (value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    PACKAGE_MANIFEST_NAME + " key '" + key
                            + "' must use a package-relative forward-slash path: " + value);
        }

        if (!".".equals(value)) {
            for (String component : value.split("/", -1)) {
                if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                    throw new IllegalArgumentException(
                            PACKAGE_MANIFEST_NAME + " key '" + key
                                    + "' contains invalid path component: " + value);
                }
            }
        }

        Path relative = Path.of(value);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException(
                    PACKAGE_MANIFEST_NAME + " key '" + key
                            + "' must be relative to the package root: " + value);
        }

        Path root = packageRoot.toAbsolutePath().normalize();
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException(
                    PACKAGE_MANIFEST_NAME + " key '" + key
                            + "' escapes the package root: " + value);
        }

        verifyModernPackagePath(root, candidate, key, requireDirectoryWhenPresent);
        return candidate;
    }

    private static void verifyModernPackagePath(
            Path physicalRoot,
            Path candidate,
            String key,
            boolean requireDirectoryWhenPresent) {
        try {
            Path existing = candidate;
            while (existing != null
                    && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new IllegalArgumentException(
                        "cannot establish package authority for " + key + ": " + candidate);
            }

            Path physicalExisting = existing.toRealPath();
            if (!physicalExisting.startsWith(physicalRoot)) {
                throw new IllegalArgumentException(
                        PACKAGE_MANIFEST_NAME + " key '" + key
                                + "' escapes the physical package root: " + candidate);
            }

            boolean existingDirectory =
                    Files.isDirectory(physicalExisting, LinkOption.NOFOLLOW_LINKS);
            if (existing.equals(candidate)
                    && requireDirectoryWhenPresent
                    && !existingDirectory) {
                throw new IllegalArgumentException(
                        PACKAGE_MANIFEST_NAME + " key '" + key
                                + "' must resolve to a directory when present: " + candidate);
            }
            if (!existing.equals(candidate) && !existingDirectory) {
                throw new IllegalArgumentException(
                        PACKAGE_MANIFEST_NAME + " key '" + key
                                + "' is blocked by a non-directory ancestor: " + existing);
            }

            Path boundaryCursor = existingDirectory
                    ? physicalExisting
                    : physicalExisting.getParent();
            for (Path cursor = boundaryCursor;
                    cursor != null && !cursor.equals(physicalRoot);
                    cursor = cursor.getParent()) {
                if (hasPackageMarker(cursor)) {
                    throw new IllegalArgumentException(
                            PACKAGE_MANIFEST_NAME + " key '" + key
                                    + "' crosses nested package boundary at '" + cursor + "'");
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "cannot verify physical package path for " + key + ": " + candidate,
                    failure);
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
                throw new IllegalArgumentException(
                        "project manifest key '" + key + "' must be an array of strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
