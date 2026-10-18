package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Opaque, host-minted capability for one authenticated external actor artifact.
 *
 * <p>The handle deliberately exposes no filesystem path, command line, library
 * name or mutable artifact buffer. Trusted launch code receives an exact byte
 * snapshot package-privately so a future launcher can execute the admitted
 * object rather than re-resolving a guest-controlled path.</p>
 */
public final class ActorArtifact<T> {
    private final UUID admittingRuntimeId;
    private final UUID codeGenerationId;
    private final String actorTypeName;
    private final Ast.ActorKind actorKind;
    private final Ast.ActorBoundary actorBoundary;
    private final String manifestSha256;
    private final String artifactSha256;
    private final String messageAbiSha256;
    private final String stateSchemaSha256;
    private final String wireCodecSha256;
    private final String workerPolicySha256;
    private final String compilerBackend;
    private final String compilerVersion;
    private final String compilerGitCommit;
    private final String targetTriple;
    private final String abi;
    private final String builderId;
    private final String keyId;
    private final Set<IsolatePolicy.Capability> requiredCapabilities;
    private final byte[] exactArtifactBytes;

    private ActorArtifact(
            UUID admittingRuntimeId,
            UUID codeGenerationId,
            String actorTypeName,
            Ast.ActorKind actorKind,
            Ast.ActorBoundary actorBoundary,
            String manifestSha256,
            String artifactSha256,
            String messageAbiSha256,
            String stateSchemaSha256,
            String wireCodecSha256,
            String workerPolicySha256,
            String compilerBackend,
            String compilerVersion,
            String compilerGitCommit,
            String targetTriple,
            String abi,
            String builderId,
            String keyId,
            Set<IsolatePolicy.Capability> requiredCapabilities,
            byte[] exactArtifactBytes) {
        this.admittingRuntimeId =
                Objects.requireNonNull(admittingRuntimeId, "admittingRuntimeId");
        this.codeGenerationId = Objects.requireNonNull(codeGenerationId, "codeGenerationId");
        this.actorTypeName = requireText(actorTypeName, "actorTypeName");
        this.actorKind = Objects.requireNonNull(actorKind, "actorKind");
        this.actorBoundary = Objects.requireNonNull(actorBoundary, "actorBoundary");
        if (actorBoundary != Ast.ActorBoundary.IPC) {
            throw new IllegalArgumentException("ActorArtifact currently represents IPC actor executables only");
        }
        if (actorKind != Ast.ActorKind.PRIVATE && actorKind != Ast.ActorKind.UNTRUSTED) {
            throw new IllegalArgumentException("IPC actor artifact requires PRIVATE or UNTRUSTED actor kind");
        }
        this.manifestSha256 = requireText(manifestSha256, "manifestSha256");
        this.artifactSha256 = requireText(artifactSha256, "artifactSha256");
        this.messageAbiSha256 = requireText(messageAbiSha256, "messageAbiSha256");
        this.stateSchemaSha256 = requireText(stateSchemaSha256, "stateSchemaSha256");
        this.wireCodecSha256 = requireText(wireCodecSha256, "wireCodecSha256");
        this.workerPolicySha256 = requireText(workerPolicySha256, "workerPolicySha256");
        this.compilerBackend = requireText(compilerBackend, "compilerBackend");
        this.compilerVersion = requireText(compilerVersion, "compilerVersion");
        this.compilerGitCommit = requireText(compilerGitCommit, "compilerGitCommit");
        this.targetTriple = requireText(targetTriple, "targetTriple");
        this.abi = requireText(abi, "abi");
        this.builderId = requireText(builderId, "builderId");
        this.keyId = requireText(keyId, "keyId");
        this.requiredCapabilities = Set.copyOf(requiredCapabilities);
        this.exactArtifactBytes = Objects.requireNonNull(exactArtifactBytes, "exactArtifactBytes").clone();
        if (this.exactArtifactBytes.length == 0) {
            throw new IllegalArgumentException("actor artifact bytes cannot be empty");
        }
    }

    static <T> ActorArtifact<T> mint(
            UUID admittingRuntimeId,
            UUID codeGenerationId,
            String actorTypeName,
            Ast.ActorKind actorKind,
            String manifestSha256,
            String artifactSha256,
            String messageAbiSha256,
            String stateSchemaSha256,
            String wireCodecSha256,
            String workerPolicySha256,
            String compilerBackend,
            String compilerVersion,
            String compilerGitCommit,
            String targetTriple,
            String abi,
            String builderId,
            String keyId,
            Set<IsolatePolicy.Capability> requiredCapabilities,
            byte[] exactArtifactBytes) {
        return new ActorArtifact<>(
                admittingRuntimeId,
                codeGenerationId,
                actorTypeName,
                actorKind,
                Ast.ActorBoundary.IPC,
                manifestSha256,
                artifactSha256,
                messageAbiSha256,
                stateSchemaSha256,
                wireCodecSha256,
                workerPolicySha256,
                compilerBackend,
                compilerVersion,
                compilerGitCommit,
                targetTriple,
                abi,
                builderId,
                keyId,
                requiredCapabilities,
                exactArtifactBytes);
    }

    public UUID codeGenerationId() { return codeGenerationId; }
    public String actorTypeName() { return actorTypeName; }
    public Ast.ActorKind actorKind() { return actorKind; }
    public Ast.ActorBoundary actorBoundary() { return actorBoundary; }
    public String manifestSha256() { return manifestSha256; }
    public String artifactSha256() { return artifactSha256; }
    public String messageAbiSha256() { return messageAbiSha256; }
    public String stateSchemaSha256() { return stateSchemaSha256; }
    public String wireCodecSha256() { return wireCodecSha256; }
    public String workerPolicySha256() { return workerPolicySha256; }
    public String compilerBackend() { return compilerBackend; }
    public String compilerVersion() { return compilerVersion; }
    public String compilerGitCommit() { return compilerGitCommit; }
    public String targetTriple() { return targetTriple; }
    public String abi() { return abi; }
    public String builderId() { return builderId; }
    public String keyId() { return keyId; }
    public Set<IsolatePolicy.Capability> requiredCapabilities() {
        return requiredCapabilities;
    }
    public int artifactSizeBytes() { return exactArtifactBytes.length; }

    /**
     * Trusted runtime/launcher bridge only. Never surface these bytes to guest
     * actor code or use them to reconstruct a guest-selected path.
     */
    byte[] copyExactArtifactBytesForTrustedLauncher() {
        return exactArtifactBytes.clone();
    }

    boolean admittedForRuntime(UUID runtimeId) {
        return admittingRuntimeId.equals(runtimeId);
    }

    @Override
    public String toString() {
        return "ActorArtifact[type=" + actorTypeName
                + ", kind=" + actorKind
                + ", boundary=" + actorBoundary
                + ", artifact=" + artifactSha256
                + ", generation=" + codeGenerationId + "]";
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return value;
    }
}
