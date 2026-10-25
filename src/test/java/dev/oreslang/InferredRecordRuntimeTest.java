package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable guest-language conformance: parser, type checker, ownership,
 * and GraalVM Truffle runtime must agree on inferred record/struct aliases.
 */
final class InferredRecordRuntimeTest {
    @Test
    void shortAndExplicitRecordAliasesRunWithoutRuntimeTypeObjects() {
        String source = """
                pub fnc main(): void {
                    const Person first = infer record{name: "Ada", age: 37};
                    stdio.println(first.name);
                    const Person second = infer record{age: 42, name: "Grace"};
                    stdio.println(second.name);
                    const Contact contact = infer<Contact> record{
                        "first name": "Linus",
                        "work@email": "linus@example.com"
                    };
                    stdio.println(contact["first name"]);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "inferred-record-runtime.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });
        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("Ada\nGrace\nLinus\n", text);
    }

    @Test
    void guestRuntimeAdmissionCannotBypassReadonlyRecordFieldRules() {
        String source = """
                pub fnc main(): void {
                    const Person first = infer record{name: "Ada", age: 37};
                    const Person second = first;
                    second.age = 42;
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }
}
