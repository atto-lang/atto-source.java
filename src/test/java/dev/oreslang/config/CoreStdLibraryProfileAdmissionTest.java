package dev.oreslang.config;

import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class CoreStdLibraryProfileAdmissionTest {
    @TempDir Path temp;

    private Path manifest(String buildLines) throws Exception {
        Path result = temp.resolve(OresProjectConfig.MANIFEST_NAME);
        Files.writeString(result,
                "schema_version = \"1\"\n[build]\n" + buildLines + "\n");
        return result;
    }

    @Test
    void missingProfileDefaultsToStd() throws Exception {
        OresProjectConfig withoutManifest = OresProjectConfig.discover(temp, Map.of());
        assertEquals(OresProjectConfig.LibraryProfile.STD, withoutManifest.libraryProfile());

        OresProjectConfig explicit = OresProjectConfig.load(
                manifest("library_profile = \"std\""), Map.of());
        assertEquals(OresProjectConfig.LibraryProfile.STD, explicit.libraryProfile());
        assertDoesNotThrow(explicit::requireStdJavaBackend);
    }

    @Test
    void exactlyCoreAndStdAreAccepted() throws Exception {
        assertEquals(OresProjectConfig.LibraryProfile.CORE,
                OresProjectConfig.load(
                        manifest("library_profile = \"core\""), Map.of()).libraryProfile());

        for (String value : new String[]{
                "library_profile = \"hosted\"",
                "library_profile = \"freestanding-core\"",
                "library_profile = \"alloc\"",
                "library_profile = \"actor\"",
                "library_profile = \"otp\"",
                "library_profile = 7",
                "library_profile = true",
                "library_profile = \"CORE\"",
                "library_profile = \"\""
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresProjectConfig.load(manifest(value), Map.of()), value);
        }
    }

    @Test
    void unknownBuildAuthorityFailsClosed() throws Exception {
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> OresProjectConfig.load(
                        manifest("library_profile = \"std\"\nunchecked_os = true"), Map.of()));
        assertTrue(denied.getMessage().contains("unknown build configuration"));
    }

    @Test
    void coreProfileRejectsBeforeGuestParsingOrEmission() throws Exception {
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, "THIS IS NOT VALID ORESLANG SOURCE !!!");
        manifest("library_profile = \"core\"");

        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()));
        assertTrue(denied.getMessage().contains("ores.core"));
        assertTrue(denied.getMessage().contains("not yet supported"));
    }

    @Test
    void executionEntryAlsoRejectsCoreBeforeHostPolicy() throws Exception {
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, "THIS IS NOT VALID ORESLANG SOURCE !!!");
        manifest("library_profile = \"core\"");

        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.runWithExitStatus(
                        entry, null, null, null, null, Set.of(), Map.of(), null, null));
        assertTrue(denied.getMessage().contains("ores.core"));
        assertTrue(denied.getMessage().contains("not yet supported"));
    }
}
