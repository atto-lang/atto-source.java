package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes native Oreslang actor policy as source code, not as a Java
 * implementation of the policy. Java only hosts the guest compilation test.
 */
final class NativeActorRuntimePolicyTest {
    @Test
    void nativeActorRuntimePolicyExecutesEveryGuestInvariant() throws Exception {
        String source = CoreLibrarySources.source("std/actorruntime");
        MixedSourceUnit module = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/actorruntime"),
                CoreLibrarySources.fileName("std/actorruntime"),
                source);
        assertTrue(module.hasOresSource());
        assertFalse(module.hasJavaSource());
        assertFalse(source.contains("java:"));
        assertFalse(source.contains("java {"));
        assertFalse(source.contains("do java {"));

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                Path.of("examples/native-actorruntime-contract.ores"),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                out,
                err);
        assertTrue(build.units().containsKey("@std/actorruntime.ores"),
                "native Oreslang policy must be loaded as source");
        assertEquals("ACTORRUNTIME_NATIVE_CONTRACT_OK",
                out.toString(StandardCharsets.UTF_8).trim(),
                () -> "guest policy conformance failed: "
                        + err.toString(StandardCharsets.UTF_8));
    }
}
