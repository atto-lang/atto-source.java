package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class MacroCanonicalIdentityTest {
    private static Ast.MacroDecl macro(String source) {
        Ast.Program program = Parser.parse(source);
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.MacroDecl.class::isInstance)
                .map(Ast.MacroDecl.class::cast)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void formattingDoesNotChangeCanonicalContent() {
        Ast.MacroDecl compact = macro("""
                define pub macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                """);
        Ast.MacroDecl spaced = macro("""
                define   pub   macro identity(
                    Syntax<Expr> value
                ) -> Syntax<Expr>
                {
                    return     value ;
                }
                """);

        assertEquals(
                MacroCanonicalIdentity.encode(compact),
                MacroCanonicalIdentity.encode(spaced));
    }

    @Test
    void parameterIdentityAndIntrinsicBodyAreCanonicalContent() {
        Ast.MacroDecl first = macro("""
                define pub macro reference(Identifier value) -> Syntax<Expr> {
                    return hygienic(value);
                }
                """);
        Ast.MacroDecl renamed = macro("""
                define pub macro reference(Identifier item) -> Syntax<Expr> {
                    return hygienic(item);
                }
                """);
        Ast.MacroDecl captured = macro("""
                define pub macro reference(Identifier value) -> Syntax<Expr> {
                    return capture_fnc(value);
                }
                """);

        assertNotEquals(
                MacroCanonicalIdentity.encode(first),
                MacroCanonicalIdentity.encode(renamed));
        assertNotEquals(
                MacroCanonicalIdentity.encode(first),
                MacroCanonicalIdentity.encode(captured));
    }

    @Test
    void floatingLiteralUsesRawIeeeBits() {
        Ast.MacroParam parameter = new Ast.MacroParam(
                Ast.MacroParamType.syntax(Ast.MacroSyntaxKind.EXPR),
                "value");
        Ast.SourceSite site = new Ast.SourceSite(1, 1);

        Ast.MacroDecl positiveZero = new Ast.MacroDecl(
                "zero",
                Ast.Visibility.PUBLIC,
                List.of(parameter),
                Ast.MacroResultKind.syntax(Ast.MacroSyntaxKind.EXPR),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(+0.0d))),
                site);
        Ast.MacroDecl negativeZero = new Ast.MacroDecl(
                "zero",
                Ast.Visibility.PUBLIC,
                List.of(parameter),
                Ast.MacroResultKind.syntax(Ast.MacroSyntaxKind.EXPR),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(-0.0d))),
                site);

        assertNotEquals(
                MacroCanonicalIdentity.encode(positiveZero),
                MacroCanonicalIdentity.encode(negativeZero),
                "canonical macro identity must preserve raw IEEE-754 signed-zero bits");
    }

    @Test
    void canonicalEncodingFailsClosedOnUnsupportedOrOverdeepBody() {
        Ast.MacroParam parameter = new Ast.MacroParam(
                Ast.MacroParamType.syntax(Ast.MacroSyntaxKind.EXPR),
                "value");
        Ast.SourceSite site = new Ast.SourceSite(1, 1);
        Ast.MacroDecl unsupported = new Ast.MacroDecl(
                "bad",
                Ast.Visibility.PUBLIC,
                List.of(parameter),
                Ast.MacroResultKind.syntax(Ast.MacroSyntaxKind.EXPR),
                List.of(new Ast.ExprStmt(new Ast.NameExpr("value"))),
                site);

        IllegalArgumentException unsupportedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroCanonicalIdentity.encode(unsupported));
        assertTrue(unsupportedFailure.getMessage().contains(
                "cannot be canonically encoded"), unsupportedFailure::toString);

        Ast.Expr nested = new Ast.NameExpr("value");
        for (int i = 0; i < MacroCanonicalIdentity.MAX_DEPTH + 2; i++) {
            nested = new Ast.UnaryExpr("+", nested);
        }
        Ast.MacroDecl overdeep = new Ast.MacroDecl(
                "deep",
                Ast.Visibility.PUBLIC,
                List.of(parameter),
                Ast.MacroResultKind.syntax(Ast.MacroSyntaxKind.EXPR),
                List.of(new Ast.ReturnStmt(nested)),
                site);

        IllegalArgumentException depthFailure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroCanonicalIdentity.encode(overdeep));
        assertTrue(depthFailure.getMessage().contains("depth exceeds"),
                depthFailure::toString);
    }
}
