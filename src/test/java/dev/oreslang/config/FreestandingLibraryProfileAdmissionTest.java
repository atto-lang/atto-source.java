package dev.oreslang.config;

import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the two-layer ores.core / ores.std admission contract.
 * This is not a freestanding execution proof.
 */
final class FreestandingLibraryProfileAdmissionTest {
    @TempDir Path temp;

    private Path manifest(String value) throws Exception {
        Path result = temp.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.writeString(result, "schema_version = \"1\"\n[build]\n" + value + "\n");
        return result;
    }

    @Test
    void missingProfileDefaultsToStdWithoutChangingExistingProjects() throws Exception {
        OresProjectConfig withoutManifest = OresProjectConfig.discover(temp, Map.of());
        assertEquals(OresProjectConfig.LibraryProfile.STD, withoutManifest.libraryProfile());
        OresProjectConfig explicit = OresProjectConfig.load(
                manifest("library_profile = \"std\""), Map.of());
        assertEquals(OresProjectConfig.LibraryProfile.STD, explicit.libraryProfile());
        assertDoesNotThrow(explicit::requireStdJavaBackend);
    }

    @Test
    void onlyCoreAndStdProfilesAreAccepted() throws Exception {
        for (String value : new String[]{
                "library_profile = \"hosted\"",
                "library_profile = \"freestanding-core\"",
                "library_profile = \"freestanding-alloc\"",
                "library_profile = \"freestanding-actor\"",
                "library_profile = \"freestanding-otp\"",
                "library_profile = 7",
                "library_profile = true",
                "library_profille = \"core\"",
                "library_profile = \"CORE\"",
                "library_profile = \"\""
        }) {
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresProjectConfig.load(manifest(value), Map.of()), value);
            assertTrue(denied.getMessage().contains("library_profile")
                    || denied.getMessage().contains("unknown build configuration"), value);
        }
        assertEquals(OresProjectConfig.LibraryProfile.CORE,
                OresProjectConfig.load(manifest("library_profile = \"core\""), Map.of()).libraryProfile());
        assertEquals(OresProjectConfig.LibraryProfile.STD,
                OresProjectConfig.load(manifest("library_profile = \"std\""), Map.of()).libraryProfile());
    }

    @Test
    void coreProfileRejectsBeforeAnyGuestParsingOrEmission() throws Exception {
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, "THIS IS NOT A VALID ORESLANG SOURCE !!!!!");
        manifest("library_profile = \"core\"");
        OresProjectConfig config = OresProjectConfig.discover(entry, Map.of());
        assertEquals(OresProjectConfig.LibraryProfile.CORE, config.libraryProfile());
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()));
        assertTrue(denied.getMessage().contains("ores.core"));
        assertTrue(denied.getMessage().contains("not yet supported"));
    }

    @Test
    void executionEntryPointAlsoRejectsCoreBeforeHostPolicy() throws Exception {
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, "not valid Oreslang source !!!");
        manifest("library_profile = \"core\"");
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.runWithExitStatus(
                        entry, null, null, null, null, Set.of(), Map.of(), null, null));
        assertTrue(denied.getMessage().contains("ores.core"));
        assertTrue(denied.getMessage().contains("not yet supported"));
    }

    @Test
    void nestedBuildAuthorityIsNotSilentlyAccepted() throws Exception {
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest(
                        "[build.extra]\nallow_io = true"), Map.of()));
        assertTrue(denied.getMessage().contains("unknown build configuration"));
    }

    @Test
    void buildSectionMayNotCarryIgnoredAuthorityOptions() throws Exception {
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.load(manifest(
                        "library_profile = \"std\"\nunchecked_os = true"), Map.of()));
        assertTrue(denied.getMessage().contains("unknown build configuration"));
    }
}
