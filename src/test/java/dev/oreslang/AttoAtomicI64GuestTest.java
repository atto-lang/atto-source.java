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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed AttoLang guest smoke and negative tests.
 * Passing these does not prove native-image AOT or cross-actor sharing.
 */
final class AttoAtomicI64GuestTest {
    @Test
    void actualGuestAtomicI64OperationsRunThroughCompiler() throws Exception {
        String code = Files.readString(
                Path.of("tests/fixtures/atomic-v1/atomic_i64_guest.atto"),
                StandardCharsets.UTF_8);
        String expected = Files.readString(
                Path.of("tests/fixtures/atomic-v1/atomic_i64_guest.expected"),
                StandardCharsets.UTF_8);
        TypeChecker.check(Parser.parse(code));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "atomic-i64-guest.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        assertEquals(expected, stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void compileRejectsInvalidAtomicElementAndConstructorTypes() {
        assertRejects("val c: Atomic<String> = Atomic.new(0);", "Atomic<T>");
        assertRejects("val c: Atomic<int> = Atomic.new(true);", "Atomic.new");
        assertRejects("val c: Atomic<int> = Atomic.new(0); c.store(true);", "argument");
        assertRejects("val c: Atomic<int> = Atomic.new(0); c.fetch_add(1.5);", "argument");
    }

    @Test
    void compileRejectsUnknownAtomicOperationsAndFactoryShadowing() {
        assertRejects("val c: Atomic<int> = Atomic.new(0); c.fetch_div(2);",
                "unknown Atomic member");
        assertRejects("val Atomic: int = 0; val c: Atomic<int> = Atomic.new(0);",
                "Atomic factory cannot be shadowed");
    }

    private static void assertRejects(String statements, String diagnostic) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        "pub fnc main(): void { " + statements + " return; }")));
        assertTrue(failure.getMessage().contains(diagnostic),
                () -> "Expected diagnostic '" + diagnostic + "' but was: " + failure);
    }
}
