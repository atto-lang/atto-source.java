package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NativeArrayRuntimeMemberTest {
    @Test
    void arrayLiteralSupportsSizeGetAddAndSet() throws Exception {
        String output = run("""
                pub routine main(): void {
                  let Array<int> values = arr[];
                  values.add(10);
                  values.add(20);
                  stdio.println(values.size);
                  stdio.println(values.get(0));
                  values.set(1, 42);
                  stdio.println(values.get(1));
                  return;
                }
                """);

        assertEquals("2\n10\n42\n", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "native-array-runtime-members.ores")
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
