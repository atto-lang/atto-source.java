package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical return-first header/body agreement with atto-spec #151 and
 * atto-platform-conformance #242. Parser checks are not guest-execution proofs.
 */
final class ReturnFirstBulletBodyParityTest {
    private static Ast.FunctionDecl firstFunction(String source) {
        Ast.Program program = Parser.parse(source);
        return assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    @Test
    void arrowAndBulletHaveIdenticalFunctionSignaturesAndBodyAst() {
        String[] headers = {
                "fnc int <- main()", "fnc int • main()",
                "int <- main()", "int• main()"
        };
        String[] bodies = {
                "{ return 42; }",
                "begin return 42; end",
                "return 42; end",
                "::\n return 42;\nend",
                "begin\n return 42;\nend.",
                "begin\n return 42;\nend;"
        };
        for (String header : headers) {
            for (String body : bodies) {
                String source = header + " " + body;
                Ast.FunctionDecl fnc = firstFunction(source);
                assertEquals("main", fnc.name(), source);
                assertEquals("int", fnc.returnType().name(), source);
                assertEquals(1, fnc.body().size(), source);
                assertInstanceOf(Ast.ReturnStmt.class, fnc.body().getFirst(), source);
            }
        }
    }

    @Test
    void arrowAndBulletMethodBodyVariantsPreserveTypedMethodAst() {
        for (String header : new String[] {
                "int <- calculate(int x)", "int• calculate(int x)",
                "fnc int <- calculate(int x)", "fnc int • calculate(int x)"
        }) {
            for (String body : new String[] {
                    "{ return x * 2; }",
                    "begin return x * 2; end",
                    "return x * 2; end",
                    "::\n return x * 2;\nend"
            }) {
                String source = "define class C as " + header + " " + body + " end";
                Ast.Program program = Parser.parse(source);
                Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                        program.modules().getFirst().declarations().getFirst());
                assertEquals(1, klass.methods().size(), source);
                Ast.MethodDecl method = klass.methods().getFirst();
                assertEquals("calculate", method.name(), source);
                assertEquals("int", method.returnType().name(), source);
                assertInstanceOf(Ast.ReturnStmt.class, method.body().getFirst(), source);
            }
        }
    }

    @Test
    void splitArrowLookalikesAndDoubleResultFailClosed() {
        for (String header : new String[] {
                "int < - main()", "int · main()", "int ∙ main()",
                "int • main() -> int", "int <- main(): int"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(header + " { return 42; }"), header);
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("int • main() begin return 42;"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("int • main() { return 42; "));
    }

    @Test
    void plainEndAndExplicitBeginDoNotInventImplicitReturn() {
        for (String header : new String[] { "int <- main()", "int • main()" }) {
            Ast.FunctionDecl explicit = firstFunction(header + " begin 42; end");
            Ast.FunctionDecl bare = firstFunction(header + " 42; end");
            assertFalse(explicit.body().getFirst() instanceof Ast.ReturnStmt);
            assertFalse(bare.body().getFirst() instanceof Ast.ReturnStmt);
        }
    }

    @Test
    void arrowAndBulletReturnFirstCallablesActuallyExecuteGuest() throws Exception {
        String program = """
                int <- arrow_brace(int x) { return x + 1; }
                fnc int • bullet_begin(int x) begin return x + 1; end
                int• bullet_bare(int x) return x + 1; end
                fnc int <- arrow_inline_begin(int x) begin return x + 1; end
                fnc int • bullet_colon(int x) ::
                    return x + 1;
                end
                pub fnc main(): void {
                    stdio.stdout.write(arrow_brace(41));
                    stdio.stdout.write("|");
                    stdio.stdout.write(bullet_begin(41));
                    stdio.stdout.write("|");
                    stdio.stdout.write(bullet_bare(41));
                    stdio.stdout.write("|");
                    stdio.stdout.write(arrow_inline_begin(41));
                    stdio.stdout.write("|");
                    stdio.stdout.write(bullet_colon(41));
                    return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(Source.newBuilder(OresLanguage.ID, program, "return-first-bullet.atto")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        assertEquals("42|42|42|42|42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void checkedReturnFirstEffectHeadersRequireAnExplicitBodyBoundary() {
        for (String header : new String[] {
                "fnc int <- twice(int x)\nraises Foo",
                "fnc int • twice(int x)\nthrows Foo",
                "int• twice(int x) raises Foo"
        }) {
            for (String boundary : new String[] { "begin\n", "::\n" }) {
                String program = header + "\n" + boundary + "return x * 2;\nend";
                Ast.FunctionDecl fn = firstFunction(program);
                assertEquals("int", fn.returnType().name(), program);
                assertEquals(1, fn.body().size(), program);
                assertFalse(fn.effects().isEmpty(), program);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(header + "\nreturn x * 2;\nend"),
                    "unmarked effects must not consume the body");
        }
        for (String source : new String[] {
                "int <- twice(int x) :: return x * 2; end",
                "int <- twice(int x) ::\n{ return x * 2; }"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void effectCommasSeparateKindsButNotGenericOrNominalTypeLists() {
        for (String header : new String[] {
                "throws Foo, raises Bar",
                "throws Foo raises Bar",
                "throws Foo, ParseError raises Bar",
                "raises Foo, panics Fatal"
        }) {
            String program = "fnc int <- risky(int x)\n" + header
                    + " ::\n  return x;\nend";
            Ast.FunctionDecl fn = firstFunction(program);
            assertEquals("int", fn.returnType().name(), program);
            assertEquals(1, fn.body().size(), program);
            assertFalse(fn.effects().isEmpty(), program);
        }
        Ast.FunctionDecl split = firstFunction(
                "fnc int <- risky(int x)\nthrows Foo, ParseError, raises Bar ::\n  return x;\nend");
        assertEquals(2, split.effects().throwsTypes().size());
        assertEquals(1, split.effects().raisesTypes().size());

        for (String header : new String[] {
                "throws Foo, throws Bar",
                "raises Bar, raises Foo",
                "throws Foo, Foo",
                "throws Foo,",
                "raises Foo, ::"
        }) {
            String program = "fnc int <- risky(int x)\n" + header
                    + " ::\n  return x;\nend";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(program), program);
        }
    }

    @Test
    void nominalErrorInheritanceAndEffectIdentitySurviveJavaParsing() {
        // Signature/AST parity only, NOT JVM/AOT exception guest parity.
        String program = """
                fnc int <- twice(int value)
                raises Foo ::
                  return value * 2;
                end
                define class Foo extends Error as
                end
                pub fnc main(): int { return twice(21); }
                """;
        Ast.Program parsed = Parser.parse(program);
        Ast.FunctionDecl function = assertInstanceOf(Ast.FunctionDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        Ast.ClassDecl foo = assertInstanceOf(Ast.ClassDecl.class,
                parsed.modules().getFirst().declarations().get(1));
        assertEquals("Foo", foo.name());
        assertEquals("Error", foo.parents().getFirst().name());
        assertEquals("Foo", function.effects().raisesTypes().getFirst().name());
        assertEquals("int", function.returnType().name());
    }

    @Test
    void exceptionalRaiseInJavaMustRejectUntilRuntimeLoweringExists() {
        String program = """
                fnc int <- risky(int x)
                raises Foo ::
                  raise! new Foo();
                end
                pub fnc main(): int { return risky(4); }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program),
                "Java cannot publish a runnable raise! guest without typed unwind/CONTROL lowering");
    }

    @Test
    void mixedArrowAndBulletNamesAreNotSeparateOverloads() {
        String source = """
                int <- compute(int x) { return x; }
                int • compute(int x) { return x; }
                pub fnc main(): int { return compute(42); }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)),
                "the marker cannot disambiguate duplicate callable signatures");
    }
}
