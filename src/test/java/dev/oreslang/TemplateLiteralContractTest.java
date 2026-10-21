package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TemplateLiteralContractTest {
    @Test
    void quotesStayDataAndInterpolatedParametersExecute() throws Exception {
        String program = """
                fnc render(string bar, string star): string {
                  return `${bar} is ${star}`;
                }
                pub fnc main(): void {
                  const foo1 = "abc";
                  const foo2 = "'abc'";
                  const foo3 = '"abc"';
                  const foo4 = `'"abc"'`;
                  stdio.stdout.write(foo1 + "|" + foo2 + "|" + foo3 + "|" + foo4);
                  stdio.stdout.write("|" + render("Mars", "bright"));
                  return;
                }
                """;
        assertEquals("abc|'abc'|\"abc\"|'\"abc\"'|Mars is bright", run(program));
    }

    @Test
    void escapedDollarAndBacktickAreLiteralData() throws Exception {
        String program = """
                pub fnc main(): void {
                  const word = "value";
                  stdio.stdout.write(`pre${word}post`);
                  stdio.stdout.write("|");
                  stdio.stdout.write(`escaped \\${notInterpolated} and \\` and \\\\`);
                  return;
                }
                """;
        assertEquals("prevaluepost|escaped ${notInterpolated} and " + (char) 0x60 + " and \\", run(program));
    }

    @Test
    void constFncShorthandAcceptsTemplateReturningLambda() {
        String source = """
                const fnc foo = |string bar, string star| -> {
                  return `${bar} is ${star}`;
                };
                pub fnc main(): void {
                  const fnc local = |string left, string right| -> {
                    return `${left} and ${right}`;
                  };
                  return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "pub fnc main(): void { const fnc invalid = 42; return; }"));
    }

    @Test
    void localConstFncTemplateCanBeTypecheckedAndInvoked() throws Exception {
        String program = """
                pub fnc main(): void {
                  const fnc foo = |string bar, string star| -> {
                    return `${bar} is ${star}`;
                  };
                  stdio.stdout.write(foo("Mars", "bright"));
                  return;
                }
                """;
        assertEquals("Mars is bright", run(program));
    }

    @Test
    void interpolationMustBeCompleteExpressionWithStringType() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main(): void { const bad = `${1; 2}`; return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main(): void { const bad = `${ }`; return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> new Lexer("const bad = `${x};").scan());
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        "pub fnc main(): void { const bad = `${5}`; return; }")));
    }

    @Test
    void quotedBracesInsideInterpolationDoNotCloseIt() throws Exception {
        String program = """
                pub fnc main(): void {
                  stdio.stdout.write(`${ "}" + "{" }`);
                  return;
                }
                """;
        assertEquals("}{", run(program));
        var tokens = new Lexer("`${ \"}\" + \"{\" }`").scan();
        assertEquals(Token.Type.TEMPLATE_LITERAL, tokens.getFirst().type());
        assertEquals(Token.Type.EOF, tokens.get(1).type());
    }

    @Test
    void templateInterpolationRunsOnceInLeftToRightOrder() throws Exception {
        String program = """
                fnc first(): string {
                  stdio.stdout.write("1");
                  return "A";
                }
                fnc second(): string {
                  stdio.stdout.write("2");
                  return "B";
                }
                pub fnc main(): void {
                  stdio.stdout.write(`${first()}-${second()}`);
                  return;
                }
                """;
        assertEquals("12A-B", run(program));
    }

    @Test
    void nestedTemplatesRemainExpressionsAndQuoteBracesDoNotEscape() throws Exception {
        String program = """
                pub fnc main(): void {
                  const word = "ok";
                  stdio.stdout.write(`before:${ `inside:${word}` }:after`);
                  return;
                }
                """;
        assertEquals("before:inside:ok:after", run(program));
    }

    @Test
    void rejectsExcessiveInterpolationNestingAndCounts() {
        String many = "${\"x\"}".repeat(1025);
        String source = "pub fnc main(): void { const x = `" + many + "`; return; }";
        IllegalArgumentException count = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(count.getMessage().contains("interpolation count limit"), count::getMessage);

        String deep = "{".repeat(257) + "\"x\"" + "}".repeat(257);
        String deeplyNested = "pub fnc main(): void { const x = `${" + deep + "}`; return; }";
        IllegalArgumentException depth = assertThrows(IllegalArgumentException.class,
                () -> new Lexer(deeplyNested).scan());
        assertTrue(depth.getMessage().contains("brace nesting limit"), depth::getMessage);
    }

    private static String run(String program) throws Exception {
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "template-contract.ores")
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
