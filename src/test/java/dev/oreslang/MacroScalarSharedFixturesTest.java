package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The source resource bytes are the same five .atto vectors as LLVM PR #1026
 * and .NET PR #346. These tests execute the Java frontend/type checker, NOT
 * GraalVM Native Image, a linked guest, or the other backends.
 */
final class MacroScalarSharedFixturesTest {
    private static String fixture(String name) {
        String path = "/macro-scalar-v1/" + name;
        try (var stream = MacroScalarSharedFixturesTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing cross-backend fixture: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Ast.Expr expandedReturn(String source) {
        Ast.Program program = OresCompiler.parseAndTypeCheck(source);
        Ast.FunctionDecl function = program.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, function.body().getFirst());
        return returned.value();
    }

    @Test
    void sharedPositiveTemplatesEmitDistinctIntegerLiterals() {
        for (String fixtureName : new String[]{
                "template-twice-42.atto",
                "template-twice-nested-42.atto"
        }) {
            Ast.BinaryExpr result = assertInstanceOf(
                    Ast.BinaryExpr.class, expandedReturn(fixture(fixtureName)),
                    fixtureName);
            assertEquals("+", result.operator(), fixtureName);
            assertEquals(21L,
                    assertInstanceOf(Ast.LiteralExpr.class, result.left()).value());
            assertEquals(21L,
                    assertInstanceOf(Ast.LiteralExpr.class, result.right()).value());
            assertNotSame(result.left(), result.right(),
                    "repeated unquote may not share one compiler AST identity");
        }
    }

    @Test
    void sharedUnsafeTemplateVectorsRejectBeforeExecutableLowering() {
        for (String fixtureName : new String[]{
                "template-twice-unresolved-name-reject.atto",
                "template-twice-composite-reject.atto"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(fixture(fixtureName)),
                    fixtureName);
            assertTrue(error.getMessage().contains("repeated unquote")
                            || error.getMessage().contains("duplicate effects or ownership transfers"),
                    () -> fixtureName + ": " + error);
        }
    }

    @Test
    void sharedOverflowFixtureIsParserInputNotYetNativeExecutionEvidence() {
        // Parsing the same source bytes is NOT proof that checked overflow is
        // rejected by this Java compiler or GraalVM guest. That behavioral
        // parity remains a separate executable gate.
        assertDoesNotThrow(() -> Parser.parse(
                fixture("template-twice-overflow-reject.atto")));
    }
}
