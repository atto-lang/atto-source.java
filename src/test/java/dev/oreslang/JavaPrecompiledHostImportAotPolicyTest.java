package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Precompiled JDK host class imported by AttoLang. Running in AOT execution
 * profile on a JVM tests the no-guest-JIT policy, NOT Graal Native Image
 * machine code production or per-actor zero-GC behavior.
 */
final class JavaPrecompiledHostImportAotPolicyTest {
    @TempDir Path temp;

    private static final String SOURCE = """
            import class Long as JLong from "java:java.lang.Long";

            pub fnc main(): void {
                stdio.println(JLong.sum(40, 2));
                return;
            }
            """;

    @Test
    void trustedPrecompiledHostClassHasExplicitPermissionUnderAotPolicy() throws Exception {
        Path source = temp.resolve("java-host-static.atto");
        Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        var trusted = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        // Compare both execution profiles with the exact same AttoLang guest
        // source and host-class capability. No embedded java{} compilation.
        for (ExecutionProfile.Mode mode : new ExecutionProfile.Mode[] {
                ExecutionProfile.Mode.JIT, ExecutionProfile.Mode.AOT
        }) {
            var stdout = new ByteArrayOutputStream();
            LinkedProgramRunner.run(source, trusted,
                    new ExecutionProfile(mode, ExecutionProfile.Platform.SERVER),
                    Set.of("java.lang.Long"), stdout, new ByteArrayOutputStream());
            assertEquals("42", stdout.toString(StandardCharsets.UTF_8).trim(),
                    "precompiled JDK class must preserve scalar host value");
        }
    }

    @Test
    void hostClassLookupMustNotBeAmbientEvenWithJavaInteropCapability() throws Exception {
        Path source = temp.resolve("no-ambient-host.atto");
        Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        var trusted = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        assertThrows(Exception.class, () -> LinkedProgramRunner.run(
                source, trusted,
                new ExecutionProfile(ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER),
                Set.of(), new ByteArrayOutputStream(), new ByteArrayOutputStream()),
                "missing exact java.lang.Long allowlist must not fall back to ambient host lookup");
        assertThrows(IllegalArgumentException.class, () ->
                IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP),
                "adversarial tenants must not acquire unrestricted Java host interop");
    }
}
