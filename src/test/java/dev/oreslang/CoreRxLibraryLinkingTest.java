package dev.oreslang;

import dev.oreslang.imports.CoreLibraryRegistry;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CoreRxLibraryLinkingTest {
    @TempDir Path temp;

    @Test
    void rxIsNotCollectedWithoutAnExplicitImport() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                pub fnc main(): void {
                  return;
                }
                """);

        var build = LinkedProgramRunner.validate(main, Map.of());

        assertEquals(1, build.units().size());
        assertFalse(build.units().containsKey(CoreLibraryRegistry.RX_UNIT_ID));
    }

    @Test
    void rxIsCollectedWhenStdRxIsImported() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                import module rx from "std/rx";

                pub fnc main(): void {
                  return;
                }
                """);

        var build = LinkedProgramRunner.validate(main, Map.of());

        assertEquals(2, build.units().size());
        assertTrue(build.units().containsKey(CoreLibraryRegistry.RX_UNIT_ID));
        assertTrue(build.units().get(CoreLibraryRegistry.RX_UNIT_ID)
                .program().modules().stream().anyMatch(module -> module.name().equals("rx")));
    }

    @Test
    void stdNamespaceIsCompilerOwnedAndFailsClosedForUnknownLibraries() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                import module nope from "std/not-real";

                pub fnc main(): void {
                  return;
                }
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main, Map.of()));

        assertTrue(error.getMessage().contains("unknown Oreslang core library import"));
    }
}
