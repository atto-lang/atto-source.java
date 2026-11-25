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

final class PipelineAndRecursiveAliasTest {
    @Test
    void pipelineHasDedicatedTokenAndRetainsBitwiseOrAndLambdaSyntax() {
        var tokens = new Lexer("x |> twice | mask; val cb = |int x| -> x;").scan();
        assertTrue(tokens.stream().anyMatch(token -> token.type() == Token.Type.PIPE_FORWARD));
        assertEquals(3, tokens.stream().filter(token -> token.type() == Token.Type.PIPE).count());
    }

    @Test
    void pipelineLowersToOrdinaryCallsAndRunsLeftToRight() throws Exception {
        String program = """
                fnc increment(int value): int {
                  return value + 1;
                }

                fnc multiply(int value, int factor): int {
                  return value * factor;
                }

                define module Math as
                  pub fnc twice(int value): int {
                    return value * 2;
                  }
                end

                pub fnc main(): void {
                  val Fnc<int(int)> square = |int value| -> value * value;
                  stdio.println(1 |> increment |> multiply(3));
                  stdio.println((2 + 3) |> Math.twice);
                  stdio.println(4 |> square);
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("6\n10\n16\n", run(program));
    }

    @Test
    void rejectsPipelinesWithEffectfulComputedCallees() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): void {
                  val result = 1 |> factory()(2);
                  return;
                }
                """));
    }

    @Test
    void guardedRecursiveDataAliasesResolveWithFiniteCompilerTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Node<T> = Option<[T, Node<T>]>;
                type IntNode = Node<int>;
                type A = Option<B>;
                type B = Result<int, A>;
                fnc depth(IntNode root): int {
                  return 0;
                }
                fnc use(A a): B {
                  return Ok(1);
                }
                """)));
    }

    @Test
    void transparentAndNonRegularAliasCyclesFailWithoutStackOverflow() {
        for (String bad : new String[] {
                "type Bad = Bad;",
                "type A = B; type B = A;",
                "type Bad = int | Bad;",
                "type Grow<T> = Option<Grow<List<T>>>;"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(bad)), bad);
        }
    }

    @Test
    void threeModuleAliasesNeverResurrectAnAmbiguousShortName() {
        String declarations = """
                define module first
                    type Shared = int;
                end
                define module second
                    type Shared = bool;
                end
                define module third
                    type Shared = string;
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(declarations + """
                        fnc bad(Shared item): void { return; }
                        """)));
        assertTrue(error.getMessage().contains("ambiguous type alias"), error.getMessage());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(declarations + """
                fnc qualified(first.Shared item): int { return item; }
                """)));
    }

    @Test
    void excessiveAcyclicAliasChainsFailWithControlledTypeDiagnostic() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            source.append("type Alias").append(i).append(" = Alias").append(i + 1).append(";\n");
        }
        source.append("type Alias160 = int;\n");
        var parsed = assertDoesNotThrow(() -> Parser.parse(source.toString()));
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(parsed));
        assertTrue(error.getMessage().contains("E_TYPE_EXPANSION_LIMIT"), error.getMessage());
    }

    @Test
    void branchingAliasExpansionIsBoundedEvenWithoutACycle() {
        StringBuilder source = new StringBuilder("type Leaf0 = int;\n");
        for (int i = 1; i <= 18; i++) {
            source.append("type Leaf").append(i).append(" = struct{left: Leaf")
                    .append(i - 1).append(", right: Leaf").append(i - 1).append("};\n");
        }
        var parsed = assertDoesNotThrow(() -> Parser.parse(source.toString()));
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(parsed));
        assertTrue(error.getMessage().contains("E_TYPE_EXPANSION_LIMIT"), error.getMessage());
    }

    @Test
    void modulesAndClassesCannotBeUsedAsRuntimeValues() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module Maths as
                  pub fnc twice(int x): int { return x * 2; }
                end
                fnc bad(): void {
                  val m = Maths;
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Helper as
                  pub static fnc twice(int x): int { return x * 2; }
                end
                fnc bad(): void {
                  val c = Helper;
                  return;
                }
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pipeline-alias.ores")
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
