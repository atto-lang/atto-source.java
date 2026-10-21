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
    private static final String STD_IMPORT_PREFIX = "std/";
    private static final String ALLOC_IMPORT_PREFIX = "alloc/";
    private static final String STD_UNIT_PREFIX = "@std/";
    private static final String ALLOC_UNIT_PREFIX = "@alloc/";
    private static final String RESOURCE_PREFIX = "dev/oreslang/std/";
    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_IMPORT_PATH_CHARS = 4096;
    private static final int MAX_MODULE_SEGMENTS = 32;
    // Closed registry: a classpath resource must never create an unapproved
    // stdlib module. Update deliberately when adding a new native .ores module.
    private static final Set<String> PACKAGED_SOURCES = Set.of(
            "core.ores",
            "testing.ores",
            "assert.ores",
            "trace.ores",
            "http_wire.ores",
            "alloc/json.ores");

    private CoreLibrarySources() { }

    public static boolean isCoreImport(String importPath) {
        return importPath != null && importPath.startsWith(STD_IMPORT_PREFIX);
    }

    public static boolean isAllocImport(String importPath) {
        return importPath != null && importPath.startsWith(ALLOC_IMPORT_PREFIX);
    }

    public static boolean isBundledImport(String importPath) {
        return isCoreImport(importPath) || isAllocImport(importPath);
    }

    public static Set<String> packagedSourcePaths() {
        return PACKAGED_SOURCES;
    }

    public static String importPathForPackagedSource(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        String normalized = relativePath.replace('\\', '/');
        if (!PACKAGED_SOURCES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "unknown packaged Oreslang library source: " + relativePath);
        }
        String withoutExtension = normalized.substring(
                0, normalized.length() - ".ores".length());
        if (withoutExtension.startsWith("alloc/")) {
            return withoutExtension;
        }
        return STD_IMPORT_PREFIX + withoutExtension;
    }

    public static String unitId(String importPath) {
        String modulePath = canonicalModulePath(importPath);
        return (isAllocImport(importPath) ? ALLOC_UNIT_PREFIX : STD_UNIT_PREFIX)
                + modulePath;
    }

    public static String source(String importPath) throws IOException {
        String relative = resourceRelativePath(importPath);
        if (!PACKAGED_SOURCES.contains(relative)) {
            throw new IllegalArgumentException(
                    "unknown Oreslang bundled-library module: " + importPath);
        }
        String resource = RESOURCE_PREFIX + relative;
        ClassLoader loader = CoreLibrarySources.class.getClassLoader();
        // Class-relative lookup retains the owning named module. A loader-wide
        // enumeration cannot see non-open module resources in modular launchers.
        URL unique = CoreLibrarySources.class.getResource("/" + resource);
        if (unique == null) {
            throw new IllegalArgumentException(
                    "unknown Oreslang bundled-library module: " + importPath);
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
        String relative = canonicalModulePath(importPath);
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    private static String resourceRelativePath(String importPath) {
        String modulePath = canonicalModulePath(importPath);
        return isAllocImport(importPath) ? "alloc/" + modulePath : modulePath;
    }

    private static String canonicalModulePath(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        final String prefix;
        if (importPath.startsWith(STD_IMPORT_PREFIX)) {
            prefix = STD_IMPORT_PREFIX;
        } else if (importPath.startsWith(ALLOC_IMPORT_PREFIX)) {
            prefix = ALLOC_IMPORT_PREFIX;
        } else {
            throw new IllegalArgumentException(
                    "bundled-library imports must start with 'std/' or 'alloc/': "
                            + importPath);
        }
        if (importPath.length() > MAX_IMPORT_PATH_CHARS) {
            throw new IllegalArgumentException(
                    "Oreslang bundled-library import path is too long");
        }
        if (importPath.indexOf('\\') >= 0
                || importPath.indexOf('\0') >= 0
                || importPath.startsWith("/")
                || importPath.contains("//")) {
            throw new IllegalArgumentException(
                    "invalid Oreslang bundled-library import path: " + importPath);
        }

        String raw = importPath.substring(prefix.length());
        if (raw.endsWith(".ores")) {
            raw = raw.substring(0, raw.length() - ".ores".length());
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException(
                    "bundled-library import must name a module");
        }

        String[] segments = raw.split("/");
        if (segments.length > MAX_MODULE_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Oreslang bundled-library import nesting is too deep");
        }
        List<String> normalized = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "bundled-library import may not traverse directories: "
                                + importPath);
            }
            if (!segment.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException(
                        "invalid bundled-library path segment '" + segment
                                + "': " + importPath);
            }
            normalized.add(segment);
        }
        return String.join("/", normalized) + ".ores";
    }
}
