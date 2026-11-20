package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Formatting arguments hold loans on their owners. Classes reached through
 * a module import have no layout in the ownership checker, so their field
 * projections must pin the root owner instead of being rejected outright.
 */
final class ImportedClassFormattingBorrowTest {
    private static final String LIBRARY = """
            define module lib as
              define class Box as
                pub let int n;
                pub val String s;
              end
              pub fnc make() -> Box {
                return new Box(7, "x");
              }
              pub fnc consume(Box item) -> String {
                return "consumed";
              }
              pub fnc edit(borrow mut Box item) -> String {
                item.n = 8;
                return "edited";
              }
            end
            """;

    @TempDir Path temp;

    private String run(String body) throws Exception {
        Files.createDirectories(temp.resolve("m"));
        Files.writeString(temp.resolve("m/lib.ores"), LIBRARY);
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, """
                import module lib from "./m/lib";
                pub routine main() -> void {
                """ + body + """
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
    }

    private void assertRejected(String body, String diagnostic) {
        Exception failure = assertThrows(Exception.class, () -> run(body));
        assertTrue(String.valueOf(failure.getMessage()).contains(diagnostic),
                () -> "expected '" + diagnostic + "', got: " + failure);
    }

    @Test
    void importedScalarAndStringFieldsOfANamedOwnerFormat() throws Exception {
        assertEquals("7\nx\n7", run("""
                  val b = lib.make();
                  stdio.println(b.n);
                  stdio.println(b.s);
                  stdio.print(b.n, "\\n");
                """));
    }

    @Test
    void annotatedImportedOwnerFormatsItsField() throws Exception {
        assertEquals("7", run("""
                  val lib.Box b = lib.make();
                  stdio.println(b.n);
                """));
    }

    @Test
    void importedFieldOfATemporaryFormats() throws Exception {
        assertEquals("7\nx", run("""
                  stdio.println(lib.make().n);
                  stdio.println(lib.make().s);
                """));
    }

    @Test
    void importedProjectionStillPinsItsOwnerAgainstLaterMoves() {
        assertRejected("""
                  val b = lib.make();
                  stdio.println(b.s, lib.consume(b));
                """, "cannot move 'b' while it is borrowed");
    }

    @Test
    void importedProjectionStillPinsItsOwnerAgainstLaterMutation() {
        assertRejected("""
                  val lib.Box b = lib.make();
                  stdio.println(b.n, lib.edit(rt mut borrow b));
                """, "cannot mutably borrow 'b' while another borrow is active");
    }

    @Test
    void ownerIsReleasedAfterTheFormattingCall() throws Exception {
        assertEquals("7\nedited\n8", run("""
                  val lib.Box b = lib.make();
                  stdio.println(b.n);
                  stdio.println(lib.edit(rt mut borrow b));
                  stdio.println(b.n);
                """));
    }
}
