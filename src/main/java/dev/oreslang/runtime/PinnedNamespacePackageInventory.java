package dev.oreslang.runtime;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.HexFormat;

/**
 * Local, fail-closed schema-v2 source inventory verifier. This reads only
 * explicitly selected package manifests whose exact SHA-256 and each source
 * file's SHA-256 are pinned by the trusted host/lockfile. This does not obtain
 * signatures, download packages, resolve semver, or trust ambient search roots.
 */
public final class PinnedNamespacePackageInventory {
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_PACKAGES = 64;
    private static final String MANIFEST = ".ores.pkg.toml";
    private PinnedNamespacePackageInventory() { }

    /** Exactly pinned manifest bytes and source bytes, supplied externally. */
    public record PackagePin(
            Path manifest,
            String manifestSha256,
            Map<String, String> sourceSha256) {
        public PackagePin {
            Objects.requireNonNull(manifest, "manifest");
            requireSha(manifestSha256);
            sourceSha256 = Map.copyOf(sourceSha256);
            for (Map.Entry<String, String> entry : sourceSha256.entrySet()) {
                safeRelative(entry.getKey());
                requireSha(entry.getValue());
            }
        }
    }

    public record Inventory(
            Map<String, String> sources,
            Map<String, String> sourceOwners,
            Map<String, Map<String, String>> pinnedAliases) {
        public Inventory {
            sources = Map.copyOf(sources);
            sourceOwners = Map.copyOf(sourceOwners);
            pinnedAliases = Map.copyOf(pinnedAliases);
        }
        public PinnedNamespaceProgramRunner.Result run(
                String entryUnit, IsolatePolicy policy, ExecutionProfile profile) {
            return PinnedNamespaceProgramRunner.run(
                    sources, sourceOwners, pinnedAliases, entryUnit, policy, profile);
        }
    }

    /**
     * The keys of pinnedPackages are immutable package-instance identities
     * chosen by the trusted lockfile; they must match all pinned alias targets.
     * A package path or its manifest name cannot override those identities.
     */
    public static Inventory load(
            Map<String, PackagePin> pinnedPackages,
            Map<String, Map<String, String>> pinnedAliases) throws IOException {
        Objects.requireNonNull(pinnedPackages);
        Objects.requireNonNull(pinnedAliases);
        if (pinnedPackages.isEmpty() || pinnedPackages.size() > MAX_PACKAGES)
            throw new IllegalArgumentException("pinned namespace inventory needs 1..64 packages");
        if (pinnedPackages.size() != new HashSet<>(pinnedPackages.keySet()).size())
            throw new IllegalArgumentException("duplicate pinned package identity");

        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, String> sourceOwners = new LinkedHashMap<>();
        List<Path> admitted = new ArrayList<>();
        SourceAdmissionBudget budget = new SourceAdmissionBudget();
        List<String> instances = new ArrayList<>(pinnedPackages.keySet());
        instances.sort(String::compareTo);
        for (String instance : instances) {
            if (instance == null || instance.isBlank())
                throw new IllegalArgumentException("empty package instance identity");
            PackagePin pin = Objects.requireNonNull(pinnedPackages.get(instance));
            Path manifest = pin.manifest().toAbsolutePath().normalize();
            if (!MANIFEST.equals(manifest.getFileName().toString())
                    || Files.isSymbolicLink(manifest)
                    || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("pinned package must have a regular .ores.pkg.toml");
            Path root = manifest.getParent().toRealPath();
            if (!manifest.toRealPath().getParent().equals(root))
                throw new IllegalArgumentException("manifest parent is not its real package root");

            byte[] manifestBytes;
            try (var input = Files.newInputStream(manifest)) {
                manifestBytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            }
            if (manifestBytes.length > MAX_MANIFEST_BYTES)
                throw new IllegalArgumentException("package manifest exceeds 1 MiB");
            requireDigest(manifestBytes, pin.manifestSha256(), manifest.toString());
            TomlParseResult parsed = Toml.parse(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!parsed.errors().isEmpty())
                throw new IllegalArgumentException("invalid pinned package manifest: " + parsed.errors().getFirst());
            if (!"2".equals(parsed.getString("schema_version")))
                throw new IllegalArgumentException("pinned namespace package requires manifest schema_version 2");
            String name = parsed.getString("project.name");
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_-]*"))
                throw new IllegalArgumentException("pinned package project.name is invalid");
            String declaredRoot = parsed.getString("project.root");
            if (declaredRoot != null && !declaredRoot.equals("."))
                throw new IllegalArgumentException("pinned namespace source root must equal '.'");
            TomlArray declared = parsed.getArray("packages." + name + ".files");
            if (declared == null || declared.size() == 0)
                throw new IllegalArgumentException("pinned package must enumerate its files explicitly");
            if (declared.size() > SourceAdmissionBudget.MAX_UNITS)
                throw new IllegalArgumentException("pinned package file inventory exceeds unit budget");

            Set<String> inventoryNames = new HashSet<>();
            List<String> files = new ArrayList<>();
            for (int i = 0; i < declared.size(); ++i) {
                Object element = declared.get(i);
                if (!(element instanceof String relative))
                    throw new IllegalArgumentException("package file inventory must contain string paths");
                safeRelative(relative);
                if (!inventoryNames.add(relative))
                    throw new IllegalArgumentException("duplicate namespace source path in manifest");
                files.add(relative);
            }
            if (!inventoryNames.equals(pin.sourceSha256().keySet())) {
                throw new IllegalArgumentException("pinned per-file hash inventory must exactly match manifest files");
            }
            files.sort(Comparator.naturalOrder());
            for (String relative : files) {
                Path candidate = root.resolve(relative).normalize();
                if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("package source is missing or not a regular file");
                rejectSymlinkComponents(root, candidate);
                Path real = candidate.toRealPath();
                if (!real.startsWith(root))
                    throw new IllegalArgumentException("pinned source escapes its package root");
                for (Path old : admitted) {
                    if (Files.isSameFile(real, old)) {
                        throw new IllegalArgumentException("multiple source unit identities alias the same physical file");
                    }
                }
                admitted.add(real);
                BoundedSourceReader.ReadResult read = BoundedSourceReader.readWithByteCount(real);
                budget.admit(read.byteCount(), 0);
                requireDigest(read.text().getBytes(StandardCharsets.UTF_8),
                        pin.sourceSha256().get(relative), real.toString());
                String id = real.toString().replace('\\', '/');
                if (sources.putIfAbsent(id, read.text()) != null
                        || sourceOwners.putIfAbsent(id, instance) != null)
                    throw new IllegalArgumentException("duplicate normalized source unit in pinned inventory");
            }
        }

        // Package-alias pins can only target packages in this exact inventory;
        // there is no ambient filesystem or registries fallback.
        for (Map.Entry<String, Map<String, String>> table : pinnedAliases.entrySet()) {
            if (!pinnedPackages.containsKey(table.getKey()))
                throw new IllegalArgumentException("pin table importer not in verified package inventory");
            for (Map.Entry<String, String> alias : table.getValue().entrySet()) {
                if (!alias.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")
                        || !pinnedPackages.containsKey(alias.getValue()))
                    throw new IllegalArgumentException("alias targets unverified package instance");
            }
        }
        return new Inventory(sources, sourceOwners, pinnedAliases);
    }

    private static void rejectSymlinkComponents(Path root, Path candidate) {
        Path next = root;
        for (Path part : root.relativize(candidate)) {
            next = next.resolve(part);
            if (Files.isSymbolicLink(next)) {
                throw new IllegalArgumentException("pinned package sources cannot traverse symlinks");
            }
        }
    }

    private static void safeRelative(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("\\") || raw.contains(":")
                || raw.contains("\0"))
            throw new IllegalArgumentException("invalid pinned package source path");
        Path p = Path.of(raw);
        if (p.isAbsolute() || !p.normalize().equals(p)
                || !raw.endsWith(".ores") || raw.startsWith("/")
                || raw.startsWith(".") || raw.contains("//")) {
            throw new IllegalArgumentException("pinned package source path must be normalized relative .ores");
        }
        for (Path segment : p)
            if (segment.toString().equals(".") || segment.toString().equals(".."))
                throw new IllegalArgumentException("relative package source traverses out of root");
    }

    private static void requireSha(String hex) {
        if (hex == null || !hex.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("source and manifest pins must be lowercase 64-digit SHA-256");
    }

    private static void requireDigest(byte[] data, String expected, String label) {
        requireSha(expected);
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if (!actual.equals(expected))
                throw new IllegalArgumentException("pinned SHA-256 mismatch for " + label);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
