package dev.oreslang.runtime;

import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G0 fail-closed guest admission. These are Java host tests, not compiled
 * Oreslang native-FFI or GraalVM Native Image execution evidence.
 */
final class UnsafeStdlibIsolationGateTest {
    @Test
    void allUnsafeStdlibSpellingsAreReservedAndGuestDenied() {
        for (String path : new String[] {
                "ores.std/unsafe", "ores.std/unsafe/ffi",
                "ores.std/unsafe.ores", "std/unsafe", "std/unsafe/memory",
                "std\\unsafe\\ffi", "std.unsafe", "std.unsafe.ffi",
                "std:unsafe", "std:unsafe.ffi",
                "ores.std:unsafe", "ores.std.unsafe",
                "std/./unsafe/ffi", "std/lib/../unsafe/native",
                "ores.std//unsafe/memory",
                "./std/unsafe/ffi", "../ores.std/unsafe/native",
                "pkg/../std/unsafe/memory"}) {
            assertTrue(CoreLibrarySources.isUnsafeStdImport(path), path);
            assertTrue(CoreLibrarySources.isReservedOresLibraryImport(path), path);
            assertThrows(SecurityException.class, () -> ImportRules.validate(
                    Parser.parse("import module native_ops from \"" + path.replace('\\', '/') + "\";")
                            .imports().getFirst()), path);
        }
    }

    @Test
    void similarNamesAreNotAccidentallyReserved() {
        for (String path : new String[] {
                "std/unsafe_tools", "std/unsafe-math",
                "std/safe", "ores.std/unsafe_notes", "std:unsafe_tools",
                "pkg:unsafe", "my/unsafe", "ores.core/numeric",
                "std/lib/../safe", "std/./unsafe_tools",
                "pkg/std/unsafe_tools.ores", "pkg/std/unsafe/memory.ores"}) {
            assertFalse(CoreLibrarySources.isUnsafeStdImport(path), path);
        }
    }

    @Test
    void adversarialPolicyCannotGainNativeAuthorityByAnyFlag() {
        for (IsolatePolicy.Capability forbidden : new IsolatePolicy.Capability[] {
                IsolatePolicy.Capability.NATIVE,
                IsolatePolicy.Capability.FFI,
                IsolatePolicy.Capability.REFLECTION}) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> new IsolatePolicy(Set.of(forbidden),
                            32L * 1024 * 1024, 128, Duration.ofSeconds(1), true));
            assertTrue(denied.getMessage().contains(forbidden.name()));
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatePolicy.untrustedActor().withCapabilities(forbidden));
        }
    }

    @Test
    void vmContextCannotEnableNativeEvenIfMarkedTrusted() {
        for (IsolatePolicy.Capability forbidden : new IsolatePolicy.Capability[] {
                IsolatePolicy.Capability.NATIVE, IsolatePolicy.Capability.FFI,
                IsolatePolicy.Capability.REFLECTION}) {
            IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(forbidden);
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> policy.restrictedContextBuilder(
                            ExecutionProfile.serverJit(), Set.of()));
            assertTrue(denied.getMessage().contains("VM-isolate"));
        }
        IsolatePolicy mixed = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.JAVA_SOURCE_INTEROP);
        assertThrows(SecurityException.class, () -> mixed.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of()));
    }
}
