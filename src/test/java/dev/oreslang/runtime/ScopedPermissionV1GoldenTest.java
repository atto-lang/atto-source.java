package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Policy-layer evaluation of the canonical 52-case scoped-permissions v1
 * corpus, pinned in contracts/v1/scoped-permissions-v1.json.
 *
 * These checks exercise production RuntimePermissions/IsolatePolicy Java code,
 * NOT guest host-I/O syscall enforcement, real network activity, or other VMs.
 * The vectors below were generated from the exact reviewed corpus Git blob;
 * update deliberately with the pinned SHA256 and independent review.
 */
final class ScopedPermissionV1GoldenTest {
    private static final String CORPUS_SHA256 =
            "de3594482d10623241a94cf531fd9e4fe71c49769dd68b2098f4ed5363870ac5";

    private record AuthVector(String id, RuntimePermissions.Permission permission,
                              String resource, boolean coarse,
                              String[] allow, String[] deny, boolean expected) { }
    private record DeserializeVector(String id, String[] argv, boolean expected) { }

    private static final AuthVector[] AUTHORIZATION_CASES = {
            new AuthVector("default-read-denied", RuntimePermissions.Permission.READ, "/workspace/input.txt", false, new String[] {}, new String[] {}, false),
            new AuthVector("default-write-denied", RuntimePermissions.Permission.WRITE, "/workspace/out.txt", false, new String[] {}, new String[] {}, false),
            new AuthVector("default-net-denied", RuntimePermissions.Permission.NET, "api.example.com:443", false, new String[] {}, new String[] {}, false),
            new AuthVector("default-env-denied", RuntimePermissions.Permission.ENV, "API_KEY", false, new String[] {}, new String[] {}, false),
            new AuthVector("default-run-denied", RuntimePermissions.Permission.RUN, "git", false, new String[] {}, new String[] {}, false),
            new AuthVector("default-sys-denied", RuntimePermissions.Permission.SYS, "os", false, new String[] {}, new String[] {}, false),
            new AuthVector("default-ffi-denied", RuntimePermissions.Permission.FFI, "/workspace/libplugin.so", false, new String[] {}, new String[] {}, false),
            new AuthVector("default-import-denied", RuntimePermissions.Permission.IMPORT, "/workspace/import.ores", false, new String[] {}, new String[] {}, false),
            new AuthVector("coarse-ceiling-net", RuntimePermissions.Permission.NET, "api.example.com:443", false, new String[] {"*"}, new String[] {}, false),
            new AuthVector("coarse-ceiling-env", RuntimePermissions.Permission.ENV, "API_KEY", false, new String[] {"*"}, new String[] {}, false),
            new AuthVector("explicit-net-grant", RuntimePermissions.Permission.NET, "api.example.com:443", true, new String[] {"api.example.com:443"}, new String[] {}, true),
            new AuthVector("explicit-env-grant", RuntimePermissions.Permission.ENV, "API_KEY", true, new String[] {"API_KEY"}, new String[] {}, true),
            new AuthVector("explicit-run-grant", RuntimePermissions.Permission.RUN, "git", true, new String[] {"git"}, new String[] {}, true),
            new AuthVector("deny-wins-net", RuntimePermissions.Permission.NET, "api.example.com:443", true, new String[] {"*"}, new String[] {"api.example.com:443"}, false),
            new AuthVector("deny-wins-env", RuntimePermissions.Permission.ENV, "API_KEY", true, new String[] {"*"}, new String[] {"API_KEY"}, false),
            new AuthVector("deny-all-wins", RuntimePermissions.Permission.NET, "other.example.org:443", true, new String[] {"*"}, new String[] {"*"}, false),
            new AuthVector("endpoint-port-separation", RuntimePermissions.Permission.NET, "api.example.com:444", true, new String[] {"api.example.com:443"}, new String[] {}, false),
            new AuthVector("endpoint-upper-normalized", RuntimePermissions.Permission.NET, "API.EXAMPLE.COM.:0443", true, new String[] {"api.example.com:443"}, new String[] {}, true),
            new AuthVector("endpoint-deny-fqdn", RuntimePermissions.Permission.NET, "API.EXAMPLE.COM.:443", true, new String[] {"*"}, new String[] {"api.example.com:443"}, false),
            new AuthVector("endpoint-subdomain-yes", RuntimePermissions.Permission.NET, "a.example.com:443", true, new String[] {"*.example.com:443"}, new String[] {}, true),
            new AuthVector("endpoint-subdomain-no-apex", RuntimePermissions.Permission.NET, "example.com:443", true, new String[] {"*.example.com:443"}, new String[] {}, false),
            new AuthVector("endpoint-subdomain-no-suffix", RuntimePermissions.Permission.NET, "badexample.com:443", true, new String[] {"*.example.com:443"}, new String[] {}, false),
            new AuthVector("endpoint-deny-portless", RuntimePermissions.Permission.NET, "api.example.com", true, new String[] {"*"}, new String[] {"api.example.com:443"}, false),
            new AuthVector("endpoint-deny-wildcard-port", RuntimePermissions.Permission.NET, "a.example.com:443", true, new String[] {"*"}, new String[] {"*.example.com:443"}, false),
            new AuthVector("endpoint-ipv6-short", RuntimePermissions.Permission.NET, "[::1]:443", true, new String[] {"[0:0:0:0:0:0:0:1]:443"}, new String[] {}, true),
            new AuthVector("endpoint-ipv6-deny", RuntimePermissions.Permission.NET, "[0:0:0:0:0:0:0:1]:443", true, new String[] {"*"}, new String[] {"[::1]:443"}, false),
            new AuthVector("endpoint-ipv4-alias", RuntimePermissions.Permission.NET, "127.1:443", true, new String[] {"*"}, new String[] {"127.0.0.1"}, false),
            new AuthVector("endpoint-ipv4-hex-alias", RuntimePermissions.Permission.NET, "0x7f.0.0.1:443", true, new String[] {"*"}, new String[] {"127.0.0.1"}, false),
            new AuthVector("endpoint-invalid-port", RuntimePermissions.Permission.NET, "api.example.com:0", true, new String[] {"*"}, new String[] {}, false),
            new AuthVector("endpoint-invalid-credentials", RuntimePermissions.Permission.NET, "alice@api.example.com:443", true, new String[] {"*"}, new String[] {}, false),
            new AuthVector("env-whitespace-exact", RuntimePermissions.Permission.ENV, "API_KEY ", true, new String[] {"API_KEY"}, new String[] {}, false),
            new AuthVector("env-case-exact", RuntimePermissions.Permission.ENV, "api_key", true, new String[] {"API_KEY"}, new String[] {}, false),
            new AuthVector("run-prefix", RuntimePermissions.Permission.RUN, "git-status", true, new String[] {"git*"}, new String[] {}, true),
            new AuthVector("run-prefix-no-match", RuntimePermissions.Permission.RUN, "curl", true, new String[] {"git*"}, new String[] {}, false),
            new AuthVector("path-child-allowed", RuntimePermissions.Permission.READ, "/workspace/data/file.txt", true, new String[] {"/workspace/data"}, new String[] {}, true),
            new AuthVector("path-prefix-no-match", RuntimePermissions.Permission.READ, "/workspace/database/file.txt", true, new String[] {"/workspace/data"}, new String[] {}, false),
            new AuthVector("path-whitespace-exact", RuntimePermissions.Permission.READ, "/workspace/data ", true, new String[] {"/workspace/data"}, new String[] {}, false),
            new AuthVector("env-padded-grant-literal", RuntimePermissions.Permission.ENV, "API_KEY ", true, new String[] {"API_KEY "}, new String[] {}, true),
            new AuthVector("env-padded-grant-not-trimmed", RuntimePermissions.Permission.ENV, "API_KEY", true, new String[] {"API_KEY "}, new String[] {}, false),
            new AuthVector("env-padded-deny-literal", RuntimePermissions.Permission.ENV, "API_KEY ", true, new String[] {"*"}, new String[] {"API_KEY "}, false),
            new AuthVector("env-padded-deny-not-trimmed", RuntimePermissions.Permission.ENV, "API_KEY", true, new String[] {"*"}, new String[] {"API_KEY "}, true),
            new AuthVector("run-padded-grant-not-trimmed", RuntimePermissions.Permission.RUN, "git", true, new String[] {"git "}, new String[] {}, false),
            new AuthVector("run-padded-deny-not-trimmed", RuntimePermissions.Permission.RUN, "git", true, new String[] {"*"}, new String[] {"git "}, true),
            new AuthVector("sys-padded-grant-not-trimmed", RuntimePermissions.Permission.SYS, "os", true, new String[] {"os "}, new String[] {}, false),
            new AuthVector("sys-padded-deny-not-trimmed", RuntimePermissions.Permission.SYS, "os", true, new String[] {"*"}, new String[] {"os "}, true)
    };
    private static final DeserializeVector[] DESERIALIZATION_CASES = {
            new DeserializeVector("serialized-empty-policy", new String[] {"--ores-capabilities=", "--ores-permission-explicit=true"}, true),
            new DeserializeVector("serialized-duplicate-capabilities", new String[] {"--ores-capabilities=NETWORK", "--ores-capabilities=NETWORK", "--ores-permission-explicit=true"}, false),
            new DeserializeVector("serialized-missing-capability-header", new String[] {"--ores-permission-explicit=true"}, false),
            new DeserializeVector("serialized-missing-marker", new String[] {"--ores-capabilities=NETWORK", "--ores-permission-net-allow=*"}, false),
            new DeserializeVector("serialized-duplicate-marker", new String[] {"--ores-capabilities=NETWORK", "--ores-permission-explicit=true", "--ores-permission-explicit=true"}, false),
            new DeserializeVector("serialized-no-escalation", new String[] {"--ores-capabilities=", "--ores-permission-explicit=true", "--ores-permission-net-allow=*"}, false),
            new DeserializeVector("serialized-malformed-net", new String[] {"--ores-capabilities=NETWORK", "--ores-permission-explicit=true", "--ores-permission-net-allow=127.1:443"}, false)
    };

    @Test
    void reviewedCorpusSha256MustMatchGeneratedGoldenCases() throws Exception {
        byte[] raw = Files.readAllBytes(Path.of("contracts/v1/scoped-permissions-v1.json"));
        String observed = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw));
        assertEquals(CORPUS_SHA256, observed,
                "reviewed corpus changed: regenerate cases only after security review");
        assertEquals(45, AUTHORIZATION_CASES.length);
        assertEquals(7, DESERIALIZATION_CASES.length);
    }

    @Test
    void all45ScopedAuthorizationVectorsExerciseRealJavaPolicy() {
        for (AuthVector vector : AUTHORIZATION_CASES) {
            RuntimePermissions.Builder builder = new RuntimePermissions.Builder();
            for (String scope : vector.allow()) {
                builder.allow(vector.permission(), scope);
            }
            for (String scope : vector.deny()) {
                builder.deny(vector.permission(), scope);
            }
            RuntimePermissions permissions = builder.build();
            // The coarse IsolatePolicy capability is an independent ceiling;
            // RuntimePermissions.allows alone cannot grant it.
            boolean granted = vector.coarse() &&
                    permissions.allows(vector.permission(), vector.resource());
            assertEquals(vector.expected(), granted, vector.id());
        }
    }

    @Test
    void all7SerializedCapabilityAndPermissionVectorsExerciseRealJavaParsers() {
        for (DeserializeVector vector : DESERIALIZATION_CASES) {
            boolean accepted;
            try {
                IsolatePolicy coarse = IsolatePolicy.fromApplicationArguments(vector.argv());
                RuntimePermissions.fromApplicationArguments(vector.argv(), coarse);
                accepted = true;
            } catch (IllegalArgumentException | SecurityException rejected) {
                accepted = false;
            }
            assertEquals(vector.expected(), accepted, vector.id());
        }
    }
}
