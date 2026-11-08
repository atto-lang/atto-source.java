package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.SafeHostMemberManifest;
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
                    Set.of(SafeHostMemberManifest.LONG_SUM_I64),
                    stdout, new ByteArrayOutputStream());
            assertEquals("42", stdout.toString(StandardCharsets.UTF_8).trim(),
                    "precompiled JDK class must preserve scalar host value");
        }
    }

    @Test
    void manifestGrantDoesNotAuthorizeOtherMembersOnTheSameHostClass() throws Exception {
        Path source = temp.resolve("unauthorized-static-host.atto");
        Files.writeString(source, """
                import class Long as JLong from "java:java.lang.Long";
                pub fnc main(): void {
                    stdio.println(JLong.toString(42));
                    return;
                }
                """, StandardCharsets.UTF_8);
        var trusted = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        var profile = new ExecutionProfile(
                ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER);

        // Positive control on the *same* guest source: prove that toString
        // actually parses, links and executes under explicitly trusted legacy
        // class-wide interop. Otherwise a parser failure could masquerade as
        // evidence that the method-scoped grant enforced isolation.
        var legacyOutput = new ByteArrayOutputStream();
        LinkedProgramRunner.run(source, trusted, profile,
                Set.of("java.lang.Long"), legacyOutput, new ByteArrayOutputStream());
        assertEquals("42", legacyOutput.toString(StandardCharsets.UTF_8).trim(),
                "control must execute before its scoped-denial result is meaningful");

        assertThrows(Exception.class, () -> LinkedProgramRunner.run(source, trusted, profile,
                Set.of(SafeHostMemberManifest.LONG_SUM_I64),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()),
                "approving Long.sum must not grant access to Long.toString");
    }

    @Test
    void fabricatedOrBroadenedHostMemberGrantsFailBeforeContextConstruction() {
        var policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        var profile = new ExecutionProfile(
                ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER);
        assertThrows(IllegalArgumentException.class,
                () -> policy.restrictedContextBuilder(profile,
                        Set.of("java.lang.Long#toString(long)")),
                "an unknown member must not become an ambient host import");
        assertThrows(IllegalArgumentException.class,
                () -> policy.restrictedContextBuilder(profile,
                        Set.of("java.lang.Long", SafeHostMemberManifest.LONG_SUM_I64)),
                "legacy class-wide grant must not silently override scoped grant");
        assertThrows(IllegalArgumentException.class,
                () -> policy.restrictedContextBuilder(profile,
                        Set.of("java.lang.Long#sum(int,int)")),
                "overload identity is part of the reviewed host manifest");
        assertThrows(IllegalArgumentException.class,
                () -> policy.restrictedContextBuilder(profile,
                        Set.of("java.lang.Runtime#exec(java.lang.String)")),
                "unsafe capability cannot be granted through a forged selector");
    }

    @Test
    void missingJavaInteropCapabilityRejectsApprovedHostMethod() {
        var restricted = IsolatePolicy.developer();
        var profile = new ExecutionProfile(
                ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER);
        assertThrows(SecurityException.class,
                () -> restricted.restrictedContextBuilder(
                        profile, Set.of(SafeHostMemberManifest.LONG_SUM_I64)),
                "a valid method selector must never imply JAVA_INTEROP capability");
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
