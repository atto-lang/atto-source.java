package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Native guest proof for issue #3041: borrowed calls complete once, never twice. */
final class BorrowedSourceCallCompletionTest {
    @Test
    void borrowedClassCallsExecuteAndPublishOnce() throws Exception {
        String program;
        try (InputStream input = getClass().getResourceAsStream(
                "/borrowed-source-call-once.atto")) {
            assertNotNull(input, "borrowed source call fixture is missing");
            program = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "borrowed-source-call-once.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .build()) {
            assertDoesNotThrow(() -> context.eval(source),
                    "a borrowed call must not publish its terminal continuation twice");
        }
        assertEquals("native borrowed call delivery passed",
                stdout.toString(StandardCharsets.UTF_8).trim(),
                "borrowed scalar and delegated calls must return correct values");
    }

    @Test
    void duplicateExclusiveBorrowArgumentsRemainRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Cell as
                          pub let int value = 1;
                        end
                        fnc write_pair(borrow mut Cell a, borrow mut Cell b): int {
                          return a.value + b.value;
                        }
                        fnc illegal(): int {
                          val Cell cell = new Cell();
                          return write_pair(rt mut borrow cell, rt mut borrow cell);
                        }
                        pub fnc main(): int { return illegal(); }
                        """));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"),
                failure.getMessage());
    }
}
