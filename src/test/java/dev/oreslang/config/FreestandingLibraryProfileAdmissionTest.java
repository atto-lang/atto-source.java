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
 * This proves the hosted Java compiler fails closed. It is not a freestanding
 * execution or actor/allocator runtime parity test.
 */
final class FreestandingLibraryProfileAdmissionTest {
    @TempDir Path temp;

    private Path manifest(String value) throws Exception {
        Path result = temp.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.writeString(result, "schema_version = \"1\"\n[build]\n" + value + "\n");
        return result;
    }

    @Test
    void missingProfileDefaultsToHostedWithoutChangingExistingProjects() throws Exception {
        OresProjectConfig withoutManifest = OresProjectConfig.discover(temp, Map.of());
        assertEquals(OresProjectConfig.LibraryProfile.HOSTED, withoutManifest.libraryProfile());
        OresProjectConfig explicit = OresProjectConfig.load(
                manifest("library_profile = \"hosted\""), Map.of());
        assertEquals(OresProjectConfig.LibraryProfile.HOSTED, explicit.libraryProfile());
        assertDoesNotThrow(explicit::requireHostedJavaBackend);
    }

    @Test
    void unknownAndMistypedProfilesFailManifestAdmission() throws Exception {
        for (String value : new String[]{
                "library_profile = \"freestanding-unknown\"",
                "library_profile = 7",
                "library_profile = true",
                "library_profille = \"freestanding-core\"",
                "library_profile = \"HOSTED\"",
                "library_profile = \"\""
        }) {
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresProjectConfig.load(manifest(value), Map.of()), value);
            assertTrue(denied.getMessage().contains("library_profile")
                    || denied.getMessage().contains("unknown build configuration"), value);
        }
    }

    @Test
    void allFreestandingProfilesRejectBeforeAnyGuestParsingOrEmission() throws Exception {
        Path entry = temp.resolve("main.ores");
        // Deliberately invalid Oreslang syntax: should never get to the parser.
        Files.writeString(entry, "THIS IS NOT A VALID ORESLANG SOURCE !!!!!");
        for (String profile : new String[]{
                "freestanding-core", "freestanding-alloc",
                "freestanding-actor", "freestanding-otp"
        }) {
            manifest("library_profile = \"" + profile + "\"");
            OresProjectConfig config = OresProjectConfig.discover(entry, Map.of());
            assertEquals(profile, config.libraryProfile().manifestValue());
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry, Map.of()), profile);
            assertTrue(denied.getMessage().contains(profile));
            assertTrue(denied.getMessage().contains("not yet supported"));
        }
    }

    @Test
    void executionEntryPointAlsoRejectsBeforeParsingOrUsingHostPolicy() throws Exception {
        Path entry = temp.resolve("main.ores");
        // Even invalid guest syntax and missing host policy must not be reached.
        Files.writeString(entry, "not valid Oreslang source !!!");
        manifest("library_profile = \"freestanding-core\"");
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.runWithExitStatus(
                        entry, null, null, null, null, Set.of(), Map.of(), null, null));
        assertTrue(denied.getMessage().contains("freestanding-core"));
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
                        "library_profile = \"hosted\"\nunchecked_os = true"), Map.of()));
        assertTrue(denied.getMessage().contains("unknown build configuration"));
    }
}
