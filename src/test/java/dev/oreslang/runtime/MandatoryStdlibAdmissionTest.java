package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MandatoryStdlibAdmissionTest {
    @Test void everyPackagedOreslangModulePassesMandatoryIoAdmission() throws Exception {
        for (String path : CoreLibrarySources.packagedSourcePaths()) {
            String name = "std/" + path.substring(0, path.length() - ".ores".length());
            String source = CoreLibrarySources.source(name);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source), name);
            assertFalse(source.contains("stdio."), name + " must return guest-owned data or use an async transport");
        }
    }

    @Test void permissionIndependentCompilerGateCoversAliasesAndUnreachableCode() {
        for (String expression : java.util.List.of("fs.read_text", "File.write_text_async",
                "http.get_text_async", "network.connect_async", "network.listen_nonblocking",
                "stdio.println", "stdio.stdout.write", "stdio.stdin.read_line", "print")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                    "pub fnc main(): void { return; val hidden = " + expression + "; }"), expression);
            assertTrue(failure.getMessage().contains("blocking I/O"), failure::toString);
        }
    }
}
