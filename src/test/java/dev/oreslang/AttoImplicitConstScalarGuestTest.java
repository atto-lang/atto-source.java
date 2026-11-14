package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage-one scalar-only conformance: type-first local == explicit const.
 * In particular, a pure-looking runtime function call is NOT a constant
 * unless the compiler proves its body const-evaluable.
 */
final class AttoImplicitConstScalarGuestTest {
    @TempDir Path temp;

    private static final String HELPER = """
            fnc runtime_value(): int { return 42; }
            """;

    private static void valid(String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                HELPER + "pub fnc main(): int { " + body + " }")));
    }

    private static void rejected(String body, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        HELPER + "pub fnc main(): int { " + body + " }")));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test void bothSpellingRequirePureAndCheckedConstantEvaluation() {
        valid("int base = 40; const int offset = 2; return base + offset;");
        valid("const int base = 40; int offset = 2; return base + offset;");
        valid("const int base = 40; const int offset = base + 2; return offset;");
        valid("bool yes = true; const bool still_yes = yes; return 42;");
        valid("string greeting = \"hello\"; const string same = greeting; return 42;");
    }

    @Test void runtimeInitializerNeedsValOrLet() {
        valid("val int base = runtime_value(); let int offset = 0; "
                + "offset = base - 40; return 40 + offset;");
        rejected("int x = runtime_value(); return x;", "E_NON_CONST_INITIALIZER");
        rejected("const int x = runtime_value(); return x;", "E_NON_CONST_INITIALIZER");
        rejected("bool x = runtime_value() == 42; return 42;", "E_NON_CONST_INITIALIZER");
    }

    @Test void checkedOverflowAndMutableConstantFailClosed() {
        rejected("int x = 9223372036854775807 + 1; return 42;", "E_CONST_OVERFLOW");
        rejected("const int x = 9223372036854775807 + 1; return 42;", "E_CONST_OVERFLOW");
        rejected("const mut int x = 42; return x;", "E_CONST_MUT_UNSUPPORTED");
    }

    @Test void immutableBindingCannotBeReassigned() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                "pub fnc main(): int { int x = 40; x = 42; return x; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                "pub fnc main(): int { const int x = 40; x = 42; return x; }")));
    }

    @Test void realAttoSourceRunsThroughLinkedJavaGuest() throws Exception {
        Path entry = temp.resolve("implicit-const.atto");
        Files.writeString(entry, """
                pub fnc main(): int {
                  int base = 40;
                  const int offset = 2;
                  return base + offset;
                }
                """);
        var result = LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus());
    }
}
