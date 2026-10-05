package dev.oreslang.stdlib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    private CoreLibrarySources() { }

    public static boolean isCoreImport(String importPath) {
        return importPath != null && importPath.startsWith(IMPORT_PREFIX);
    }

    public static String unitId(String importPath) {
        return UNIT_PREFIX + canonicalRelativePath(importPath);
    }

    public static String source(String importPath) throws IOException {
        String relative = canonicalRelativePath(importPath);
        String resource = RESOURCE_PREFIX + relative;
        ClassLoader loader = CoreLibrarySources.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "unknown Oreslang core-library module: " + importPath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
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
