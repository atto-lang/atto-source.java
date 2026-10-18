package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Typed admission core for externally built IPC actor executables.
 *
 * <p>This class intentionally does not parse untrusted JSON or resolve a
 * filesystem path. A strict bounded JSON/schema layer must construct the typed
 * manifest before this core is called. Admission revalidates every
 * security-relevant semantic field, recomputes the exact artifact digest/size,
 * verifies an external Ed25519 trust policy, matches a separately authenticated
 * build attestation and mints an opaque {@link ActorArtifact} capability.</p>
 */
public final class ActorArtifactAdmission {
    public static final String SCHEMA_VERSION = "oreslang.aot-artifact.v1";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GIT_COMMIT = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");
    private static final int MAX_MANIFEST_TEXT_CHARS = 512;
    private static final int MAX_CAPABILITY_NAME_CHARS = 64;

    private ActorArtifactAdmission() { }

    public enum Backend {
        JAVA_GRAALVM("java-graalvm"),
        LLVM("llvm");

        private final String manifestName;
        Backend(String manifestName) { this.manifestName = manifestName; }
        public String manifestName() { return manifestName; }
    }

    public enum Format {
        NATIVE_EXECUTABLE("native-executable"),
        STATIC_LIBRARY("static-library"),
        WASM_MODULE("wasm-module");

        private final String manifestName;
        Format(String manifestName) { this.manifestName = manifestName; }
        public String manifestName() { return manifestName; }
    }

    public record Compiler(
            Backend backend,
            String version,
            String gitCommit) {
        public Compiler {
            Objects.requireNonNull(backend, "backend");
            version = requireText(version, "compiler version");
            gitCommit = requireGitCommit(gitCommit, "compiler git commit");
        }
    }

    public record Artifact(
            String fileSha256,
            long sizeBytes,
            Format format,
            String targetTriple,
            String abi) {
        public Artifact {
            fileSha256 = requireSha256(fileSha256, "artifact file sha256");
            if (sizeBytes <= 0 || sizeBytes > 9_007_199_254_740_991L) {
                throw new IllegalArgumentException("artifact size must be a positive JSON-safe integer");
            }
            Objects.requireNonNull(format, "artifact format");
            targetTriple = requireText(targetTriple, "target triple");
            abi = requireText(abi, "artifact abi");
        }
    }

    public record Policy(
            boolean runtimeMacroExpansion,
            boolean runtimeAnnotationGeneration,
            boolean runtimeGuestCompilation,
            List<String> requiredCapabilities) {
        public Policy {
            requiredCapabilities = List.copyOf(requiredCapabilities);
            if (requiredCapabilities.size() > IsolatePolicy.Capability.values().length) {
                throw new IllegalArgumentException(
                        "artifact capability list exceeds runtime capability vocabulary");
            }
            HashSet<String> unique = new HashSet<>();
            for (String capability : requiredCapabilities) {
                requireText(capability, "required capability");
                if (capability.length() > MAX_CAPABILITY_NAME_CHARS) {
                    throw new IllegalArgumentException("required capability name is too long");
                }
                if (!unique.add(capability)) {
                    throw new IllegalArgumentException(
                            "duplicate required capability '" + capability + "'");
                }
            }
        }
    }

    public record ManifestSignature(
            String algorithm,
            String keyId,
            String valueBase64) {
        public ManifestSignature {
            algorithm = requireText(algorithm, "signature algorithm");
            keyId = requireText(keyId, "signature key id");
            valueBase64 = requireText(valueBase64, "signature value");
        }
    }

    public record Provenance(
            String builderId,
            ManifestSignature signature) {
        public Provenance {
            builderId = requireText(builderId, "builder id");
            Objects.requireNonNull(signature, "signature");
        }
    }

    public record Manifest(
            String schemaVersion,
            Compiler compiler,
            String sourceSha256,
            String expandedSha256,
            String generatedAstSha256,
            Artifact artifact,
            Policy policy,
            Provenance provenance) {
        public Manifest {
            schemaVersion = requireText(schemaVersion, "schema version");
            Objects.requireNonNull(compiler, "compiler");
            sourceSha256 = requireSha256(sourceSha256, "source sha256");
            expandedSha256 = requireSha256(expandedSha256, "expanded sha256");
            generatedAstSha256 = requireSha256(generatedAstSha256, "generated ast sha256");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(provenance, "provenance");
        }
    }

    /**
     * Separately authenticated build evidence supplied by the trusted host.
     * This is deliberately richer than the manifest so IPC handshake-critical
     * actor ABI/state/wire commitments are bound before launch.
     */
    public record TrustedBuildAttestation(
            String actorTypeName,
            Ast.ActorKind actorKind,
            Ast.ActorBoundary actorBoundary,
            Backend backend,
            String compilerVersion,
            String compilerGitCommit,
            String sourceSha256,
            String expandedSha256,
            String generatedAstSha256,
            String artifactSha256,
            long artifactSizeBytes,
            Format artifactFormat,
            String targetTriple,
            String abi,
            String builderId,
            String messageAbiSha256,
            String stateSchemaSha256,
            String wireCodecSha256) {
        public TrustedBuildAttestation {
            actorTypeName = requireText(actorTypeName, "attested actor type");
            Objects.requireNonNull(actorKind, "attested actor kind");
            Objects.requireNonNull(actorBoundary, "attested actor boundary");
            if (actorBoundary != Ast.ActorBoundary.IPC) {
                throw new IllegalArgumentException(
                        "IPC build attestation must bind the IPC actor boundary");
            }
            Objects.requireNonNull(backend, "attested backend");
            compilerVersion = requireText(compilerVersion, "attested compiler version");
            compilerGitCommit = requireGitCommit(compilerGitCommit, "attested compiler git commit");
            sourceSha256 = requireSha256(sourceSha256, "attested source sha256");
            expandedSha256 = requireSha256(expandedSha256, "attested expanded sha256");
            generatedAstSha256 = requireSha256(generatedAstSha256, "attested generated ast sha256");
            artifactSha256 = requireSha256(artifactSha256, "attested artifact sha256");
            if (artifactSizeBytes <= 0) {
                throw new IllegalArgumentException("attested artifact size must be positive");
            }
            Objects.requireNonNull(artifactFormat, "attested artifact format");
            targetTriple = requireText(targetTriple, "attested target triple");
            abi = requireText(abi, "attested abi");
            builderId = requireText(builderId, "attested builder id");
            messageAbiSha256 = requireSha256(messageAbiSha256, "message abi sha256");
            stateSchemaSha256 = requireSha256(stateSchemaSha256, "state schema sha256");
            wireCodecSha256 = requireSha256(wireCodecSha256, "wire codec sha256");
        }
    }

    /**
     * Verifies that build evidence came from the independently trusted
     * attestation channel. Returning normally means authenticated; any failure
     * must throw and admission fails closed.
     */
    @FunctionalInterface
    public interface TrustedBuildAttestationPolicy {
        void verify(TrustedBuildAttestation attestation);
    }

    /** Host trust store; manifest data never supplies its own public key. */
    @FunctionalInterface
    public interface TrustedKeyPolicy {
        PublicKey resolve(String builderId, String keyId);
    }

    public record TargetPolicy(
            Set<Backend> allowedBackends,
            String targetTriple,
            String abi,
            long maxArtifactBytes) {
        public TargetPolicy {
            allowedBackends = Set.copyOf(allowedBackends);
            if (allowedBackends.isEmpty()) {
                throw new IllegalArgumentException("at least one compiler backend must be allowed");
            }
            targetTriple = requireText(targetTriple, "target policy triple");
            abi = requireText(abi, "target policy abi");
            if (maxArtifactBytes <= 0) {
                throw new IllegalArgumentException("max artifact bytes must be positive");
            }
        }
    }

    static <T> ActorArtifact<T> admitIpcActor(
            UUID admittingRuntimeId,
            String actorTypeName,
            Ast.ActorKind actorKind,
            Manifest manifest,
            byte[] exactArtifactBytes,
            TrustedBuildAttestation attestation,
            TrustedBuildAttestationPolicy attestationPolicy,
            TrustedKeyPolicy keyPolicy,
            TargetPolicy targetPolicy,
            IsolatePolicy supervisorPolicy,
            IsolatePolicy workerPolicy) {
        Objects.requireNonNull(admittingRuntimeId, "admitting runtime id");
        actorTypeName = requireText(actorTypeName, "actor type name");
        Objects.requireNonNull(actorKind, "actor kind");
        Objects.requireNonNull(manifest, "manifest");
        exactArtifactBytes = Objects.requireNonNull(exactArtifactBytes, "artifact bytes");
        Objects.requireNonNull(attestation, "attestation");
        Objects.requireNonNull(attestationPolicy, "attestation policy");
        Objects.requireNonNull(keyPolicy, "key policy");
        Objects.requireNonNull(targetPolicy, "target policy");
        Objects.requireNonNull(supervisorPolicy, "supervisor policy");
        Objects.requireNonNull(workerPolicy, "worker policy");
        // Bound the second copy before taking ownership. The caller has
        // already allocated its input, but admission must not amplify an
        // oversized input into another equally large heap object.
        if (exactArtifactBytes.length > targetPolicy.maxArtifactBytes()) {
            throw new SecurityException("artifact exceeds admission size limit");
        }
        // Take ownership before any digest or trust callback. Caller-owned
        // mutable storage must never be hashed and cloned in separate phases.
        byte[] admittedBytes = exactArtifactBytes.clone();

        if (actorKind != Ast.ActorKind.PRIVATE && actorKind != Ast.ActorKind.UNTRUSTED) {
            throw new SecurityException("IPC artifact requires PRIVATE or UNTRUSTED actor kind");
        }
        for (IsolatePolicy.Capability capability : List.of(
                IsolatePolicy.Capability.ACTOR_SPAWN,
                IsolatePolicy.Capability.IPC,
                IsolatePolicy.Capability.CHILD_PROCESS,
                IsolatePolicy.Capability.HOT_CODE_LOAD)) {
            if (!supervisorPolicy.allows(capability)) {
                throw new SecurityException(
                        capability + " capability required to admit IPC actor artifact");
            }
        }

        validateManifestSemantics(manifest, targetPolicy, admittedBytes.length);
        String artifactSha256 = sha256Hex(admittedBytes);
        if (!artifactSha256.equals(manifest.artifact().fileSha256())) {
            throw new SecurityException("artifact bytes sha256 does not match signed manifest");
        }
        if (admittedBytes.length != manifest.artifact().sizeBytes()) {
            throw new SecurityException("artifact byte size does not match signed manifest");
        }

        verifyTrustedBuildAttestation(attestation, attestationPolicy);
        validateAttestation(actorTypeName, actorKind, manifest, attestation);
        Set<IsolatePolicy.Capability> required =
                resolveCapabilities(manifest.policy().requiredCapabilities());
        if (!workerPolicy.capabilities().equals(required)) {
            EnumSet<IsolatePolicy.Capability> missing = required.isEmpty()
                    ? EnumSet.noneOf(IsolatePolicy.Capability.class)
                    : EnumSet.copyOf(required);
            missing.removeAll(workerPolicy.capabilities());
            EnumSet<IsolatePolicy.Capability> undeclared =
                    workerPolicy.capabilities().isEmpty()
                            ? EnumSet.noneOf(IsolatePolicy.Capability.class)
                            : EnumSet.copyOf(workerPolicy.capabilities());
            undeclared.removeAll(required);
            throw new SecurityException(
                    "worker policy must exactly match signed artifact capabilities; "
                            + "missing=" + missing + ", undeclared=" + undeclared);
        }
        if (actorKind == Ast.ActorKind.UNTRUSTED) {
            if (!workerPolicy.adversarial()) {
                throw new SecurityException("untrusted IPC actor requires adversarial worker policy");
            }
            if (!required.isEmpty() || !workerPolicy.capabilities().isEmpty()) {
                throw new SecurityException(
                        "untrusted IPC actor v1 must have zero ambient capabilities");
            }
        }

        verifyManifestSignature(manifest, keyPolicy);

        byte[] canonicalSigned = canonicalManifest(manifest, true);
        String workerPolicySha256 = workerPolicyDigest(workerPolicy);
        return ActorArtifact.mint(
                admittingRuntimeId,
                UUID.randomUUID(),
                actorTypeName,
                actorKind,
                sha256Hex(canonicalSigned),
                artifactSha256,
                attestation.messageAbiSha256(),
                attestation.stateSchemaSha256(),
                attestation.wireCodecSha256(),
                workerPolicySha256,
                manifest.compiler().backend().manifestName(),
                manifest.compiler().version(),
                manifest.compiler().gitCommit(),
                manifest.artifact().targetTriple(),
                manifest.artifact().abi(),
                manifest.provenance().builderId(),
                manifest.provenance().signature().keyId(),
                required,
                admittedBytes);
    }

    static byte[] canonicalUnsignedManifest(Manifest manifest) {
        return canonicalManifest(manifest, false);
    }

    static byte[] canonicalSignedManifest(Manifest manifest) {
        return canonicalManifest(manifest, true);
    }

    private static void validateManifestSemantics(
            Manifest manifest,
            TargetPolicy targetPolicy,
            int actualArtifactBytes) {
        if (!SCHEMA_VERSION.equals(manifest.schemaVersion())) {
            throw new SecurityException("unsupported AOT artifact schema version");
        }
        Policy policy = manifest.policy();
        if (policy.runtimeMacroExpansion()
                || policy.runtimeAnnotationGeneration()
                || policy.runtimeGuestCompilation()) {
            throw new SecurityException(
                    "runtime macro/annotation/guest compilation must be disabled for admitted AOT artifacts");
        }

        Artifact artifact = manifest.artifact();
        if (artifact.format() != Format.NATIVE_EXECUTABLE) {
            throw new SecurityException("IPC actor artifact must be a native executable");
        }
        if (!targetPolicy.allowedBackends().contains(manifest.compiler().backend())) {
            throw new SecurityException(
                    "compiler backend is not allowed by target policy: "
                            + manifest.compiler().backend());
        }
        if (!targetPolicy.targetTriple().equals(artifact.targetTriple())) {
            throw new SecurityException("artifact target triple mismatch");
        }
        if (!targetPolicy.abi().equals(artifact.abi())) {
            throw new SecurityException("artifact ABI mismatch");
        }
        if (artifact.sizeBytes() > targetPolicy.maxArtifactBytes()
                || actualArtifactBytes > targetPolicy.maxArtifactBytes()) {
            throw new SecurityException("artifact exceeds admission size limit");
        }

        ManifestSignature signature = manifest.provenance().signature();
        if (!"ed25519".equals(signature.algorithm())) {
            throw new SecurityException("only Ed25519 artifact signatures are admitted");
        }
        decodeCanonicalEd25519Signature(signature.valueBase64());
    }

    private static void verifyTrustedBuildAttestation(
            TrustedBuildAttestation attestation,
            TrustedBuildAttestationPolicy policy) {
        try {
            policy.verify(attestation);
        } catch (SecurityException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw new SecurityException(
                    "trusted build attestation policy failed closed", failure);
        }
    }

    private static void validateAttestation(
            String actorTypeName,
            Ast.ActorKind actorKind,
            Manifest manifest,
            TrustedBuildAttestation attestation) {
        ArrayList<String> mismatches = new ArrayList<>();
        match(mismatches, "actor type", actorTypeName, attestation.actorTypeName());
        match(mismatches, "actor kind", actorKind, attestation.actorKind());
        match(mismatches, "actor boundary", Ast.ActorBoundary.IPC, attestation.actorBoundary());
        match(mismatches, "compiler backend", manifest.compiler().backend(), attestation.backend());
        match(mismatches, "compiler version", manifest.compiler().version(), attestation.compilerVersion());
        match(mismatches, "compiler git commit", manifest.compiler().gitCommit(), attestation.compilerGitCommit());
        match(mismatches, "source sha256", manifest.sourceSha256(), attestation.sourceSha256());
        match(mismatches, "expanded sha256", manifest.expandedSha256(), attestation.expandedSha256());
        match(mismatches, "generated ast sha256", manifest.generatedAstSha256(), attestation.generatedAstSha256());
        match(mismatches, "artifact sha256", manifest.artifact().fileSha256(), attestation.artifactSha256());
        match(mismatches, "artifact size", manifest.artifact().sizeBytes(), attestation.artifactSizeBytes());
        match(mismatches, "artifact format", manifest.artifact().format(), attestation.artifactFormat());
        match(mismatches, "target triple", manifest.artifact().targetTriple(), attestation.targetTriple());
        match(mismatches, "abi", manifest.artifact().abi(), attestation.abi());
        match(mismatches, "builder id", manifest.provenance().builderId(), attestation.builderId());
        if (!mismatches.isEmpty()) {
            throw new SecurityException(
                    "artifact manifest does not match trusted build attestation: "
                            + String.join(", ", mismatches));
        }
    }

    private static void match(
            List<String> mismatches,
            String label,
            Object actual,
            Object expected) {
        if (!Objects.equals(actual, expected)) mismatches.add(label);
    }

    private static Set<IsolatePolicy.Capability> resolveCapabilities(List<String> names) {
        EnumSet<IsolatePolicy.Capability> result =
                EnumSet.noneOf(IsolatePolicy.Capability.class);
        for (String name : names) {
            IsolatePolicy.Capability capability = switch (name) {
                case "stdin" -> IsolatePolicy.Capability.STDIN;
                case "stdout" -> IsolatePolicy.Capability.STDOUT;
                case "process.info" -> IsolatePolicy.Capability.PROCESS_INFO;
                case "process.exit" -> IsolatePolicy.Capability.PROCESS_EXIT;
                case "gc.control" -> IsolatePolicy.Capability.GC_CONTROL;
                case "actor.share_readonly" -> IsolatePolicy.Capability.ACTOR_SHARE_READONLY;
                case "actor.spawn" -> IsolatePolicy.Capability.ACTOR_SPAWN;
                case "shared.memory" -> IsolatePolicy.Capability.SHARED_MEMORY;
                case "network" -> IsolatePolicy.Capability.NETWORK;
                case "ipc" -> IsolatePolicy.Capability.IPC;
                case "gpu" -> IsolatePolicy.Capability.GPU;
                case "filesystem.read" -> IsolatePolicy.Capability.FILESYSTEM_READ;
                case "filesystem.write" -> IsolatePolicy.Capability.FILESYSTEM_WRITE;
                case "environment" -> IsolatePolicy.Capability.ENVIRONMENT;
                case "hot_code_load" -> IsolatePolicy.Capability.HOT_CODE_LOAD;
                case "java.interop" -> IsolatePolicy.Capability.JAVA_INTEROP;
                case "java.source_interop" -> IsolatePolicy.Capability.JAVA_SOURCE_INTEROP;
                case "ffi" -> IsolatePolicy.Capability.FFI;
                case "native" -> IsolatePolicy.Capability.NATIVE;
                case "reflection" -> IsolatePolicy.Capability.REFLECTION;
                case "child_process" -> IsolatePolicy.Capability.CHILD_PROCESS;
                case "thread.create" -> IsolatePolicy.Capability.THREAD_CREATE;
                case "polyglot" -> IsolatePolicy.Capability.POLYGLOT;
                default -> throw new SecurityException(
                        "unknown artifact capability '" + name + "'");
            };
            if (!result.add(capability)) {
                throw new SecurityException(
                        "duplicate effective artifact capability '" + name + "'");
            }
        }
        return Set.copyOf(result);
    }

    static String workerPolicyDigest(IsolatePolicy policy) {
        Objects.requireNonNull(policy, "worker policy");
        ArrayList<String> capabilities = new ArrayList<>();
        for (IsolatePolicy.Capability capability : policy.capabilities()) {
            capabilities.add(capability.name());
        }
        capabilities.sort(String::compareTo);
        String canonical = "capabilities=" + String.join(",", capabilities)
                + "|maxHeapBytes=" + policy.maxHeapBytes()
                + "|maxMailboxMessages=" + policy.maxMailboxMessages()
                + "|maxWallTimeSeconds=" + policy.maxWallTime().getSeconds()
                + "|maxWallTimeNanos=" + policy.maxWallTime().getNano()
                + "|adversarial=" + policy.adversarial();
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static void verifyManifestSignature(
            Manifest manifest,
            TrustedKeyPolicy keyPolicy) {
        ManifestSignature signature = manifest.provenance().signature();
        PublicKey key = keyPolicy.resolve(
                manifest.provenance().builderId(),
                signature.keyId());
        if (key == null) {
            throw new SecurityException(
                    "artifact signature key is not trusted: " + signature.keyId());
        }
        byte[] signatureBytes = decodeCanonicalEd25519Signature(signature.valueBase64());
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonicalUnsignedManifest(manifest));
            if (!verifier.verify(signatureBytes)) {
                throw new SecurityException("artifact Ed25519 signature verification failed");
            }
        } catch (GeneralSecurityException failure) {
            throw new SecurityException("cannot verify artifact Ed25519 signature", failure);
        }
    }

    private static byte[] decodeCanonicalEd25519Signature(String encoded) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new SecurityException("artifact signature is not valid standard Base64", invalid);
        }
        if (decoded.length != 64
                || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
            throw new SecurityException(
                    "artifact signature must be canonical padded Base64 of exactly 64 bytes");
        }
        return decoded;
    }

    private static byte[] canonicalManifest(Manifest manifest, boolean includeSignature) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{');
        json.append("\"artifact\":{");
        json.append("\"abi\":").append(jsonString(manifest.artifact().abi())).append(',');
        json.append("\"file_sha256\":").append(jsonString(manifest.artifact().fileSha256())).append(',');
        json.append("\"format\":").append(jsonString(manifest.artifact().format().manifestName())).append(',');
        json.append("\"size_bytes\":").append(manifest.artifact().sizeBytes()).append(',');
        json.append("\"target_triple\":").append(jsonString(manifest.artifact().targetTriple()));
        json.append("},");
        json.append("\"compiler\":{");
        json.append("\"backend\":").append(jsonString(manifest.compiler().backend().manifestName())).append(',');
        json.append("\"git_commit\":").append(jsonString(manifest.compiler().gitCommit())).append(',');
        json.append("\"version\":").append(jsonString(manifest.compiler().version()));
        json.append("},");
        json.append("\"expanded_sha256\":").append(jsonString(manifest.expandedSha256())).append(',');
        json.append("\"generated_ast_sha256\":").append(jsonString(manifest.generatedAstSha256())).append(',');
        json.append("\"policy\":{");
        json.append("\"required_capabilities\":[");
        for (int i = 0; i < manifest.policy().requiredCapabilities().size(); i++) {
            if (i != 0) json.append(',');
            json.append(jsonString(manifest.policy().requiredCapabilities().get(i)));
        }
        json.append("],");
        json.append("\"runtime_annotation_generation\":")
                .append(manifest.policy().runtimeAnnotationGeneration()).append(',');
        json.append("\"runtime_guest_compilation\":")
                .append(manifest.policy().runtimeGuestCompilation()).append(',');
        json.append("\"runtime_macro_expansion\":")
                .append(manifest.policy().runtimeMacroExpansion());
        json.append("},");
        json.append("\"provenance\":{");
        json.append("\"builder_id\":").append(jsonString(manifest.provenance().builderId()));
        if (includeSignature) {
            json.append(',');
            json.append("\"signature\":{");
            json.append("\"algorithm\":")
                    .append(jsonString(manifest.provenance().signature().algorithm())).append(',');
            json.append("\"key_id\":")
                    .append(jsonString(manifest.provenance().signature().keyId())).append(',');
            json.append("\"value_base64\":")
                    .append(jsonString(manifest.provenance().signature().valueBase64()));
            json.append('}');
        }
        json.append("},");
        json.append("\"schema_version\":").append(jsonString(manifest.schemaVersion())).append(',');
        json.append("\"source_sha256\":").append(jsonString(manifest.sourceSha256()));
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonString(String value) {
        requireValidUnicode(value, "manifest string");
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (ch <= 0x1f) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static void requireValidUnicode(String value, String label) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException(label + " contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException(label + " contains an unpaired surrogate");
            }
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        if (value.length() > MAX_MANIFEST_TEXT_CHARS) {
            throw new IllegalArgumentException(label + " is too long");
        }
        requireValidUnicode(value, label);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) {
                throw new IllegalArgumentException(label + " contains a control character");
            }
        }
        return value;
    }

    private static String requireSha256(String value, String label) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be 64 lowercase hex characters");
        }
        return value;
    }

    private static String requireGitCommit(String value, String label) {
        if (value == null || !GIT_COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be 40 or 64 lowercase hex characters");
        }
        return value;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
