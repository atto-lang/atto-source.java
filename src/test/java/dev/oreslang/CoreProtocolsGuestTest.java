package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.types.TypeChecker;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real compiler-owned core import and declaration admission for v1 protocols. */
final class CoreProtocolsGuestTest {
    @TempDir Path temp;

    @Test
    void protocolSourceIsRegisteredAndTypechecked() throws Exception {
        assertTrue(CoreLibrarySources.packagedCorePaths().contains("protocols.ores"));
        String source = CoreLibrarySources.oresCoreSource("ores.core/protocols");
        assertTrue(source.contains("define pub trait Read<E>"));
        assertTrue(source.contains("define pub trait Write<E>"));
        assertTrue(source.contains("define pub trait IntoIterator<T>"));
        assertTrue(source.contains("define pub trait Service<Request, Reply, E>"));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test
    void explicitTraitSelectorsResolveFromCoreNotAmbientFilesystem() throws Exception {
        Path guest = temp.resolve("main.atto");
        Files.writeString(guest, """
                import trait Read from "ores.core/protocols";
                import trait Write from "ores.core/protocols";
                import trait Source from "ores.core/protocols";
                import trait Eq from "ores.core/protocols";
                pub fnc main(): void { return; }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(guest));
    }

    @Test
    void importedCoreTraitRequiresActualNominalMethodConformance() throws Exception {
        Path accepted = temp.resolve("good.atto");
        Files.writeString(accepted, """
                import trait Eq from "ores.core/protocols";
                define class Counter implements Eq<int> as
                  pub equals(int other): bool { return true; }
                end
                pub fnc main(): void { return; }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(accepted));

        Path rejected = temp.resolve("bad.atto");
        Files.writeString(rejected, """
                import trait Eq from "ores.core/protocols";
                define class Counter implements Eq<int> as
                end
                pub fnc main(): void { return; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(rejected));
    }

    @Test
    void coreProtocolNamesCannotBeDefinedAsUserClaimedSafetyAuthorities() throws Exception {
        String source = CoreLibrarySources.oresCoreSource("ores.core/protocols");
        for (String forbidden : new String[] {
                "trait Sendable", "trait Shareable", "trait Copy",
                "trait Drop", "trait Receives", "trait Supervisor"
        }) {
            assertFalse(source.contains("define pub " + forbidden),
                    "safety authority must be compiler/runtime-owned: " + forbidden);
        }
        assertFalse(source.contains("fnc read_blocking("));
        assertFalse(source.contains("fnc write_blocking("));
    }

    @Test
    void unknownCoreModulesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("ores.core/protocols/../ambient"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.oresCoreSource("ores.core/unregistered"));
    }
}
