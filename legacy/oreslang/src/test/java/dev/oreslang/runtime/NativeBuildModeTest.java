package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeBuildModeTest {
    @Test void nativeCapabilitiesCannotBeEnabledByExecutionPolicy() {
        for (var mode : ExecutionProfile.Mode.values()) {
            assertDoesNotThrow(() -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.JVM, mode));
            if (mode == ExecutionProfile.Mode.AOT) {
                assertDoesNotThrow(() -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.AOT, mode));
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.AOT, mode));
            }
            if (mode == ExecutionProfile.Mode.JIT) {
                assertThrows(IllegalArgumentException.class,
                        () -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.HYBRID, mode));
            } else {
                assertDoesNotThrow(() -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.HYBRID, mode));
            }
        }
    }
    @Test void nativeReferenceModeIsBuildTimeAndDefaultsCompact() {
        assertEquals(NativeBuildMode.ManagedReferenceMode.COMPRESSED32,
                NativeBuildMode.parseManagedReferenceMode(true, "compressed32"));
        assertEquals(NativeBuildMode.ManagedReferenceMode.NATIVE64,
                NativeBuildMode.parseManagedReferenceMode(true, "native64"));
        assertEquals(NativeBuildMode.ManagedReferenceMode.JVM_MANAGED,
                NativeBuildMode.parseManagedReferenceMode(false, "native64"));
        assertEquals(NativeBuildMode.ManagedReferenceMode.JVM_MANAGED,
                NativeBuildMode.parseManagedReferenceMode(false, null));
        assertThrows(IllegalStateException.class,
                () -> NativeBuildMode.parseManagedReferenceMode(true, null));
        assertThrows(IllegalStateException.class,
                () -> NativeBuildMode.parseManagedReferenceMode(true, "auto"));
    }

    @Test void jvmDoesNotPretendToBeANativeImage() {
        assertEquals(NativeBuildMode.Kind.JVM, NativeBuildMode.kind());
        assertEquals(NativeBuildMode.ManagedReferenceMode.JVM_MANAGED,
                NativeBuildMode.managedReferenceMode());
        assertEquals("jit", NativeBuildMode.defaultExecutionMode());
        assertEquals(ExecutionProfile.Mode.JIT,
                IsolatePolicy.executionProfileFromApplicationArguments(new String[0]).mode());
        assertEquals(ExecutionProfile.Mode.AOT,
                IsolatePolicy.executionProfileFromApplicationArguments(new String[] {"--ores-execution-mode=aot"}).mode());
    }
}
