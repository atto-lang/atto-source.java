package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class NativeAttoEnvStdlibTest {
    @TempDir Path temp;

    @Test
    void envModuleUsesClosedCanonicalAttoRegistry() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("env.atto"));
        assertEquals("@std/env.atto", CoreLibrarySources.unitId("std/env"));
        assertEquals(CoreLibrarySources.unitId("std/env"),
                CoreLibrarySources.unitId("ores.std/env.atto"));
        assertEquals("env.atto", CoreLibrarySources.fileName("std/env"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/env.ores"));
        String source = CoreLibrarySources.source("std/env");
        var parsed = MixedSourceUnit.parse(CoreLibrarySources.unitId("std/env"),
                CoreLibrarySources.fileName("std/env"), source);
        assertTrue(parsed.hasOresSource());
        assertFalse(parsed.hasJavaSource());
    }

    @Test
    void linkedGuestExecutesStdEnvFallbackAndMissingSemantics() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT);
        var linked = LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/env.atto"),
                policy, RuntimePermissions.denyAll()
                        .allowAll(RuntimePermissions.Permission.ENV),
                PermissionCheckMode.RUNTIME, ExecutionProfile.serverJit(),
                Set.of(), java.util.Map.of(), out, err);
        assertTrue(linked.units().containsKey("@std/env.atto"),
                "source library must compile and link as a guest module");
        assertEquals(String.join("\n", "false", "true", "fallback", "true",
                        "true", "true", "true", "false", "true", "true", "true", "true"),
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void linkedGuestUsesOnlyExplicitContextEnvironment() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // The test deliberately supplies no PATH. A JVM-global getenv(PATH)
        // implementation would leak it into the guest despite the context.
        assertNotNull(System.getenv("PATH"), "isolation test needs host PATH");
        var guestEnv = java.util.Map.of(
                "ATTO_ENV_VALUE", "42",
                "ATTO_ENV_EMPTY", "",
                "ATTO_ENV_FLAG", "true");
        var policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT);
        var linked = LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/env-injected.atto"),
                policy, RuntimePermissions.denyAll()
                        .allowAll(RuntimePermissions.Permission.ENV),
                PermissionCheckMode.RUNTIME, ExecutionProfile.serverJit(),
                Set.of(), guestEnv, out, err);
        assertTrue(linked.units().containsKey("@std/env.atto"));
        assertEquals(String.join("\n", "true", "42", "true",
                        "true", "true", "fallback", "true", "true"),
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void independentlyCreatedGuestContextsDoNotShareEnvironment() throws Exception {
        var entry = Path.of("src/test/resources/stdlib-parity/env-context-isolation.atto");
        var policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT);
        var permissions = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV);
        for (String identity : new String[] {"actor-a", "actor-b", "actor-a"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            var built = LinkedProgramRunner.run(entry, policy, permissions,
                    PermissionCheckMode.RUNTIME, ExecutionProfile.serverJit(),
                    Set.of(), java.util.Map.of("ATTO_CONTEXT_KEY", identity),
                    out, err);
            assertTrue(built.units().containsKey("@std/env.atto"));
            assertEquals(identity + "\nfalse",
                    out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                    () -> err.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void snapshotDoesNotInspectDeniedHostValues() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT);
        RuntimePermissions permission = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV)
                .withDenied(RuntimePermissions.Permission.ENV, "PRIVATE_KEY");
        java.util.Map<String, String> values = new java.util.AbstractMap<>() {
            @Override public Set<String> keySet() {
                return Set.of("PUBLIC_KEY", "PRIVATE_KEY");
            }
            @Override public String get(Object name) {
                if ("PRIVATE_KEY".equals(name)) {
                    throw new AssertionError("denied host value was inspected");
                }
                return "allowed";
            }
            @Override public Set<Entry<String, String>> entrySet() {
                throw new AssertionError("must enumerate keys, never values");
            }
        };
        assertEquals(java.util.Map.of("PUBLIC_KEY", "allowed"),
                LinkedProgramRunner.admittedGuestEnvironment(policy, permission, values));
        assertThrows(UnsupportedOperationException.class,
                () -> LinkedProgramRunner.admittedGuestEnvironment(
                        policy, permission, values).put("NEW", "new"));
    }

    @Test
    void missingCoarseEnvCapabilitySkipsEvenHostKeyEnumeration() {
        java.util.Map<String, String> values = new java.util.AbstractMap<>() {
            @Override public Set<String> keySet() {
                throw new AssertionError("denied coarse capability enumerated keys");
            }
            @Override public Set<Entry<String, String>> entrySet() {
                throw new AssertionError("denied coarse capability enumerated values");
            }
        };
        assertTrue(LinkedProgramRunner.admittedGuestEnvironment(
                IsolatePolicy.developer(),
                RuntimePermissions.denyAll().allowAll(RuntimePermissions.Permission.ENV),
                values).isEmpty());
    }

    @Test
    void snapshotBoundsAuthorizedValuesAndNeverPrintsContents() {
        var policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT);
        var permission = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV);
        for (String invalid : new String[] {
                "MY_SECRET_" + "x".repeat(16384),
                "NUL" + String.valueOf((char) 0),
                String.valueOf((char) 0xD800)
        }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.admittedGuestEnvironment(
                            policy, permission, java.util.Map.of("ATTO_ENV_INVALID", invalid)));
            assertFalse(ex.getMessage().contains("MY_SECRET_"));
            assertFalse(ex.getMessage().contains("NUL"));
        }
    }

    @Test
    void contextSnapshotEnforcesAggregateAndKeyCountBudgets() {
        var policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.ENVIRONMENT);
        var permissions = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV);

        java.util.Map<String, String> overBytes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 65; ++i)
            overBytes.put("ATTO_ENV_VALUE_" + i, "x".repeat(16384));
        IllegalArgumentException bytes = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.admittedGuestEnvironment(
                        policy, permissions, overBytes));
        assertEquals("environment snapshot byte budget exceeded", bytes.getMessage());

        java.util.Map<String, String> overKeys = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 513; ++i)
            overKeys.put("ATTO_ENV_KEY_" + i, "");
        IllegalArgumentException keys = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.admittedGuestEnvironment(
                        policy, permissions, overKeys));
        assertEquals("environment snapshot key limit exceeded", keys.getMessage());
    }

    @Test
    void projectCannotShadowCompilerOwnedStdEnv() throws Exception {
        Path localStd = Files.createDirectories(temp.resolve("std"));
        Files.writeString(localStd.resolve("env.atto"), """
                define module std_env as
                  pub fnc parse_bool(String value): Result<bool, String> {
                    return Err("shadowed");
                  }
                end
                """);
        Path entry = temp.resolve("entry.atto");
        Files.writeString(entry, """
                import module std_env from "std/env";
                pub routine main(): void {
                  stdio.println(std_env.parse_bool("true").unwrap());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry,
                IsolatePolicy.developer().withCapabilities(
                        IsolatePolicy.Capability.ENVIRONMENT),
                ExecutionProfile.serverJit(), out, err);
        assertTrue(linked.units().containsKey("@std/env.atto"));
        assertEquals("true", out.toString(StandardCharsets.UTF_8).trim(),
                () -> err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void scopedDenyPrecedesHostEnvironmentLookup() throws Exception {
        Path entry = temp.resolve("deny.atto");
        Files.writeString(entry, """
                import module std_env from "std/env";
                pub routine main(): void {
                  std_env.get_or("PATH", "fallback");
                  return;
                }
                """);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(entry,
                        IsolatePolicy.developer().withCapabilities(
                                IsolatePolicy.Capability.ENVIRONMENT),
                        RuntimePermissions.denyAll(),
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(), System.getenv(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(deepMessage(error).contains("permission denied"), deepMessage(error));
    }


    @Test
    void keyScopedDenyOverridesBroadAllowAndFallback() throws Exception {
        Path entry = temp.resolve("deny-scoped.atto");
        Files.writeString(entry, """
                import module std_env from "std/env";
                pub routine main(): void {
                  std_env.get_or("ATTO_ENV_SECRET_KEY", "fallback");
                  return;
                }
                """);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV)
                .withDenied(RuntimePermissions.Permission.ENV, "ATTO_ENV_SECRET_KEY");
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(entry,
                        IsolatePolicy.developer().withCapabilities(
                                IsolatePolicy.Capability.ENVIRONMENT),
                        permissions, PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(), System.getenv(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(deepMessage(error).contains("permission denied"), deepMessage(error));
    }

    @Test
    void scopedGrantCannotPromoteMissingIsolateEnvCapability() throws Exception {
        Path entry = temp.resolve("coarse-deny.atto");
        Files.writeString(entry, """
                import module std_env from "std/env";
                pub routine main(): void {
                  std_env.get("ATTO_COARSE_DENY_KEY");
                  return;
                }
                """);
        RuntimePermissions explicit = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV);
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(entry,
                        IsolatePolicy.developer(),
                        explicit, PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(), System.getenv(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        String detail = deepMessage(failure);
        assertTrue(detail.contains("exceeds isolate capability")
                        || detail.contains("permission denied")
                        || detail.contains("environment"),
                detail);
    }

    @Test
    void invalidKeyIsNotRecastAsAbsence() throws Exception {
        Path entry = temp.resolve("bad-name.atto");
        Files.writeString(entry, """
                import module std_env from "std/env";
                pub routine main(): void {
                  std_env.get_or("", "fallback");
                  return;
                }
                """);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> LinkedProgramRunner.run(entry,
                        IsolatePolicy.developer().withCapabilities(
                                IsolatePolicy.Capability.ENVIRONMENT),
                        ExecutionProfile.serverJit(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(deepMessage(error).contains("environment variable name is invalid"),
                deepMessage(error));
    }

    @Test
    void portableKeyGrammarIsBoundedAndFailClosed() {
        for (String good : new String[] {"HOME", "_", "ATTO_9", "A".repeat(128)}) {
            assertDoesNotThrow(() -> NativeIo.validateEnvName(good), good);
        }
        for (String bad : new String[] {
                "", "9BAD", "A-B", "A=B", "A B", "A\\nB",
                "A".repeat(129), "é", String.valueOf((char) 0xD800)
        }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> NativeIo.validateEnvName(bad));
            assertEquals("environment variable name is invalid", ex.getMessage());
        }
    }

    @Test
    void hostEnvironmentValuesEnforceUtf8ByteQuotaWithoutEchoingSecrets() {
        assertDoesNotThrow(() -> NativeIo.validateEnvValue(null));
        assertDoesNotThrow(() -> NativeIo.validateEnvValue(""));
        assertDoesNotThrow(() -> NativeIo.validateEnvValue("a".repeat(16384)));
        assertDoesNotThrow(() -> NativeIo.validateEnvValue("é".repeat(8192)));
        assertDoesNotThrow(() -> NativeIo.validateEnvValue("🎯".repeat(4096)));
        for (String bad : new String[] {
                "MY_SECRET_" + "s".repeat(16385),
                "é".repeat(8193),
                "🎯".repeat(4097),
                String.valueOf((char) 0xD800),
                String.valueOf((char) 0xDC00),
                String.valueOf((char) 0)
        }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> NativeIo.validateEnvValue(bad));
            assertTrue(ex.getMessage().startsWith("environment variable value"));
            assertFalse(ex.getMessage().contains("MY_SECRET_"));
        }
    }

    private static String deepMessage(Throwable failure) {
        StringBuilder message = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) message.append(current.getMessage()).append(" ");
        }
        return message.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
