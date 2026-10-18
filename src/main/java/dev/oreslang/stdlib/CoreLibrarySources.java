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
 * Loads Oreslang-owned core-library source from packaged resources.
 *
 * <p>The core library is intentionally source-first: semantics live in .ores
 * modules. This Java class is loader glue only and must never become an
 * implementation backdoor for stdlib behavior.</p>
 */
public final class CoreLibrarySources {
    private static final String IMPORT_PREFIX = "std/";
    private static final String UNIT_PREFIX = "@std/";
    private static final String RESOURCE_PREFIX = "dev/oreslang/std/";
    private static final String CORE_RESOURCE_PREFIX = "dev/oreslang/core/";
    private static final Set<String> FREESTANDING_CORE_SOURCES = Set.of("numeric.ores");

    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_IMPORT_PATH_CHARS = 4096;
    private static final int MAX_MODULE_SEGMENTS = 32;
    // Closed registry: a classpath resource must never create an unapproved
    // stdlib module. Update deliberately when adding a new native .ores module.
    private static final Set<String> PACKAGED_SOURCES = Set.of("core.ores", "testing.ores", "assert.ores", "trace.ores", "http_wire.ores");

    private CoreLibrarySources() { }

    public static boolean isCoreImport(String importPath) {
        // A Windows-style separator must never skip the trusted std/ loader
        // and fall through to an application-owned std/core.ores path.
        return importPath != null
                && (importPath.startsWith(IMPORT_PREFIX)
                        || importPath.startsWith("std\\"));
    }

    /**
     * Reserve future freestanding library import roots before any filesystem
     * or ORESLANG_PATH lookup. A project file under core/ must never be
     * mistaken for compiler-owned, heap-free core source.
     *
     * Only bare package paths are reserved; explicitly relative imports such
     * as "./core/utility" remain ordinary application filesystem imports.
     * This is an admission guard, not yet a freestanding module resolver.
     */
    public static boolean isReservedFreestandingImport(String importPath) {
        if (importPath == null) return false;
        // Filesystem resolution normalizes backslash to slash, so policy
        // checks must classify the same path form *before* that resolution.
        String normalized = importPath.replace('\\', '/');
        return normalized.startsWith("core/")
                || normalized.startsWith("alloc/")
                || normalized.startsWith("actor/")
                || normalized.startsWith("otp/");
    }

    /** A closed canonical source registry; never search project roots. */
    public static boolean isFreestandingCoreImport(String importPath) {
        return importPath != null
                && (importPath.startsWith("core/") || importPath.startsWith("core\\"));
    }

    public static Set<String> packagedFreestandingCorePaths() {
        return FREESTANDING_CORE_SOURCES;
    }

    public static String freestandingCoreUnitId(String importPath) {
        return "@core/" + canonicalFreestandingCorePath(importPath);
    }

    public static String freestandingCoreFileName(String importPath) {
        String relative = canonicalFreestandingCorePath(importPath);
        return relative.substring(relative.lastIndexOf('/') + 1);
    }

    public static String freestandingCoreSource(String importPath) throws IOException {
        String relative = canonicalFreestandingCorePath(importPath);
        if (!FREESTANDING_CORE_SOURCES.contains(relative)) {
            throw new IllegalArgumentException(
                    "unknown compiler-owned core module: " + importPath);
        }
        return readPackagedSource(CORE_RESOURCE_PREFIX + relative, importPath);
    }

    private static String canonicalFreestandingCorePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        if (!importPath.startsWith("core/")) {
            throw new IllegalArgumentException(
                    "canonical core imports must start with 'core/': " + importPath);
        }
        return canonicalRelativePath("std/" + importPath.substring("core/".length()));
    }

    public static Set<String> packagedSourcePaths() {
        return PACKAGED_SOURCES;
    }

    public static String unitId(String importPath) {
        return UNIT_PREFIX + canonicalRelativePath(importPath);
    }

    public static String source(String importPath) throws IOException {
        String relative = canonicalRelativePath(importPath);
        if (!PACKAGED_SOURCES.contains(relative)) {
            throw new IllegalArgumentException(
                    "unknown Oreslang core-library module: " + importPath);
        }
        return readPackagedSource(RESOURCE_PREFIX + relative, importPath);
    }

    /** Classpath identity and duplicate-resource protections shared by both registries. */
    private static String readPackagedSource(String resource, String importPath) throws IOException {
        ClassLoader loader = CoreLibrarySources.class.getClassLoader();
        // Class-relative lookup retains the owning named module. A loader-wide
        // enumeration cannot see non-open module resources in modular launchers.
        URL unique = CoreLibrarySources.class.getResource("/" + resource);
        if (unique == null) {
            throw new IllegalArgumentException(
                    "unknown Oreslang core-library module: " + importPath);
        }
        // Packaged stdlib is compiler authority, not ambient classpath policy.
        // Ambiguous duplicate resource names must never silently select a
        // user-controlled earlier classpath entry.
        Enumeration<URL> matches = loader.getResources(resource);
        while (matches.hasMoreElements()) {
            URL candidate = matches.nextElement();
            if (!candidate.toExternalForm().equals(unique.toExternalForm())) {
                throw new SecurityException(
                        "duplicate packaged Oreslang core-library resource: " + importPath);
            }
        }
        try (InputStream input = unique.openStream()) {
            byte[] bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) {
                throw new IllegalStateException(
                        "Oreslang core-library module exceeds " + MAX_SOURCE_BYTES
                                + " bytes: " + importPath);
            }

            // The language parser must never see replacement characters created
            // by silently decoding malformed UTF-8 in trusted packaged source.
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException invalidUtf8) {
                throw new IllegalArgumentException(
                        "packaged std/* module contains invalid UTF-8: " + importPath,
                        invalidUtf8);
            }
        }
    }

    public static String fileName(String importPath) {
        String relative = canonicalRelativePath(importPath);
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    private static String canonicalRelativePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        if (!importPath.startsWith(IMPORT_PREFIX)) {
            throw new IllegalArgumentException(
                    "core-library imports must start with 'std/': " + importPath);
        }
        if (importPath.length() > MAX_IMPORT_PATH_CHARS) {
            throw new IllegalArgumentException("Oreslang core-library import path is too long");
        }
        if (importPath.indexOf('\\') >= 0
                || importPath.indexOf('\0') >= 0
                || importPath.startsWith("/")
                || importPath.contains("//")) {
            throw new IllegalArgumentException(
                    "invalid Oreslang core-library import path: " + importPath);
        }

        String raw = importPath.substring(IMPORT_PREFIX.length());
        if (raw.endsWith(".ores")) raw = raw.substring(0, raw.length() - ".ores".length());
        if (raw.isBlank()) {
            throw new IllegalArgumentException("core-library import must name a module");
        }

        String[] segments = raw.split("/");
        if (segments.length > MAX_MODULE_SEGMENTS) {
            throw new IllegalArgumentException("Oreslang core-library import nesting is too deep");
        }
        List<String> normalized = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "core-library import may not traverse directories: " + importPath);
            }
            if (!segment.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException(
                        "invalid core-library path segment '" + segment + "': " + importPath);
            }
            normalized.add(segment);
        }
        return String.join("/", normalized) + ".ores";
    }
}
