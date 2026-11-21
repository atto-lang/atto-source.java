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
 * Loads compiler-owned AttoLang library source from packaged resources.
 *
 * <p>Canonical public namespaces are {@code atto.core/*},
 * {@code atto.alloc/*} and {@code atto.std/*}, with the actor runtime optional.
 * Java currently implements only Core and hosted Std registry resolution;
 * Alloc and actor namespace imports are reserved and fail closed until their
 * dependency/effect/provider closure is compiled. Legacy ores.core, ores.std
 * and std aliases keep existing module IDs for binary compatibility.</p>
 */
public final class CoreLibrarySources {
    private static final String ATTO_CORE_IMPORT_PREFIX = "atto.core/";
    private static final String CORE_IMPORT_PREFIX = "ores.core/";
    private static final String CORE_UNIT_PREFIX = "@ores.core/";
    // Only canonical .atto resources are compiled; legacy .ores remains an
    // identity-compatible import spelling, not a second active source.
    private static final String CORE_RESOURCE_PREFIX = "dev/atto/core/";

    private static final String ATTO_STD_IMPORT_PREFIX = "atto.std/";
    private static final String STD_IMPORT_PREFIX = "ores.std/";
    private static final String LEGACY_STD_IMPORT_PREFIX = "std/";
    private static final String STD_UNIT_PREFIX = "@std/";
    private static final String STD_RESOURCE_PREFIX = "dev/oreslang/std/";

    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_IMPORT_PATH_CHARS = 4096;
    private static final int MAX_MODULE_SEGMENTS = 32;

    private static final Set<String> CORE_SOURCES = Set.of("numeric.atto");

    // Preserve the complete existing hosted std registry. New hosted modules
    // remain explicit additions to this closed set.
    private static final Set<String> STD_SOURCES = Set.of(
            "core.ores", "testing.ores", "assert.ores", "trace.ores",
            "http_wire.ores", "compute.ores", "db_pool.ores",
            "actor_persistence.ores", "data_pool.ores", "otp/supervisor.ores");

    private CoreLibrarySources() { }

    /** Name the namespace the caller actually spelled; legacy aliases keep their diagnostic. */
    private static String libraryDiagnostic(String importPath, String layer) {
        String normalized = importPath.replace('\\', '/');
        return normalized.startsWith("atto." + layer + "/")
                ? "unknown compiler-owned atto." + layer + " module: "
                : "unknown compiler-owned ores." + layer + " module (legacy alias of atto." + layer + "): ";
    }

    /** Canonical atto.core namespace, retaining the ores.core alias. */
    public static boolean isOresCoreImport(String importPath) {
        if (importPath == null) return false;
        String normalized = importPath.replace('\\', '/');
        return normalized.startsWith(ATTO_CORE_IMPORT_PREFIX)
                || normalized.startsWith(CORE_IMPORT_PREFIX);
    }

    /** Canonical atto.std namespace and two legacy hosted aliases. */
    public static boolean isStdImport(String importPath) {
        if (importPath == null) return false;
        String normalized = importPath.replace('\\', '/');
        return normalized.startsWith(ATTO_STD_IMPORT_PREFIX)
                || normalized.startsWith(STD_IMPORT_PREFIX)
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
        return normalized.equals("atto.core")
                || normalized.equals("atto.alloc")
                || normalized.equals("atto.std")
                || normalized.equals("atto.runtime.actor")
                || normalized.startsWith(ATTO_CORE_IMPORT_PREFIX)
                || normalized.startsWith("atto.alloc/")
                || normalized.startsWith(ATTO_STD_IMPORT_PREFIX)
                || normalized.startsWith("atto.runtime.actor/")
                || normalized.equals("ores.core")
                || normalized.equals("ores.std")
                // std/* predates atto.std but remains compiler-owned and
                // must never fall back to a user-controlled local file.
                || normalized.equals("std")
                || normalized.startsWith(LEGACY_STD_IMPORT_PREFIX)
                || normalized.startsWith(CORE_IMPORT_PREFIX)
                || normalized.startsWith(STD_IMPORT_PREFIX);
    }

    public static Set<String> packagedCorePaths() {
        return CORE_SOURCES;
    }

    public static String oresCoreUnitId(String importPath) {
        // Keep historical link/unit IDs stable while physical source becomes .atto.
        String source = canonicalCoreRelativePath(importPath);
        return CORE_UNIT_PREFIX + source.substring(0, source.length() - 5) + ".ores";
    }

    public static String oresCoreFileName(String importPath) {
        return fileNameFromRelative(canonicalCoreRelativePath(importPath));
    }

    public static String oresCoreSource(String importPath) throws IOException {
        String relative = canonicalCoreRelativePath(importPath);
        if (!CORE_SOURCES.contains(relative)) {
            throw new IllegalArgumentException(
                    libraryDiagnostic(importPath, "core") + importPath);
        }
        return readPackagedSource(CORE_RESOURCE_PREFIX + relative, importPath);
    }

    public static Set<String> packagedSourcePaths() {
        return STD_SOURCES;
    }

    /** Hosted std identity remains stable while public imports migrate. */
    public static String unitId(String importPath) {
        return STD_UNIT_PREFIX + canonicalStdRelativePath(importPath);
    }

    public static String source(String importPath) throws IOException {
        String relative = canonicalStdRelativePath(importPath);
        if (!STD_SOURCES.contains(relative)) {
            if (importPath.startsWith(LEGACY_STD_IMPORT_PREFIX)) {
                throw new IllegalArgumentException(
                        "unknown Oreslang core-library module: " + importPath);
            }
            throw new IllegalArgumentException(
                    libraryDiagnostic(importPath, "std") + importPath);
        }
        return readPackagedSource(STD_RESOURCE_PREFIX + relative, importPath);
    }

    public static String fileName(String importPath) {
        return fileNameFromRelative(canonicalStdRelativePath(importPath));
    }

    private static String canonicalCoreRelativePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        String relative;
        if (importPath.startsWith(ATTO_CORE_IMPORT_PREFIX)) {
            relative = canonicalPathBody(importPath, ATTO_CORE_IMPORT_PREFIX);
        } else if (importPath.startsWith(CORE_IMPORT_PREFIX)) {
            relative = canonicalPathBody(importPath, CORE_IMPORT_PREFIX);
        } else {
            throw new IllegalArgumentException(
                    "canonical core imports must start with 'atto.core/': " + importPath);
        }
        // canonicalPathBody performs strict traversal/segment validation and
        // historically normalizes to .ores. Physical Core source is .atto.
        return relative.substring(0, relative.length() - 5) + ".atto";
    }

    private static String canonicalStdRelativePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        if (importPath.startsWith(ATTO_STD_IMPORT_PREFIX)) {
            return canonicalPathBody(importPath, ATTO_STD_IMPORT_PREFIX);
        }
        if (importPath.startsWith(STD_IMPORT_PREFIX)) {
            return canonicalPathBody(importPath, STD_IMPORT_PREFIX);
        }
        if (importPath.startsWith(LEGACY_STD_IMPORT_PREFIX)) {
            return canonicalPathBody(importPath, LEGACY_STD_IMPORT_PREFIX);
        }
        throw new IllegalArgumentException(
                "standard-library imports must start with 'atto.std/': " + importPath);
    }

    private static String canonicalPathBody(String importPath, String prefix) {
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
        if (raw.endsWith(".ores") || raw.endsWith(".atto")) {
            raw = raw.substring(0, raw.length() - 5);
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Oreslang library import must name a module");
        }

        // Java's one-argument String.split discards trailing empty fields:
        // "numeric/".split("/") gives only ["numeric"], accidentally
        // admitting a noncanonical library namespace spelling. Preserve all
        // fields so trailing and repeated separators fail validation.
        String[] segments = raw.split("/", -1);
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
        return String.join("/", normalized) + ".ores";
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
