package dev.oreslang.stdlib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads compiler-owned Oreslang library source from packaged resources.
 *
 * <p>Oreslang exposes exactly two public library namespaces:
 * {@code ores.core/*} is freestanding/no-OS source and {@code ores.std/*}
 * is hosted/platform source. Legacy {@code std/*} remains a compatibility
 * spelling for {@code ores.std/*}; it is not a third library layer.</p>
 */
public final class CoreLibrarySources {
    private static final String CORE_IMPORT_PREFIX = "ores.core/";
    private static final String CORE_UNIT_PREFIX = "@ores.core/";
    private static final String CORE_RESOURCE_PREFIX = "dev/oreslang/core/";

    private static final String STD_IMPORT_PREFIX = "ores.std/";
    private static final String LEGACY_STD_IMPORT_PREFIX = "std/";
    private static final String STD_UNIT_PREFIX = "@std/";
    private static final String STD_RESOURCE_PREFIX = "dev/oreslang/std/";

    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_IMPORT_PATH_CHARS = 4096;
    private static final int MAX_MODULE_SEGMENTS = 32;

    private static final Set<String> CORE_SOURCES = Set.of("numeric.atto", "protocols.atto");

    // Preserve the complete existing hosted std registry. New hosted modules
    // remain explicit additions to this closed set.
    // Existing .ores modules migrated byte-for-byte to .atto; only these
    // names support .ores as a compatibility alias (not a second resource).
    private static final Set<String> MIGRATED_STD_STEMS = Set.of(
            "core", "assert", "compute", "trace", "db_pool",
            "http_wire", "actor_persistence");

    private static final Set<String> STD_SOURCES = Set.of(
            "core.atto", "testing.ores", "assert.atto", "trace.atto",
            "http_wire.atto", "compute.atto", "db_pool.atto",
            "actor_persistence.atto", "data_pool.ores", "otp/supervisor.ores", "bounds.atto", "text.atto",
            "time.atto");

    private CoreLibrarySources() { }

    /** Canonical freestanding library namespace. */
    public static boolean isOresCoreImport(String importPath) {
        if (importPath == null) return false;
        return importPath.replace('\\', '/').startsWith(CORE_IMPORT_PREFIX);
    }

    /** Canonical hosted namespace plus the temporary legacy std/* spelling. */
    public static boolean isStdImport(String importPath) {
        if (importPath == null) return false;
        String normalized = importPath.replace('\\', '/');
        return normalized.startsWith(STD_IMPORT_PREFIX)
                || normalized.startsWith(LEGACY_STD_IMPORT_PREFIX);
    }

    /**
     * Compatibility for existing Java callers that used "core import" to mean
     * the old std/* compiler-owned registry.
     */
    @Deprecated(forRemoval = false)
    public static boolean isCoreImport(String importPath) {
        return isStdImport(importPath);
    }

    /** Reserve canonical library roots before filesystem/ORESLANG_PATH fallback. */
    public static boolean isReservedOresLibraryImport(String importPath) {
        if (importPath == null) return false;
        String normalized = importPath.replace('\\', '/');
        return normalized.equals("ores.core")
                || normalized.equals("ores.std")
                || normalized.startsWith(CORE_IMPORT_PREFIX)
                || normalized.startsWith(STD_IMPORT_PREFIX);
    }

    public static Set<String> packagedCorePaths() {
        return CORE_SOURCES;
    }

    public static String oresCoreUnitId(String importPath) {
        String relative = canonicalCoreRelativePath(importPath);
        // Linked code-unit identity remains stable during the source migration.
        return CORE_UNIT_PREFIX + (relative.equals("numeric.atto") ? "numeric.ores" : relative);
    }

    public static String oresCoreFileName(String importPath) {
        return fileNameFromRelative(canonicalCoreRelativePath(importPath));
    }

    public static String oresCoreSource(String importPath) throws IOException {
        String relative = canonicalCoreRelativePath(importPath);
        if (!CORE_SOURCES.contains(relative)) {
            throw new IllegalArgumentException(
                    "unknown compiler-owned ores.core module: " + importPath);
        }
        return readPackagedSource(CORE_RESOURCE_PREFIX + relative, importPath);
    }

    public static Set<String> packagedSourcePaths() {
        return STD_SOURCES;
    }

    /** Hosted std identity remains stable while public imports migrate. */
    public static String unitId(String importPath) {
        String relative = canonicalStdRelativePath(importPath);
        if (relative.endsWith(".atto") && MIGRATED_STD_STEMS.contains(
                relative.substring(0, relative.length() - ".atto".length()))) {
            relative = relative.substring(0, relative.length() - ".atto".length()) + ".ores";
        }
        return STD_UNIT_PREFIX + relative;
    }

    public static String source(String importPath) throws IOException {
        String relative = canonicalStdRelativePath(importPath);
        if (!STD_SOURCES.contains(relative)) {
            if (importPath.startsWith(LEGACY_STD_IMPORT_PREFIX)) {
                throw new IllegalArgumentException(
                        "unknown Oreslang core-library module: " + importPath);
            }
            throw new IllegalArgumentException(
                    "unknown compiler-owned ores.std module: " + importPath);
        }
        return readPackagedSource(STD_RESOURCE_PREFIX + relative, importPath);
    }

    public static String fileName(String importPath) {
        return fileNameFromRelative(canonicalStdRelativePath(importPath));
    }

    private static String canonicalCoreRelativePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        if (!importPath.startsWith(CORE_IMPORT_PREFIX)) {
            throw new IllegalArgumentException(
                    "canonical core imports must start with 'ores.core/': " + importPath);
        }
        // Compiler-owned guest modules use canonical .atto bytes; the legacy
        // .ores spelling of a migrated core module is an explicit closed alias
        // to the same unit (no ambient file-system or extension probing).
        String relative = canonicalPathBody(importPath, CORE_IMPORT_PREFIX, CORE_SOURCES);
        if (relative.equals("numeric.ores")) return "numeric.atto";
        if (relative.equals("protocols.ores")) return "protocols.atto";
        return relative;
    }

    private static String canonicalStdRelativePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        if (importPath.startsWith(STD_IMPORT_PREFIX)) {
            return canonicalMigratedStdPath(
                    canonicalPathBody(importPath, STD_IMPORT_PREFIX, STD_SOURCES));
        }
        if (importPath.startsWith(LEGACY_STD_IMPORT_PREFIX)) {
            return canonicalMigratedStdPath(
                    canonicalPathBody(importPath, LEGACY_STD_IMPORT_PREFIX, STD_SOURCES));
        }
        throw new IllegalArgumentException(
                "standard-library imports must start with 'ores.std/': " + importPath);
    }

    private static String canonicalMigratedStdPath(String relative) {
        // Only already-shipped modules have legacy .ores aliases. New .atto
        // modules, such as bounds, must still reject unapproved .ores suffixes.
        if (relative.endsWith(".ores")) {
            String stem = relative.substring(0, relative.length() - ".ores".length());
            if (MIGRATED_STD_STEMS.contains(stem)) return stem + ".atto";
        }
        return relative;
    }

    private static String canonicalPathBody(String importPath, String prefix, Set<String> registry) {
        if (importPath.length() > MAX_IMPORT_PATH_CHARS) {
            throw new IllegalArgumentException("Oreslang library import path is too long");
        }
        if (importPath.indexOf('\\') >= 0
                || importPath.indexOf('\0') >= 0
                || importPath.startsWith("/")
                || importPath.contains("//")) {
            throw new IllegalArgumentException(
                    "invalid Oreslang library import path: " + importPath);
        }

        String raw = importPath.substring(prefix.length());
        // Respect explicit legacy and canonical suffixes.
        String explicitSuffix = raw.endsWith(".atto") ? ".atto"
                : raw.endsWith(".ores") ? ".ores" : null;
        if (explicitSuffix != null) {
            raw = raw.substring(0, raw.length() - explicitSuffix.length());
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Oreslang library import must name a module");
        }

        String[] segments = raw.split("/");
        if (segments.length > MAX_MODULE_SEGMENTS) {
            throw new IllegalArgumentException("Oreslang library import nesting is too deep");
        }
        List<String> normalized = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "Oreslang library import may not traverse directories: " + importPath);
            }
            if (!segment.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException(
                        "invalid Oreslang library path segment '" + segment + "': " + importPath);
            }
            normalized.add(segment);
        }
        String stem = String.join("/", normalized);
        if (explicitSuffix != null) {
            return stem + explicitSuffix;
        }
        String canonical = stem + ".atto";
        return registry.contains(canonical) ? canonical : stem + ".ores";
    }

    private static String fileNameFromRelative(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    private static String readPackagedSource(String resource, String importPath) throws IOException {
        ClassLoader loader = CoreLibrarySources.class.getClassLoader();
        URL unique = CoreLibrarySources.class.getResource("/" + resource);
        if (unique == null) {
            throw new IllegalArgumentException(
                    "unknown compiler-owned Oreslang library module: " + importPath);
        }

        // Packaged libraries are compiler authority. A duplicate classpath
        // resource must never silently select user-controlled source.
        Enumeration<URL> matches = loader.getResources(resource);
        while (matches.hasMoreElements()) {
            URL candidate = matches.nextElement();
            if (!candidate.toExternalForm().equals(unique.toExternalForm())) {
                throw new SecurityException(
                        "duplicate packaged Oreslang library resource: " + importPath);
            }
        }

        try (InputStream input = unique.openStream()) {
            byte[] bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) {
                throw new IllegalStateException(
                        "Oreslang library module exceeds " + MAX_SOURCE_BYTES
                                + " bytes: " + importPath);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException invalidUtf8) {
                throw new IllegalArgumentException(
                        "packaged Oreslang library module contains invalid UTF-8: " + importPath,
                        invalidUtf8);
            }
        }
    }
}
