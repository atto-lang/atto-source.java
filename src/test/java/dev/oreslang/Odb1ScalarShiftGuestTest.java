package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine Java/GraalVM Oreslang scalar guest execution for the ODB1
 * little-endian encoding primitive. Not a codec, filesystem or ACK test.
 */
final class Odb1ScalarShiftGuestTest {
    private record Case(String name, String expression, String stdout) { }

    private static final List<Case> CASES = List.of(
            new Case("zero", "0 ashr 8", "0"),
            new Case("below-one-byte", "255 ashr 8", "0"),
            new Case("first-high-byte", "256 ashr 8", "1"),
            new Case("unsigned-16-maximum", "65535 ashr 8", "255"),
            new Case("odb1-id-maximum", "2147483647 ashr 8", "8388607"),
            new Case("addition-before-shift", "255 + 1 ashr 8", "1"),
            new Case("two-shifts", "65536 ashr 8 ashr 8", "1"),
            new Case("signed-negative", "-256 ashr 8", "-1"),
            new Case("signed-minimum", "-9223372036854775808 ashr 8",
                    "-36028797018963968")
    );

    @TestFactory
    Stream<DynamicTest> javaGraalOdb1ScalarNativeGuests() {
        return CASES.stream().map(vector -> DynamicTest.dynamicTest(vector.name(), () -> {
            String program = "pub fnc main():void { stdio.stdout.write(" +
                    vector.expression() + "); return; }\n";
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)), program);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            Source source = Source.newBuilder(OresLanguage.ID, program,
                    "odb1-shift-" + vector.name() + ".ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(stdout).build()) {
                context.eval(source);
            }
            assertEquals(vector.stdout(), stdout.toString(StandardCharsets.UTF_8),
                    "wrong native ODB1 scalar byte-extraction semantics");
        }));
    }

    @Test
    void integerDivisionIsNotImplicitIntegerBytePacking() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                Parser.parse("pub fnc main():int{return 256/256;}")));
        assertDoesNotThrow(() -> TypeChecker.check(
                Parser.parse("pub fnc main():int{return 256 ashr 8;}")));
    }
}
