package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionDomainAdmissionTest {
    @Test
    void executionDomainsAreDedicatedTokens() {
        List<Token.Type> actual = new Lexer("gpu cpu quantum").scan().stream()
                .map(Token::type).toList();
        assertEquals(List.of(Token.Type.GPU, Token.Type.CPU, Token.Type.QUANTUM,
                Token.Type.EOF), actual);
    }

    @Test
    void unsupportedDomainSyntaxAlwaysRejectsWithoutHostFallback() {
        List<String> sources = List.of(
                "pub gpu fnc kernel(): int { return 1; }",
                "pub fnc main(): int { gpu { return 1; } return 0; }",
                "pub fnc main(): int { gpu { cpu { return 1; } } return 0; }",
                "pub fnc main(): int { cpu { gpu { return 1; } } return 0; }",
                "pub fnc main(): int { return gpu; }",
                "pub fnc gpu(): int { return 1; }",
                "pub fnc main(): int { let gpu = 1; return 0; }",
                "pub quantum fnc kernel(): int { return 1; }"
        );
        for (String source : sources) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("execution domain"), error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"),
                    error::getMessage);
        }
    }

    @Test
    void canonicalAndLegacyTaskDomainBlocksRemainParserVisible() {
        // Parse admission alone does not authorize a GPU device launch.
        // The GPU task remains rejected by the type checker on this backend.
        List<String> sources = List.of(
                "pub fnc main(): int { let Future<int> f = cpu task { return 42; }; return 0; }",
                "pub fnc main(): int { let Future<int> f = gpu task { return 42; }; return 0; }",
                "pub fnc main(): int { let Future<int> f = nb cpu { return 42; }; return 0; }",
                "pub fnc main(): int { let Future<int> f = nb gpu { return 42; }; return 0; }"
        );
        for (String source : sources) {
            assertDoesNotThrow(() -> Parser.parse(source), source);
        }
    }

    @Test
    void gpuTasksNeverReachHostTypeChecking() {
        // A parser-visible Task is not permission to dispatch a GPU kernel.
        // Both source spellings must be rejected *before* host execution.
        for (String spelling : List.of(
                "gpu task { return 42; }", "nb gpu { return 42; }")) {
            String source = "pub fnc main(): int { let Future<int> pending = "
                    + spelling + "; return 0; }";
            var program = assertDoesNotThrow(() -> Parser.parse(source));
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(program), source);
            assertTrue(error.getMessage().contains("physical GPU dispatch"),
                    error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"),
                    error::getMessage);
        }
    }

    @Test
    void nestedSynchronousRegionsStillFailClosedInsideTaskBlocks() {
        List<String> sources = List.of(
                "pub fnc main(): int { gpu {} return 0; }",
                "pub fnc main(): int { let Future<int> f = cpu task { gpu { return 1; } }; return 0; }"
        );
        for (String source : sources) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("synchronous execution domain"),
                    error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"),
                    error::getMessage);
        }
    }

    @Test
    void scalarCpuAdmissionRejectsUnloweredStatementsAndSideEffects() {
        for (String body : List.of(
                "", "let int x = 1; return x;",
                "some_function()", "await some_future", "rt take owner")) {
            String source = "pub fnc main(): int { return cpu { " + body + " }; }";
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
        }
    }

    @Test
    void scalarCpuPreflightRejectsMismatchedParenthesesAtTheCorrectGate() {
        for (String body : List.of("1)", "(1", "((1)", "(1))")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc main():int{return cpu { "
                            + body + " };}"));
            assertTrue(error.getMessage().contains("scalar cpu region"),
                    () -> "expected scalar-region diagnostic: " + error.getMessage());
        }
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main():int{return cpu { (40 + 2) };}"));
    }

    @Test
    void scalarCpuPreflightLimitsUnaryRecursionBeforeBuildingTheAst() {
        String prefix = "pub fnc main():int{return cpu { ";
        String suffix = "1 };}";
        // A 64-operator unary chain is within the AST depth budget;
        // 65 operators must fail at lexical preflight, not after recursion.
        assertDoesNotThrow(() -> Parser.parse(prefix + "-".repeat(64) + suffix));
        for (String op : List.of("-", "+", "!")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(prefix + op.repeat(65) + suffix));
            assertTrue(error.getMessage().contains("unary operator depth budget"),
                    () -> "wrong failure gate for " + op + ": " + error.getMessage());
        }
    }

    @Test
    void scalarCpuTokenBudgetCountsTheClosingBraceWithoutOffByOne() {
        String start = "pub fnc main():int{return cpu { ";
        String end = " };}";
        // 512 constants = 1,023 arithmetic tokens; adding the closing
        // RBRACE is precisely 1,024 tokens. This passes lexical admission
        // and fails the separate scalar AST depth/node budget.
        String atLimit = "1" + " + 1".repeat(511);
        IllegalArgumentException astBudget = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(start + atLimit + end));
        assertTrue(astBudget.getMessage().contains("cpu scalar execution region"),
                astBudget::getMessage);

        // 513 constants = 1,025 arithmetic tokens, so lexing must reject
        // before parsing the oversized scalar expression.
        String tooMany = "1" + " + 1".repeat(512);
        IllegalArgumentException tokenBudget = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(start + tooMany + end));
        assertTrue(tokenBudget.getMessage().contains("scalar cpu region exceeds token budget"),
                tokenBudget::getMessage);
    }

    @Test
    void scalarCpuRequiresTheCompilerCanonicalSignedI64LiteralRepresentation() {
        // The parser and type checker use Long for Oreslang int.
        assertDoesNotThrow(() -> new Ast.CpuScalarRegionExpr(new Ast.LiteralExpr(42L)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CpuScalarRegionExpr(new Ast.LiteralExpr(42)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CpuScalarRegionExpr(new Ast.LiteralExpr(42.0)));
    }

    @Test
    void commentsStringsAndNonKeywordsStayOrdinarySource() {
        assertDoesNotThrow(() -> Parser.parse("""
                // gpu { cpu { quantum } }
                pub fnc compute_gpu(): int {
                    /* gpu { cpu {} } */
                    const message = "gpu cpu quantum";
                    return 42;
                }
                """));
    }
}
