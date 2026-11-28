package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ListLengthGuestRuntimeParityTest {
    private static String run(String name, String program) throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8).trim();
    }

    @Test
    void scalarListLengthMatchesSizeBeforeAndAfterInsertion() throws Exception {
        String program = """
                pub fnc main(): void {
                  val List<int> values = new List<int>();
                  stdio.stdout.write(values.length);
                  values.add(5);
                  values.add(8);
                  stdio.stdout.write(values.length);
                  stdio.stdout.write(values.size);
                  return;
                }
                """;
        assertEquals("022", run("list-length-parity.atto", program));
    }

    @Test
    void listLengthCanBeReadThroughBorrowWithoutMutableProjection() throws Exception {
        String program = """
                define class Holder as
                  pub val List<int> values;
                end
                fnc count(borrow Holder holder): int {
                  return holder.values.length;
                }
                pub fnc main(): void {
                  val List<int> values = new List<int>();
                  values.add(14);
                  val Holder holder = new Holder(values);
                  stdio.stdout.write(count(rt borrow holder));
                  return;
                }
                """;
        assertEquals("1", run("borrowed-list-length.atto", program));
    }
}
