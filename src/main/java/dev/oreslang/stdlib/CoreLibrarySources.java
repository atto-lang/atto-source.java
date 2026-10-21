package dev.oreslang.stdlib;

import java.io.IOException;
import java.util.Set;

/**
 * Compatibility facade for the transitional std/* packaged source namespace.
 *
 * <p>The canonical registry, path validation, resource uniqueness checks, and
 * UTF-8 admission live in {@link PortableLibrarySources}. Keep this facade so
 * existing stdlib callers/tests do not become a second authority.</p>
 */
public final class CoreLibrarySources {
    private CoreLibrarySources() { }

    public static boolean isCoreImport(String importPath) {
        return PortableLibrarySources.tier(importPath)
                == PortableLibrarySources.Tier.STD;
    }

    public static Set<String> packagedSourcePaths() {
        return PortableLibrarySources.packagedSourcePaths(
                PortableLibrarySources.Tier.STD);
    }

    public static String unitId(String importPath) {
        requireStd(importPath);
        return PortableLibrarySources.unitId(importPath);
    }

    public static String source(String importPath) throws IOException {
        requireStd(importPath);
        return PortableLibrarySources.source(importPath);
    }

    public static String fileName(String importPath) {
        requireStd(importPath);
        return PortableLibrarySources.fileName(importPath);
    }

    private static void requireStd(String importPath) {
        if (!isCoreImport(importPath)) {
            throw new IllegalArgumentException(
                    "core-library imports must start with 'std/': "
                            + importPath);
        }
    }
}
