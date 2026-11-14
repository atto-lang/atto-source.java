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

    @Test void exactSharedSourcesHaveIdenticalCompilerBehavior() throws Exception {
        // All eight blobs are independently checked against LLVM and .NET.
        for (String name : new String[] {
                "literal-equivalence", "const-arithmetic", "bool-local", "runtime-val-let", "const-reference"
        }) {
            Path fixture = Path.of("contracts/implicit-const-scalar-v1/" + name + ".atto");
            assertTrue(Files.isRegularFile(fixture), name);
            var compiled = LinkedProgramRunner.validate(fixture);
            assertNotNull(compiled);
            var result = LinkedProgramRunner.runWithExitStatus(
                    fixture, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, result.exitStatus(), name);
        }
        for (String name : new String[] {
                "implicit-nonconst", "explicit-nonconst",
                "const-mut-scalar", "const-overflow", "implicit-rebind", "explicit-rebind"
        }) {
            Path fixture = Path.of("contracts/implicit-const-scalar-v1/" + name + ".atto");
            assertTrue(Files.isRegularFile(fixture), name);
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> LinkedProgramRunner.validate(fixture), name);
            String expected = switch (name) {
                case "implicit-nonconst", "explicit-nonconst" -> "E_NON_CONST_INITIALIZER";
                case "const-mut-scalar" -> "E_CONST_MUT_UNSUPPORTED";
                case "const-overflow" -> "E_CONST_OVERFLOW";
                case "implicit-rebind", "explicit-rebind" -> "E_IMMUTABLE_REBIND";
                default -> throw new AssertionError("unrecognized fixture");
            };
            assertTrue(error.getMessage().contains(expected),
                    name + ": unexpected diagnostic: " + error.getMessage());
        }
    }

    @Test void exponentialConstantStringExpansionCannotExhaustCompilerMemory() {
        var source = new StringBuilder("pub fnc main(): int { string s0 = \"x\"; ");
        for (int i = 1; i <= 21; i++) {
            source.append("string s").append(i).append(" = s")
                    .append(i - 1).append(" + s").append(i - 1).append("; ");
        }
        source.append("return 42; }");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source.toString())));
        assertTrue(error.getMessage().contains("E_CONST_EVAL_BUDGET"),
                "exponential expansion must stop at a deterministic budget: " + error.getMessage());
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
