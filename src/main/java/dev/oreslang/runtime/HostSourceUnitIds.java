package dev.oreslang.runtime;

import dev.oreslang.config.OresProjectConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
        Path actual = source.toRealPath();

        String inProject = underRoot(source, actual, project.projectRoot(), "project");
        if (inProject != null) return inProject;

        List<Path> searchRoots = project.searchRoots();
        for (int i = 0; i < searchRoots.size(); i++) {
            String name = underRoot(source, actual, searchRoots.get(i), "external/" + i);
            if (name != null) return name;
        }
        throw new IllegalArgumentException(
                "source file is outside the project root and configured import roots; "
                        + "no ambient absolute path can become a macro source identity");
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
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                throw new IllegalArgumentException("source unit ID contains invalid Unicode/control data");
            }
        }
        return id;
    }
}
