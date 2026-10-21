package dev.oreslang.stdlib;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads compiler-owned Oreslang library source from reserved namespace tiers.
 *
 * <p>Packaged library source is trusted compiler input, not project/package
 * resolution. A project file, ORESLANG_PATH entry, or ambient classpath
 * resource can never shadow a registered reserved module.</p>
 */
public final class PortableLibrarySources {
    public enum Tier {
        ALLOC("alloc/", "@alloc/", "dev/oreslang/alloc/"),
        STD("std/", "@std/", "dev/oreslang/std/");

        private final String importPrefix;
        private final String unitPrefix;
        private final String resourcePrefix;

        Tier(String importPrefix, String unitPrefix, String resourcePrefix) {
            this.importPrefix = importPrefix;
            this.unitPrefix = unitPrefix;
            this.resourcePrefix = resourcePrefix;
        }

        public String importPrefix() {
            return importPrefix;
        }

        private String unitPrefix() {
            return unitPrefix;
        }

        private String resourcePrefix() {
            return resourcePrefix;
        }
    }

    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_IMPORT_PATH_CHARS = 4096;
    private static final int MAX_MODULE_SEGMENTS = 32;

    private static final Map<Tier, Set<String>> PACKAGED_SOURCES = Map.of(
            Tier.ALLOC, Set.of("json.ores"),
            Tier.STD, Set.of(
                    "core.ores",
                    "testing.ores",
                    "assert.ores",
                    "trace.ores",
                    "http_wire.ores"));

    private PortableLibrarySources() { }

    public static boolean isPackagedImport(String importPath) {
        return tier(importPath) != null;
    }

    public static Tier tier(String importPath) {
        if (importPath == null) return null;
        for (Tier candidate : Tier.values()) {
            if (importPath.startsWith(candidate.importPrefix())) {
                return candidate;
            }
        }
        return null;
    }

    public static Set<String> packagedSourcePaths(Tier tier) {
        return PACKAGED_SOURCES.getOrDefault(
                Objects.requireNonNull(tier, "library tier"),
                Set.of());
    }

    public static String unitId(String importPath) {
        Tier tier = requireTier(importPath);
        return tier.unitPrefix() + canonicalRelativePath(importPath, tier);
    }

    public static String fileName(String importPath) {
        Tier tier = requireTier(importPath);
        String relative = canonicalRelativePath(importPath, tier);
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    public static String source(String importPath) throws IOException {
        Tier tier = requireTier(importPath);
        String relative = canonicalRelativePath(importPath, tier);
        if (!packagedSourcePaths(tier).contains(relative)) {
            throw new IllegalArgumentException(
                    "unknown Oreslang " + tier.importPrefix()
                            + " library module: " + importPath);
        }

        String resource = tier.resourcePrefix() + relative;
        ClassLoader loader = PortableLibrarySources.class.getClassLoader();
        URL unique = PortableLibrarySources.class.getResource("/" + resource);
        if (unique == null) {
            throw new IllegalArgumentException(
                    "packaged Oreslang library resource is missing: " + importPath);
        }

        // Classpath ordering is not authority. If another classpath element
        // contributes the same reserved resource, fail rather than selecting
        // whichever resource happens to enumerate first.
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
                        "Oreslang packaged library module exceeds "
                                + MAX_SOURCE_BYTES + " bytes: " + importPath);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException invalidUtf8) {
                throw new IllegalArgumentException(
                        "packaged " + tier.importPrefix()
                                + " module contains invalid UTF-8: " + importPath,
                        invalidUtf8);
            }
        }
    }

    /**
     * Dependency admission for compiler-owned packaged modules.
     *
     * <p>This is intentionally stricter than the eventual full library DAG.
     * The current executable packaged source only has transitional std/* and
     * alloc/*. alloc/* may depend only on alloc/* until canonical core/* is
     * implemented. std/* retains its existing std/*-only rule so this PR does
     * not silently widen hosted module authority.</p>
     */
    public static boolean mayImport(Tier importer, String importedPath) {
        Tier dependency = tier(importedPath);
        if (dependency == null) return false;
        return switch (Objects.requireNonNull(importer, "importer tier")) {
            case ALLOC -> dependency == Tier.ALLOC;
            case STD -> dependency == Tier.STD;
        };
    }

    private static Tier requireTier(String importPath) {
        Objects.requireNonNull(importPath, "importPath");
        Tier tier = tier(importPath);
        if (tier == null) {
            throw new IllegalArgumentException(
                    "compiler-owned library import must start with a reserved prefix: "
                            + importPath);
        }
        return tier;
    }

    private static String canonicalRelativePath(
            String importPath,
            Tier tier) {
        if (importPath.length() > MAX_IMPORT_PATH_CHARS) {
            throw new IllegalArgumentException(
                    "Oreslang packaged library import path is too long");
        }
        if (importPath.indexOf('\\') >= 0
                || importPath.indexOf('\0') >= 0
                || importPath.startsWith("/")
                || importPath.contains("//")) {
            throw new IllegalArgumentException(
                    "invalid Oreslang packaged library import path: " + importPath);
        }

        String raw = importPath.substring(tier.importPrefix().length());
        if (raw.endsWith(".ores")) {
            raw = raw.substring(0, raw.length() - ".ores".length());
        }
        if (raw.isBlank()) {
            throw new IllegalArgumentException(
                    tier.importPrefix() + " import must name a module");
        }

        String[] segments = raw.split("/");
        if (segments.length > MAX_MODULE_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Oreslang packaged library import nesting is too deep");
        }
        List<String> normalized = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isBlank()
                    || segment.equals(".")
                    || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "packaged library import may not traverse directories: "
                                + importPath);
            }
            if (!segment.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException(
                        "invalid packaged library path segment '"
                                + segment + "': " + importPath);
            }
            normalized.add(segment);
        }
        return String.join("/", normalized) + ".ores";
    }
}
