package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed admission. These tests do not run a host-model substitute.
 * Source presence or parser success is not execution evidence.
 */
final class CoreFourGuestTest {
    private static final Path ROOT = Path.of("core");
    private static final Path FIXTURES = Path.of("tests", "fixtures", "core-four-v1");


    @Test
    void coreImportsAreCompilerOwnedAndAliasToOneUnit() throws Exception {
        for (String module : new String[] {"range", "cell", "contracts", "num"}) {
            String canonical = "atto.core/" + module;
            String shorthand = "core/" + module;
            String legacy = "ores.core/" + module;
            assertTrue(dev.oreslang.stdlib.CoreLibrarySources.isOresCoreImport(shorthand));
            assertEquals(dev.oreslang.stdlib.CoreLibrarySources.oresCoreUnitId(canonical),
                    dev.oreslang.stdlib.CoreLibrarySources.oresCoreUnitId(shorthand));
            assertEquals(dev.oreslang.stdlib.CoreLibrarySources.oresCoreUnitId(canonical),
                    dev.oreslang.stdlib.CoreLibrarySources.oresCoreUnitId(legacy));
            assertEquals(dev.oreslang.stdlib.CoreLibrarySources.oresCoreUnitId(canonical),
                    dev.oreslang.stdlib.CoreLibrarySources.oresCoreUnitId(canonical + ".atto"));
            assertEquals(Files.readString(ROOT.resolve(module + ".atto")),
                    dev.oreslang.stdlib.CoreLibrarySources.oresCoreSource(shorthand),
                    "packaged resource bytes must equal reviewed backend-owned Atto source");
        }
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.stdlib.CoreLibrarySources.oresCoreSource("core/../contracts"));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.stdlib.CoreLibrarySources.oresCoreSource("core/missing"));
    }

    @Test
    void coreModulesParseAndTypecheck() throws Exception {
        for (String name : new String[] {"range", "cell", "contracts", "num"}) {
            Path path = ROOT.resolve(name + ".atto");
            assertTrue(Files.isRegularFile(path), path.toString());
            String source = Files.readString(path);
            assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)),
                    "guest compiler must accept " + path);
        }
    }

    @Test
    void importsLinkAndGuestExecutesExactI64Vectors() throws Exception {
        Path positive = FIXTURES.resolve("smoke.atto");
        assertTrue(Files.isRegularFile(positive));
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(positive, Map.of()));
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                positive, IsolatePolicy.developer(), ExecutionProfile.serverJit(), stdout, stderr);
        assertEquals(42, result.exitStatus(),
                "actual compiled Atto smoke guest must exit 42; stderr=" + stderr);
    }

    @Test
    void requiresFalseRemainsTrappableInGuestExecution() throws Exception {
        Path negative = FIXTURES.resolve("contract-failure.atto");
        assertTrue(Files.isRegularFile(negative));
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(negative, Map.of()));
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        Throwable thrown = null;
        Integer exit = null;
        try {
            var result = LinkedProgramRunner.runWithExitStatus(
                    negative, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    stdout, stderr);
            exit = result.exitStatus();
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertTrue(thrown != null || (exit != null && exit != 0 && exit != 42),
                "guest continued or exited successfully after requires(false)");
        String diagnostic = stdout.toString() + stderr.toString()
                + (thrown == null ? "" : String.valueOf(thrown.getMessage()));
        assertTrue(diagnostic.contains("core-four-v1: precondition failed"),
                "negative guest failed without contract-specific runtime witness: " + diagnostic);
    }
}
