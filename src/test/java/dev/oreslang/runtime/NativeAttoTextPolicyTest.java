package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class NativeAttoTextPolicyTest {
    @Test
    void canonicalTextRulesAreNativeAttoAndCompilerOwned() throws Exception {
        assertEquals("@std/text.atto", CoreLibrarySources.unitId("ores.std/text"));
        assertEquals("text.atto", CoreLibrarySources.fileName("std/text"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/text.ores"));
        String atto = CoreLibrarySources.source("std/text");
        assertTrue(atto.contains("define module text"));
        assertFalse(atto.contains("java:"));
        assertFalse(atto.contains("do java"));
        assertFalse(atto.contains("java {"));
    }

    @Test
    void linkedGuestTextPolicyMatchesLegacyAndUnicodeScalarSnapshot() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(
                Path.of("src/test/resources/stdlib-parity/text-policy.atto"),
                IsolatePolicy.developer(), ExecutionProfile.serverJit(), output, errors);
        assertTrue(linked.units().containsKey("@std/text.atto"),
                "must compile the .atto text source, not only execute Java regex policy");
        String expected = IntStream.range(0, 17).mapToObj(i -> "true")
                .collect(Collectors.joining("\n")) + "\nfalse\n3\n65\n128512\n20013";
        assertEquals(expected,
                output.toString(StandardCharsets.UTF_8).replace("\r", "").trim(),
                () -> errors.toString(StandardCharsets.UTF_8));
    }
    @Test
    void unicodeScalarSnapshotHasExactQuotaNotSilentTruncation() {
        assertEquals("65536", directGuestScalarCount("a".repeat(65_536)));
        PolyglotException over = assertThrows(PolyglotException.class,
                () -> directGuestScalarCount("a".repeat(65_537)));
        assertTrue(over.getMessage().contains("codepoints exceeds 65536 scalar limit"),
                over::getMessage);
    }

    @Test
    void wrongScalarPrimitiveArityAndTypeFailAtCompileTime() {
        for (String program : new String[] {
                "pub routine main(): void { val points = \"abc\".codepoints(1); return; }",
                "pub routine main(): void { val points = \"abc\".codepoints(\"bad\"); return; }"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(program)), program);
        }
    }

    private static String directGuestScalarCount(String value) {
        String program = "pub routine main(): void { "
                + "val points = \"" + value + "\".codepoints(); "
                + "stdio.println(points.size); return; }";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, program);
        }
        return output.toString(StandardCharsets.UTF_8).trim();
    }

}
