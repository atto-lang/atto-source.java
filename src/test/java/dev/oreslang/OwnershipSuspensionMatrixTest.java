package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class OwnershipSuspensionMatrixTest {
    private static final Path ROOT = Path.of("src/test/resources/ownership-matrix");
    @TestFactory Stream<DynamicTest> fiftySourcePrograms() throws Exception {
        List<String> cases = Files.readAllLines(ROOT.resolve("manifest.tsv"));
        assertEquals(50, cases.size(), "every source fixture must have a verdict");
        return cases.stream().map(row -> {
            String[] fields = row.split("\\t", -1);
            return DynamicTest.dynamicTest(fields[0], () -> {
                String code = Files.readString(ROOT.resolve(fields[0]));
                if (fields[1].equals("reject")) {
                    var error = assertThrows(IllegalArgumentException.class,
                            () -> OresCompiler.parseAndTypeCheck(code));
                    assertTrue(error.getMessage().toLowerCase().contains(fields[2].toLowerCase()),
                            "wrong rejection: " + error.getMessage());
                    assertFalse(error.getMessage().contains("parse error"), "syntax rejection is not borrow evidence");
                } else {
                    assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
                    if (fields[1].equals("execute")) {
                        int repetitions = Integer.getInteger("ores.ownership.matrix.repetitions", 1);
                        assertTrue(repetitions >= 1 && repetitions <= 20);
                        for (int attempt = 0; attempt < repetitions; attempt++) {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            try (Context context = Context.newBuilder(OresLanguage.ID)
                                    .allowAllAccess(false).out(out).build()) {
                                context.eval(Source.newBuilder(OresLanguage.ID, code, fields[0])
                                        .mimeType(OresLanguage.MIME_TYPE).build());
                            }
                            assertTrue(out.toString(StandardCharsets.UTF_8).matches(fields[2]),
                                    () -> "unexpected output: " + out.toString(StandardCharsets.UTF_8));
                        }
                    }
                }
            });
        });
    }
}
