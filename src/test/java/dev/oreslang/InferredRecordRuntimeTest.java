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
    void computedRecordTupleAndEntryFieldsExecuteThroughTheSameIndexPath() {
        String source = """
                pub fnc main(): void {
                    const Entry e = record{key: "id", value: "payload"};
                    const name = "value";
                    stdio.println(e[name]);
                    const Pair t = tuple(3, 4);
                    const offset = 0;
                    stdio.println(t[offset]);
                    const T s = infer<T> struct{x: 3.0, y: 4.0};
                    const field = "x";
                    stdio.println(s[field]);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source, "computed-aggregate-index.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context ctx = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(out)
                    .build()) {
                ctx.eval(program);
            }
        });
        assertEquals("payload\n3\n3.0\n",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void missingRuntimeKeyAndOutOfRangeTupleIndexFailRatherThanReifyUnknown() {
        String missingKey = """
                pub fnc main(): void {
                    const T r = record{x: 3.0};
                    const string key = "missing";
                    stdio.println(r[key]);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(missingKey));
        assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> {
            try (Context ctx = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                ctx.eval(Source.newBuilder(OresLanguage.ID, missingKey,
                        "computed-missing-key.ores").build());
            }
        });

        String badTupleIndex = """
                pub fnc main(): void {
                    const T t = tuple(3.0, 4.0);
                    const int index = 3;
                    stdio.println(t[index]);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(badTupleIndex));
        assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> {
            try (Context ctx = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                ctx.eval(Source.newBuilder(OresLanguage.ID, badTupleIndex,
                        "computed-tuple-oob.ores").build());
            }
        });
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
