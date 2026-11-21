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
 * Loads Oreslang-owned allocator-backed portable source from packaged resources.
 *
 * <p>The alloc namespace is compiler-owned and cannot be shadowed by project
 * roots or ORESLANG_PATH. This class is loader glue only.</p>
 */
public final class AllocLibrarySources {
    private static final String IMPORT_PREFIX = "alloc/";
    private static final String UNIT_PREFIX = "@alloc/";
    private static final String RESOURCE_PREFIX = "dev/oreslang/alloc/";
    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_IMPORT_PATH_CHARS = 4096;
    private static final int MAX_MODULE_SEGMENTS = 32;
    // Closed registry: a classpath resource must never create an unapproved
    // allocator module. Update deliberately when adding a new native .ores module.
    private static final Set<String> PACKAGED_SOURCES = Set.of("json.ores");

    private AllocLibrarySources() { }

    public static boolean isAllocImport(String importPath) {
        return importPath != null && importPath.startsWith(IMPORT_PREFIX);
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
                    "unknown Oreslang alloc module: " + importPath);
        }
        String resource = RESOURCE_PREFIX + relative;
        ClassLoader loader = AllocLibrarySources.class.getClassLoader();
        // Class-relative lookup retains the owning named module. A loader-wide
        // enumeration cannot see non-open module resources in modular launchers.
        URL unique = AllocLibrarySources.class.getResource("/" + resource);
        if (unique == null) {
            throw new IllegalArgumentException(
                    "unknown Oreslang alloc module: " + importPath);
        }
        // Packaged alloc source is compiler authority, not ambient classpath policy.
        // Ambiguous duplicate resource names must never silently select a
        // user-controlled earlier classpath entry.
        Enumeration<URL> matches = loader.getResources(resource);
        while (matches.hasMoreElements()) {
            URL candidate = matches.nextElement();
            if (!candidate.toExternalForm().equals(unique.toExternalForm())) {
                throw new SecurityException(
                        "duplicate packaged Oreslang alloc resource: " + importPath);
            }
        }
        try (InputStream input = unique.openStream()) {
            byte[] bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (bytes.length > MAX_SOURCE_BYTES) {
                throw new IllegalStateException(
                        "Oreslang alloc module exceeds " + MAX_SOURCE_BYTES
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
                        "packaged alloc/* module contains invalid UTF-8: " + importPath,
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
                    "alloc imports must start with 'alloc/': " + importPath);
        }
        if (importPath.length() > MAX_IMPORT_PATH_CHARS) {
            throw new IllegalArgumentException("Oreslang alloc import path is too long");
        }
        if (importPath.indexOf('\\') >= 0
                || importPath.indexOf('\0') >= 0
                || importPath.startsWith("/")
                || importPath.contains("//")) {
            throw new IllegalArgumentException(
                    "invalid Oreslang alloc import path: " + importPath);
        }

        String raw = importPath.substring(IMPORT_PREFIX.length());
        if (raw.endsWith(".ores")) raw = raw.substring(0, raw.length() - ".ores".length());
        if (raw.isBlank()) {
            throw new IllegalArgumentException("alloc import must name a module");
        }

        String[] segments = raw.split("/");
        if (segments.length > MAX_MODULE_SEGMENTS) {
            throw new IllegalArgumentException("Oreslang alloc import nesting is too deep");
        }
        List<String> normalized = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "alloc import may not traverse directories: " + importPath);
            }
            if (!segment.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException(
                        "invalid alloc path segment '" + segment + "': " + importPath);
            }
            normalized.add(segment);
        }
        return String.join("/", normalized) + ".ores";
    }
}
