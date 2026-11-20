package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source parser admission only: Java has no checked continuous graph or
 * standalone graceful-stop runtime yet. A bang alias must never become
 * scalar fallback, force-stop, or a silently accepted actor operation. */
final class PipelineStopBangAdmissionTest {

    private static IllegalArgumentException rejected(String body) {
        return assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main(): int { " + body + " return 0; }"));
    }

    @Test void forwardAndReverseBangFormsRetainOriginalFailClosedBoundary() {
        for (String source : new String[] {
                "return 42 pipe 41;",
                "return 42 pipe! 41;",
                "return 42 rpipe 41;",
                "return 42 rpipe! 41;",
                "return 42 pipe! 41 rpipe! 8;",
        }) {
            IllegalArgumentException failure = rejected(source);
            assertTrue(failure.getMessage().contains("E_PIPE_BACKEND_UNSUPPORTED"),
                    () -> source + ": " + failure.getMessage());
        }
    }

    @Test void malformedBangSuffixCannotBeTreatedAsASeparateKeyword() {
        for (String source : new String[] {
                "return 42 pipe!suffix 41;",
                "return 42 rpipe!suffix 41;",
                "return 42 pipe!! 41;",
        }) {
            IllegalArgumentException failure = rejected(source);
            assertTrue(failure.getMessage().contains("E_PIPE_BANG_BOUNDARY"),
                    () -> source + ": " + failure.getMessage());
        }
    }

    @Test void gracefulStopAndStopBangRemainEquallyUnsupported() {
        for (String source : new String[] { "stop worker;", "stop! worker;" }) {
            IllegalArgumentException failure = rejected(source);
            assertTrue(failure.getMessage().contains("E_STOP_BACKEND_UNSUPPORTED"),
                    () -> source + ": " + failure.getMessage());
        }
        for (String source : new String[] { "stop!worker;", "stop!! worker;" }) {
            IllegalArgumentException failure = rejected(source);
            assertTrue(failure.getMessage().contains("E_STOP_BANG_BOUNDARY"),
                    () -> source + ": " + failure.getMessage());
        }
    }

    @Test void ordinaryStringsAndCommentsDoNotCreateBangOperations() {
        assertDoesNotThrow(() -> Parser.parse("""
            pub fnc main(): int {
                // stop! pipe! rpipe! are not executable here.
                val ignored = "pipe! rpipe! stop!";
                return 0;
            }
            """));
    }
}
