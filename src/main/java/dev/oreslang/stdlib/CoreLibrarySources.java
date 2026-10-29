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

    private static final Set<String> CORE_SOURCES = Set.of("numeric.ores");

    // Preserve the complete existing hosted std registry. New hosted modules
    // remain explicit additions to this closed set.
    private static final Set<String> STD_SOURCES = Set.of(
            "core.ores", "testing.ores", "assert.ores", "trace.ores",
            "http_wire.ores", "compute.ores", "db_pool.ores",
            "actor_persistence.ores", "data_pool.ores");

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

    /**
     * Reserved unsafe standard-library namespace. A path boundary is mandatory:
     * std/unsafe_math is not std/unsafe. Accept historical spelling variants
     * solely so none can escape into the generic package/filesystem resolver.
     * No native FFI package is shipped by this registry yet.
     */
    public static boolean isUnsafeStdImport(String importPath) {
        if (importPath == null) return false;
        String path = importPath.replace('\\', '/');
        // Package aliases must be interpreted after lexical dot-segment
        // normalization. Otherwise std/./unsafe can escape an exact-root
        // check and reach a resolver that canonicalizes the path later.
        if (path.startsWith("std/") || path.startsWith("ores.std/")) {
            java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
            for (String component : path.split("/", -1)) {
                if (component.isEmpty() || component.equals(".")) continue;
                if (component.equals("..")) {
                    if (segments.size() > 1) segments.removeLast();
                    else return true; // Noncanonical escape from std root: fail closed.
                } else {
                    segments.addLast(component);
                }
            }
            path = String.join("/", segments);
        } else if (path.indexOf('/') >= 0) {
            // A relative import can normalize into a compiler-owned stdlib
            // identity (./std/unsafe or pkg/../std/unsafe). Reject its final
            // identity, never let the filesystem linker decide after admission.
            java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
            for (String component : path.split("/", -1)) {
                if (component.isEmpty() || component.equals(".")) continue;
                if (component.equals("..")) {
                    if (!segments.isEmpty()) segments.removeLast();
                } else {
                    segments.addLast(component);
                }
            }
            path = String.join("/", segments);
        }
        for (String root : new String[] {
                "ores.std/unsafe", "std/unsafe", "ores.std.unsafe",
                "std.unsafe", "ores.std:unsafe", "std:unsafe"}) {
            if (path.equals(root) || path.equals(root + ".ores")
                    || path.startsWith(root + "/") || path.startsWith(root + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prevent linked import metadata from laundering the compiler-owned unsafe
     * module behind a benign path. Resolve/import unit IDs are not source paths,
     * and must be checked independently of the original import declaration.
     */
    public static boolean isUnsafeStdUnitId(String unitId) {
        if (unitId == null) return false;
        String normalized = unitId.replace('\\', '/');
        // Linked source identity adds only the compiler-owned '@' marker.
        // Reuse the import-path classifier so dotted and colon aliases cannot
        // bypass the link gate when a package resolver supplies a unit ID.
        return normalized.startsWith("@")
                && isUnsafeStdImport(normalized.substring(1));
    }

    /** Reserve canonical library roots before filesystem/ORESLANG_PATH fallback. */
    public static boolean isReservedOresLibraryImport(String importPath) {
        if (importPath == null) return false;
        String normalized = importPath.replace('\\', '/');
        return isUnsafeStdImport(importPath)
                || normalized.equals("ores.core")
                || normalized.equals("ores.std")
                || normalized.startsWith(CORE_IMPORT_PREFIX)
                || normalized.startsWith(STD_IMPORT_PREFIX);
    }

    public static Set<String> packagedCorePaths() {
        return CORE_SOURCES;
    }

    public static String oresCoreUnitId(String importPath) {
        return CORE_UNIT_PREFIX + canonicalCoreRelativePath(importPath);
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
        return canonicalPathBody(importPath, CORE_IMPORT_PREFIX);
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
        if (raw.endsWith(".ores")) {
            raw = raw.substring(0, raw.length() - ".ores".length());
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
