package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

final class ConditionalExpressionContractTest {
    private static final Path CONTRACT = Path.of(
            "contracts", "v1", "conditional-expression-v1.json");

    private static final Pattern VECTOR = Pattern.compile(
            "\\{\\s*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,"
                    + "\\s*\\\"source\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,"
                    + "\\s*(?:\\\"result\\\"\\s*:\\s*(-?\\d+)"
                    + "|\\\"reject\\\"\\s*:\\s*\\\"([^\\\"]+)\\\")\\s*\\}",
            Pattern.DOTALL);

    @Test
    void executesPortableConditionalContractVectors() throws Exception {
        String json = Files.readString(CONTRACT, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema\": \"oreslang.conditional-expression.v1\""));
        assertTrue(json.contains("\"produces_value\": false"));
        assertTrue(json.contains("\"condition_type\": \"bool\""));
        assertTrue(json.contains("\"truthiness_coercion\": false"));
        assertTrue(json.contains("\"associativity\": \"right\""));
        assertTrue(json.contains("\"branches\": \"selected_arm_only\""));

        Matcher matcher = VECTOR.matcher(json);
        int vectors = 0;
        while (matcher.find()) {
            vectors++;
            String id = matcher.group(1);
            String source = matcher.group(2);
            String expected = matcher.group(3);
            String rejection = matcher.group(4);

            if (expected != null) {
                assertDoesNotThrow(
                        () -> TypeChecker.check(Parser.parse(source)),
                        () -> id + " must typecheck");
                String expression = returnedExpression(source);
                assertEquals(
                        expected,
                        run("pub routine main(): void { "
                                + "stdio.stdout.write(" + expression + "); return; }"),
                        id);
            } else {
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(source)),
                        id);
                assertEquals("condition_not_bool", rejection, id);
                assertTrue(failure.getMessage().contains("ternary condition"), failure::getMessage);
            }
        }
        assertEquals(5, vectors, "portable conditional vector count");
    }

    private static String returnedExpression(String source) {
        int start = source.indexOf("return ");
        int end = source.lastIndexOf("; }");
        assertTrue(start >= 0 && end > start, source);
        return source.substring(start + "return ".length(), end);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "conditional-contract.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
