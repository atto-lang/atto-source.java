package dev.oreslang.runtime;

import dev.oreslang.config.OresProjectConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted host-side bridge from actual filesystem paths to bounded relative
 * source-unit IDs. Untrusted incremental compiler inputs are deliberately NOT
 * allowed to provide absolute paths: only this loader can derive a logical ID
 * after verifying the file belongs to a configured source root.
 *
 * <p>Within a project, identities are project-relative (portable across host
 * checkout directories). Outside project.root, configured roots are addressed
 * by their deterministic search-root ordinal, never their absolute host path.
 * Imports that escape every declared root fail closed; symlinks in source paths
 * cannot alias another compiler unit or cross root boundaries.</p>
 */
final class HostSourceUnitIds {
    private static final int MAX_ID_CHARS = 4_096;

    private HostSourceUnitIds() { }

    static String of(Path sourceFile, OresProjectConfig project) throws IOException {
        Objects.requireNonNull(sourceFile, "source file");
        Objects.requireNonNull(project, "project config");
        Path source = sourceFile.toAbsolutePath().normalize();
        // Deny out-of-root paths before any filesystem I/O (realpath/stat).
        // Otherwise an untrusted import can probe the presence or permissions
        // of unrelated absolute host files through the error category.
        List<Path> roots = project.searchRoots();
        boolean inRoot = source.startsWith(project.projectRoot().toAbsolutePath().normalize());
        if (!inRoot) {
            for (Path root : roots) {
                if (source.startsWith(root.toAbsolutePath().normalize())) {
                    inRoot = true;
                    break;
                }
            }
        }
        if (!inRoot) {
            throw new IllegalArgumentException(
                    "source file is outside the project root and configured import roots; "
                            + "no ambient absolute path can become a macro source identity");
        }
        Path actual = source.toRealPath();
        // Where the filesystem exposes a hardlink count, reject non-unique
        // host file identities even when the other link is outside the import
        // closure. Other filesystems still require closure-local alias checks.
        rejectKnownHardlinks(actual);

        String inProject = underRoot(source, actual, project.projectRoot(), "project");
        if (inProject != null) return inProject;

        for (int i = 0; i < roots.size(); i++) {
            String name = underRoot(source, actual, roots.get(i), "external/" + i);
            if (name != null) return name;
        }
        throw new IllegalArgumentException(
                "source file is outside the project root and configured import roots; "
                        + "no ambient absolute path can become a macro source identity");
    }

    private static void rejectKnownHardlinks(Path actual) throws IOException {
        final Object links;
        try {
            links = Files.getAttribute(actual, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            // Some providers throw IllegalArgumentException for an unknown
            // attribute view, not UnsupportedOperationException. Only the
            // optional attribute query is suppressed: a positive link count
            // must remain an independently enforced rejection.
            return;
        }
        if (links instanceof Number count && count.longValue() > 1) {
            throw new IllegalArgumentException(
                    "hardlinked source file has more than one filesystem name; "
                            + "macro source identity must be unique");
        }
    }

    /**
     * Each linked build has its own identity registry; never share it across
     * tenants, projects, or builds. Reject different logical names that refer
     * to the same filesystem object, including hardlinks and root aliases.
     */
    static final class Admission {
        private final Map<Path, String> admittedPaths = new LinkedHashMap<>();
        private final Map<String, Path> logicalOwners = new HashMap<>();
        private final Map<Object, Path> fileKeyOwners = new HashMap<>();
        private final List<Path> noFileKey = new ArrayList<>();
        private record Snapshot(Object fileKey, long size,
                                java.nio.file.attribute.FileTime lastModified) { }
        private final Map<Path, Snapshot> snapshots = new HashMap<>();

        String id(Path sourceFile, OresProjectConfig project) throws IOException {
            String logicalId = HostSourceUnitIds.of(sourceFile, project);
            Path path = sourceFile.toAbsolutePath().normalize();
            String known = admittedPaths.get(path);
            if (known != null) {
                if (!known.equals(logicalId)) {
                    throw new IllegalArgumentException(
                            "source path changed its canonical logical identity during linking");
                }
                verifyRead(path, project, known);
                return known;
            }
            Path claimed = logicalOwners.get(logicalId);
            if (claimed != null && !claimed.equals(path)) {
                throw new IllegalArgumentException(
                        "distinct paths claim the same canonical source-unit identity: " + logicalId);
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IllegalArgumentException("source path is not a regular file");
            }
            Object fileKey = attributes.fileKey();
            Path keyOwner = fileKey == null ? null : fileKeyOwners.get(fileKey);
            if (keyOwner != null && !keyOwner.equals(path)) {
                throw new IllegalArgumentException(
                        "hardlink or filesystem file-key alias would give one source two identities");
            }
            // Some providers report no file key. Compare only those paths
            // rather than doing a quadratic scan for every regular source.
            for (Path unknownKeyPath : noFileKey) {
                if (Files.isSameFile(path, unknownKeyPath)) {
                    throw new IllegalArgumentException(
                            "hardlink alias lacks a stable file key; source identity rejected");
                }
            }
            if (fileKey == null) {
                for (Path knownPath : admittedPaths.keySet()) {
                    if (Files.isSameFile(path, knownPath)) {
                        throw new IllegalArgumentException(
                                "hardlink alias lacks a stable file key; source identity rejected");
                    }
                }
                noFileKey.add(path);
            } else {
                fileKeyOwners.put(fileKey, path);
            }
            admittedPaths.put(path, logicalId);
            logicalOwners.put(logicalId, path);
            snapshots.put(path, new Snapshot(fileKey,
                    attributes.size(), attributes.lastModifiedTime()));
            return logicalId;
        }

        /**
         * Verify that the source is still the admitted object after the
         * bounded reader returns. This closes ordinary path-replacement and
         * in-place edit races; fully adversarial concurrent filesystem writers
         * still require handle-based secure-directory reads.
         */
        void verifyRead(Path sourceFile, OresProjectConfig project, String expectedId)
                throws IOException {
            Path path = sourceFile.toAbsolutePath().normalize();
            Snapshot before = snapshots.get(path);
            String registered = admittedPaths.get(path);
            if (before == null || !expectedId.equals(registered)
                    || !expectedId.equals(HostSourceUnitIds.of(path, project))) {
                throw new IllegalArgumentException(
                        "source-unit identity was replaced after admission");
            }
            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile()
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || before.size() != after.size()
                    || !before.lastModified().equals(after.lastModifiedTime())) {
                throw new IllegalArgumentException(
                        "source file changed between admission and bounded read");
            }
        }
    }

    private static String underRoot(Path source, Path actual, Path sourceRoot, String prefix)
            throws IOException {
        Path lexicalRoot = sourceRoot.toAbsolutePath().normalize();
        if (!source.startsWith(lexicalRoot)) return null;
        Path realRoot = lexicalRoot.toRealPath();
        Path relative = lexicalRoot.relativize(source);
        // A symlink anywhere inside the source root would change the identity
        // or could tunnel out of the root; root-directory symlinks themselves
        // are allowed because the relative identity is unchanged.
        if (!realRoot.resolve(relative).normalize().equals(actual)) {
            throw new IllegalArgumentException(
                    "source path traverses a symlink or aliases another source unit");
        }
        if (relative.getNameCount() == 0 || relative.toString().isBlank()) {
            throw new IllegalArgumentException("source unit must name a file under its root");
        }
        String id = prefix + "/" + relative.toString().replace('\\', '/');
        if (id.length() > MAX_ID_CHARS) {
            throw new IllegalArgumentException("source unit ID exceeds canonical length budget");
        }
        for (int i = 0; i < id.length();) {
            char c = id.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= id.length() || !Character.isLowSurrogate(id.charAt(i + 1))) {
                    throw new IllegalArgumentException("source unit ID contains malformed UTF-16");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("source unit ID contains malformed UTF-16");
            }
            int cp = Character.codePointAt(id, i);
            int type = Character.getType(cp);
            if (cp == ':' || Character.isISOControl(cp)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("source unit ID contains invalid Unicode/control data");
            }
            i += Character.charCount(cp);
        }
        return id;
    }
}
