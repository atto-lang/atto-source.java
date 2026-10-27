package dev.oreslang.stdlib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads compiler-owned Oreslang library source from packaged resources.
 *
 * <p>Public library architecture has exactly two namespaces:
 * {@code ores.core/*} is the freestanding/no-OS layer and
 * {@code ores.std/*} is the hosted/platform layer. Legacy {@code std/*}
 * imports remain a compatibility spelling for the hosted layer during
 * migration; they never create a third capability layer.</p>
 */
public final class CoreLibrarySources {
    private static final String STD_IMPORT_PREFIX = "ores.std/";
    private static final String LEGACY_STD_IMPORT_PREFIX = "std/";
    private static final String STD_UNIT_PREFIX = "@std/";
    private static final String STD_RESOURCE_PREFIX = "dev/oreslang/std/";

    private static final String CORE_IMPORT_PREFIX = "ores.core/";
    private static final String CORE_UNIT_PREFIX = "@ores.core/";
    private static final String CORE_RESOURCE_PREFIX = "dev/oreslang/core/";

    private static final Set<String> CORE_SOURCES = Set.of("numeric.ores");
    private static final Set<String> PACKAGED_SOURCES =
            Set.of("core.ores", "testing.ores", "assert.ores", "trace.ores", "http_wire.ores");

    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_IMPORT_PATH_CHARS = 4096;
    private static final int MAX_MODULE_SEGMENTS = 32;

    private CoreLibrarySources() { }

    /** Canonical ores.std/* plus the temporary legacy std/* spelling. */
    public static boolean isStdImport(String importPath) {
        return importPath != null
                && (importPath.startsWith(STD_IMPORT_PREFIX)
                    || importPath.startsWith("ores.std\\")
                    || importPath.startsWith(LEGACY_STD_IMPORT_PREFIX)
                    || importPath.startsWith("std\\"));
    }

    /** Canonical freestanding library namespace. */
    public static boolean isOresCoreImport(String importPath) {
        return importPath != null
                && (importPath.startsWith(CORE_IMPORT_PREFIX)
                    || importPath.startsWith("ores.core\\"));
    }

    /**
     * Reserve canonical Oreslang library roots before filesystem/ORESLANG_PATH
     * fallback. Explicit relative imports such as ./ores.core/utility remain
     * ordinary application paths.
     */
    public static boolean isReservedOresLibraryImport(String importPath) {
        if (importPath == null) return false;
        String normalized = importPath.replace('\\', '/');
        return normalized.startsWith(CORE_IMPORT_PREFIX)
                || normalized.startsWith(STD_IMPORT_PREFIX);
    }

    public static Set<String> packagedCorePaths() {
        return CORE_SOURCES;
    }

    public static String oresCoreUnitId(String importPath) {
        return CORE_UNIT_PREFIX + canonicalCoreRelativePath(importPath);
    }

    public static String oresCoreFileName(String importPath) {
        String relative = canonicalCoreRelativePath(importPath);
        return relative.substring(relative.lastIndexOf('/') + 1);
    }

    public static String oresCoreSource(String importPath) throws IOException {
        String relative = canonicalCoreRelativePath(importPath);
        if (!CORE_SOURCES.contains(relative)) {
            throw new IllegalArgumentException(
                    "unknown compiler-owned ores.core module: " + importPath);
        }
        return readPackagedSource(CORE_RESOURCE_PREFIX + relative, importPath);
    }

    private static String canonicalCoreRelativePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        if (!importPath.startsWith(CORE_IMPORT_PREFIX)) {
            throw new IllegalArgumentException(
                    "canonical core imports must start with 'ores.core/': " + importPath);
        }
        return canonicalPathBody(importPath, CORE_IMPORT_PREFIX);
    }

    public static Set<String> packagedSourcePaths() {
        return PACKAGED_SOURCES;
    }

    /** Hosted std identity remains stable while public imports migrate to ores.std/*. */
    public static String unitId(String importPath) {
        return STD_UNIT_PREFIX + canonicalStdRelativePath(importPath);
    }

    public static String source(String importPath) throws IOException {
        String relative = canonicalStdRelativePath(importPath);
        if (!PACKAGED_SOURCES.contains(relative)) {
            throw new IllegalArgumentException(
                    "unknown compiler-owned ores.std module: " + importPath);
        }
        return readPackagedSource(STD_RESOURCE_PREFIX + relative, importPath);
    }

    private static String canonicalStdRelativePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        if (importPath.startsWith(STD_IMPORT_PREFIX)) {
            return canonicalPathBody(importPath, STD_IMPORT_PREFIX);
        }
        if (importPath.startsWith(LEGACY_STD_IMPORT_PREFIX)) {
            return canonicalPathBody(importPath, LEGACY_STD_IMPORT_PREFIX);
        }
        throw new IllegalArgumentException(
                "standard-library imports must start with 'ores.std/': " + importPath);
    }

    /** Classpath identity and duplicate-resource protections shared by both registries. */
    private static String readPackagedSource(String resource, String importPath) throws IOException {
        ClassLoader loader = CoreLibrarySources.class.getClassLoader();
        URL unique = CoreLibrarySources.class.getResource("/" + resource);
        if (unique == null) {
            throw new IllegalArgumentException(
                    "unknown compiler-owned Oreslang library module: " + importPath);
        }
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

    public static String fileName(String importPath) {
        String relative = canonicalStdRelativePath(importPath);
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
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
        if (raw.endsWith(".ores")) raw = raw.substring(0, raw.length() - ".ores".length());
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
        return String.join("/", normalized) + ".ores";
    }
}
