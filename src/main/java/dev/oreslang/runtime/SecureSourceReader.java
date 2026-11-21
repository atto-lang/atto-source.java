package dev.oreslang.runtime;

import dev.oreslang.config.OresProjectConfig;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded source admission through opened file handles, not path-based
 * InputStreams. SecureDirectoryStream pins intermediate directories where
 * supported and rejects symlinks at every component. The NOFOLLOW_LINKS
 * descriptor fallback remains necessary on filesystem providers without
 * SecureDirectoryStream: it is intentionally NOT an adversarial guarantee for
 * intermediate-path replacement races on those providers.
 *
 * The second independent handle read is compared byte-for-byte (via immutable,
 * strictly decoded text + source byte count) to catch ordinary swap-and-restore
 * races. An adversary able to synchronize both descriptor opens still requires
 * OS-specific opened-handle fstat/provenance or a protected, immutable source
 * snapshot. This class makes no claim to fully solve that threat model.
 */
final class SecureSourceReader {
    private static final Set<OpenOption> READ_NOFOLLOW =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private SecureSourceReader() { }

    static BoundedSourceReader.ReadResult read(
            Path sourceFile, OresProjectConfig project) throws IOException {
        return read(sourceFile, project, false);
    }

    /** Adversarial workloads must fail closed if secure directory handles are absent. */
    static BoundedSourceReader.ReadResult read(
            Path sourceFile, OresProjectConfig project, boolean requireSecure)
            throws IOException {
        Objects.requireNonNull(sourceFile, "source file");
        Objects.requireNonNull(project, "project config");
        Path source = sourceFile.toAbsolutePath().normalize();

        String admitted = HostSourceUnitIds.of(source, project);
        Path root = rootFor(source, project);
        BoundedSourceReader.ReadResult first = readOnce(root, source, requireSecure);
        BoundedSourceReader.ReadResult second = readOnce(root, source, requireSecure);
        if (!first.equals(second)) {
            throw new IllegalArgumentException(
                    "source changed between independently opened source descriptors");
        }
        if (!admitted.equals(HostSourceUnitIds.of(source, project))) {
            throw new IllegalArgumentException("source unit identity changed during secure read");
        }
        return first;
    }

    private static Path rootFor(Path source, OresProjectConfig project) {
        Path root = project.projectRoot().toAbsolutePath().normalize();
        if (source.startsWith(root)) return root;
        for (Path candidate : project.searchRoots()) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (source.startsWith(normalized)) return normalized;
        }
        throw new IllegalArgumentException(
                "source file is not under an approved import root");
    }

    private static BoundedSourceReader.ReadResult readOnce(
            Path root, Path source, boolean requireSecure) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path relative = normalizedRoot.relativize(source);
        if (relative.getNameCount() == 0 || relative.toString().isBlank()) {
            throw new IllegalArgumentException("source path must name a file");
        }
        // A canonical root directory may itself be a symlink (e.g. a
        // configured checkout root). Descendants are always opened relative
        // to the real root handle and without following component symlinks.
        try (DirectoryStream<Path> directory =
                     Files.newDirectoryStream(normalizedRoot.toRealPath())) {
            if (directory instanceof SecureDirectoryStream<Path> secure) {
                return readSecure(secure, relative);
            }
        }

        if (requireSecure) {
            throw new SecurityException(
                    "adversarial source admission requires secure directory handles; "
                            + "provider cannot pin intermediate path components");
        }
        // Trusted-host provider fallback: the final component is still opened
        // with NOFOLLOW_LINKS and identity is checked before/after by read().
        // Do not claim secure intermediate-directory pinning here.
        try (SeekableByteChannel channel = Files.newByteChannel(source, READ_NOFOLLOW)) {
            return BoundedSourceReader.readWithByteCount(channel);
        }
    }

    /** Close every nested directory, preserving suppressed close failures. */
    private static final class OpenDirectories implements AutoCloseable {
        private final List<SecureDirectoryStream<Path>> directories = new ArrayList<>();

        void track(SecureDirectoryStream<Path> directory) { directories.add(directory); }

        @Override public void close() throws IOException {
            IOException first = null;
            for (int i = directories.size() - 1; i >= 0; i--) {
                try {
                    directories.get(i).close();
                } catch (IOException failure) {
                    if (first == null) first = failure;
                    else first.addSuppressed(failure);
                }
            }
            if (first != null) throw first;
        }
    }

    private static BoundedSourceReader.ReadResult readSecure(
            SecureDirectoryStream<Path> root,
            Path relative) throws IOException {
        try (OpenDirectories opened = new OpenDirectories()) {
            SecureDirectoryStream<Path> directory = root;
            int last = relative.getNameCount() - 1;
            for (int i = 0; i < last; i++) {
                SecureDirectoryStream<Path> child = directory.newDirectoryStream(
                        relative.getName(i), LinkOption.NOFOLLOW_LINKS);
                opened.track(child);
                directory = child;
            }
            try (SeekableByteChannel channel = directory.newByteChannel(
                    relative.getName(last), READ_NOFOLLOW)) {
                return BoundedSourceReader.readWithByteCount(channel);
            }
        }
    }
}
