package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Native Oreslang language parsing/type-and-ownership contracts, not a host arithmetic oracle. */
final class ImmutableDefaultScalarLocalTest {
    private static String program(String statements) {
        return "pub fnc main(): int { " + statements + " }";
    }

    private static void accepted(String statements) {
        assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse(program(statements))),
                statements);
    }

    private static void rejected(String statements) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program(statements))),
                statements);
    }

    @Test void builtinTypeFirstAndExistingConstAreReadonly() {
        // The equality here is the static observable value for integer 7;
        // it must not redefine arbitrary standalone const as runtime val.
        for (String body : new String[] {
                "int x = 7; return x;",
                "const x = 7; return x;",
                "const int x = 7; return x;",
                "const x: int = 7; return x;",
                "int x = 2 + 5; return x;",
                "int x = 7; int y = x * 3; return y;",
                "bool flag = true; return 7;",
                "string label = \"hello\"; return 7;",
                "int x = -9223372036854775808; return 7;"
        }) accepted(body);
    }

    @Test void implicitScalarLocalNeverGrantsRebindingOrMutableBorrow() {
        for (String body : new String[] {
                "int x = 7; x = 8; return x;",
                "int x = 7; x += 1; return x;",
                "bool flag = true; flag = false; return 7;",
                "string label = \"hi\"; label = \"bye\"; return 7;",
                "int x = 7; rt mut borrow x; return x;",
                "int x = 7; int x = 8; return x;",
                "int x = 7; const x = 8; return x;"
        }) rejected(body);
    }

    @Test void checksTypesAndInitializersWithoutImplicitConversion() {
        for (String body : new String[] {
                "int x = true; return 7;",
                "bool flag = 7; return 7;",
                "string label = 7; return 7;",
                "int x = unknown_name; return 7;",
                "int x = x + 1; return 7;",
                "int x = other; int other = 7; return 7;",
                "int x; return 7;",
                "int x = 7; return unknown_name;"
        }) rejected(body);
    }

    @Test void runtimeHelperInitializerKeepsReadonlyBindingSeparateFromConstValueProof()
            throws Exception {
        // Normative common-source #203: local const is a readonly binding,
        // not a requirement that its initializer be compile-time evaluable.
        // All three forms must actually execute the real helper in Graal.
        for (String declaration : new String[] {
                "int computed = increment(41);",
                "const computed = increment(41);",
                "const int computed = increment(41);"
        }) {
            String source = "fnc increment(int p): int { return p + 1; } "
                    + "pub fnc main(): void { " + declaration
                    + " stdio.println(computed); return; }";
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)),
                    declaration);
            var program = Source.newBuilder(OresLanguage.ID, source,
                    "readonly-runtime-helper.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                assertDoesNotThrow(() -> context.eval(program), declaration);
            }
            assertEquals("42" + System.lineSeparator(),
                    output.toString(StandardCharsets.UTF_8), declaration);
        }
    }

    @Test void actualGraalGuestRunsImmutableDefaultBindings() throws Exception {
        // Unlike check-only assertions, each case evaluates Oreslang guest
        // source through the real Truffle language, capturing actual output.
        for (String declaration : new String[] {
                "int x = 7;",
                "const x = 7;",
                "const int x = 7;",
                "const x: int = 7;",
                "int x = 2 + 5;"
        }) {
            String source = "pub fnc main(): void { " + declaration
                    + " stdio.println(x); return; }";
            var program = Source.newBuilder(OresLanguage.ID, source,
                    "immutable-default-guest.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                assertDoesNotThrow(() -> context.eval(program), declaration);
            }
            assertEquals("7" + System.lineSeparator(),
                    output.toString(StandardCharsets.UTF_8),
                    "guest value mismatch for " + declaration);
        }
    }

    @Test void readonlyAssignmentParsesBeforeSemanticRejection() {
        // Failing the lexer/parser is not evidence that the compiler resolved
        // an immutable binding and correctly denied its reassignment.
        for (String body : new String[] {
                "int x = 7; x = 8; return x;",
                "const x = 7; x = 8; return x;",
                "const int x = 7; x = 8; return x;",
                "bool flag = true; flag = false; return 7;"
        }) {
            var ast = assertDoesNotThrow(() -> Parser.parse(program(body)),
                    "readonly negative must pass parsing: " + body);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(ast),
                    "readonly reassignment must fail at semantic checking: " + body);
        }
    }

    @Test void actualGraalGuestMustExecuteExplicitMutableLetPositiveControl()
            throws Exception {
        // A compiler that rejects *all* assignment statements can falsely
        // appear to enforce immutable defaults. This positive control must
        // really execute the mutation and print 8 via the Graal guest.
        String source = "pub fnc main(): void { "
                + "let int x = 7; x = 8; stdio.println(x); return; }";
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        var program = Source.newBuilder(OresLanguage.ID, source,
                "mutable-let-positive-control.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertDoesNotThrow(() -> context.eval(program));
        }
        assertEquals("8" + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test void explicitMutableLetSemanticsRemainSeparate() {
        accepted("let int x = 7; x = 8; return x;");
        rejected("const int x = 7; x = 8; return x;");
        // Existing lexical function calls must not be reclassified as
        // declarations merely because the callee resembles a type.
        rejected("int(7); return 7;");
        rejected("int missing = true; return 7;");
    }
}
