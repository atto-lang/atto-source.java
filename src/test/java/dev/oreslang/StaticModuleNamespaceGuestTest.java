package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guest execution proof: the runtime may use an internal ModuleFacade for
 * qualified member dispatch, but source modules must never become values.
 */
final class StaticModuleNamespaceGuestTest {

    private static final String MODULE = """
            define module Catalog as
              pub const version: int = 4;
              pub fnc get(): int { return 4; }
            end
            """;

    @Test
    void qualifiedModuleMembersAndCallableExtractionRemainExecutable() throws Exception {
        String output = evaluate(MODULE + """
                pub routine main(): void {
                  const f = Catalog.get;
                  stdio.stdout.write(Catalog.version);
                  stdio.stdout.write("|");
                  stdio.stdout.write(Catalog.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(f());
                  return;
                }
                """);
        assertEquals("4|4|4", output);
    }

    @Test
    void directNamespaceValueFailsBeforeGuestExecution() {
        assertThrows(Exception.class, () -> evaluate(MODULE + """
                pub routine main(): void {
                  const escaped = Catalog;
                  stdio.stdout.write("should-not-execute");
                  return;
                }
                """));
    }

    @Test
    void immutableModuleMemberAssignmentFailsBeforeGuestExecution() {
        assertThrows(Exception.class, () -> evaluate(MODULE + """
                pub routine main(): void {
                  Catalog.version = 5;
                  stdio.stdout.write("should-not-execute");
                  return;
                }
                """));
    }

    private static String evaluate(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "static-module-namespace.ores")
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
