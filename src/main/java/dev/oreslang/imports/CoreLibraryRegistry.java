package dev.oreslang.imports;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Compiler-owned Oreslang standard-library sources.
 *
 * <p>Core libraries are opt-in link units. Merely shipping a library with the
 * toolchain does not make it part of a program: the loader resolves a core
 * source only when the program contains an explicit std/... import.</p>
 */
public final class CoreLibraryRegistry {
    public static final String RX_IMPORT = "std/rx";
    public static final String RX_UNIT_ID = "@ores/stdlib/std/rx.ores";

    private static final Map<String, String> RESOURCES = Map.of(
            RX_IMPORT, "/oreslang/stdlib/std/rx.ores");

    private CoreLibraryRegistry() { }

    public static boolean isCorePath(String importPath) {
        if (importPath == null) return false;
        String normalized = importPath.replace('\\', '/');
        return normalized.equals("std") || normalized.startsWith("std/");
    }

    public static Optional<CoreUnit> resolve(String importPath) {
        if (!isCorePath(importPath)) return Optional.empty();

        String canonical = canonicalImportPath(importPath);
        String resource = RESOURCES.get(canonical);
        if (resource == null) {
            throw new IllegalArgumentException(
                    "unknown Oreslang core library import '" + importPath + "'");
        }

        try (InputStream stream = CoreLibraryRegistry.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Oreslang core library resource is missing from the toolchain: " + resource);
            }
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String unitId = "@ores/stdlib/" + canonical + ".ores";
            String fileName = canonical.substring(canonical.lastIndexOf('/') + 1) + ".ores";
            return Optional.of(new CoreUnit(canonical, unitId, fileName, source));
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "failed to read Oreslang core library resource " + resource, failure);
        }
    }

    private static String canonicalImportPath(String importPath) {
        String normalized = importPath.replace('\\', '/');
        if (normalized.endsWith(".ores")) {
            normalized = normalized.substring(0, normalized.length() - ".ores".length());
        }
        if (!normalized.startsWith("std/")) {
            throw new IllegalArgumentException(
                    "core library imports must use the reserved std/... path: '" + importPath + "'");
        }
        if (normalized.contains("//")) {
            throw new IllegalArgumentException("invalid core library import path '" + importPath + "'");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid core library import path '" + importPath + "'");
            }
        }
        return normalized;
    }

    public record CoreUnit(
            String importPath,
            String unitId,
            String fileName,
            String source) { }
}
