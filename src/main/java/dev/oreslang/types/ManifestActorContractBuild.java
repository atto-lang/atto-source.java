package dev.oreslang.types;

import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compatibility adapter for the first static declared-actor proof API.
 *
 * All verified-source I/O is delegated to the ONE manifest package builder.
 * Do not maintain a second project traversal, source normalization policy,
 * import policy, or competing provenance implementation.
 *
 * The resulting witness is STATIC and not an executable actor permission.
 */
public final class ManifestActorContractBuild {
    public static final int MAX_FILES = PackageNamespaceBuild.MAX_SOURCE_FILES;
    public static final long MAX_SOURCE_BYTES =
            PackageNamespaceBuild.MAX_PACKAGE_SOURCE_BYTES;
    public static final int MAX_FILE_BYTES = (int) PackageNamespaceBuild.MAX_FILE_BYTES;
    public static final int MAX_MANIFEST_BYTES = PackageNamespaceBuild.MAX_MANIFEST_BYTES;
    public static final int MAX_VISITED_ENTRIES = PackageNamespaceBuild.MAX_VISITED_ENTRIES;
    public static final int MAX_SOURCE_DEPTH = PackageNamespaceBuild.MAX_SOURCE_DEPTH;

    private final PackageNamespaceBuild delegate;

    public record Receipt(
            ActorContractWitness.Result staticWitness,
            Map<String, String> sourceSha256,
            String manifestSha256) {
        public Receipt {
            sourceSha256 = Collections.unmodifiableMap(
                    new LinkedHashMap<>(sourceSha256));
        }
    }

    public ManifestActorContractBuild(OresProjectConfig config) throws IOException {
        delegate = new PackageNamespaceBuild(config);
    }

    public String packageIdentity() {
        return delegate.packageIdentity();
    }

    public synchronized Receipt build() throws IOException {
        PackageNamespaceBuild.ActorAudit audit = delegate.auditDeclaredActors();
        return new Receipt(audit.contractWitness(),
                audit.sourceSha256(), audit.manifestSha256());
    }
}
