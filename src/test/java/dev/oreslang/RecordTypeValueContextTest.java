package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Type/value grammar boundary for record aggregates.
 *
 * A named type declaration and a value initializer may both use `record {...}`.
 * The surrounding syntactic position, never the spelling of a field payload,
 * determines which grammar is active.
 */
final class RecordTypeValueContextTest {
    private static List<Ast.Decl> declarations(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream()).toList();
    }

    @Test
    void aTypeAliasIsNotARecordValueAndARecordValueIsNotATypeAlias() {
        String source = """
                type T = record { foo: string };
                pub fnc make(string value): T {
                    const T x = record { foo: value };
                    return x;
                }
                """;
        List<Ast.Decl> declarations = declarations(source);
        assertInstanceOf(Ast.TypeAliasDecl.class, declarations.get(0));
        Ast.TypeAliasDecl alias = (Ast.TypeAliasDecl) declarations.get(0);
        assertTrue(alias.target().isRecordType());
        assertEquals("string", alias.target().recordMembers().get("foo").name());

        Ast.FunctionDecl function = (Ast.FunctionDecl) declarations.get(1);
        assertEquals("T", function.returnType().name());
        Ast.BindingStmt x = (Ast.BindingStmt) function.body().get(0);
        assertEquals("T", x.declaredType().name());
        assertNull(x.inferredTypeName(), "plain record must not manufacture a type alias");
        Ast.ObjectExpr literal = assertInstanceOf(Ast.ObjectExpr.class, x.initializer());
        assertEquals(Ast.ObjectKind.RECORD, literal.kind());
        assertInstanceOf(Ast.NameExpr.class, literal.fields().get(0).value());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void inlineReturnTypesAndLiteralFieldsUseDifferentGrammars() {
        String source = """
                pub fnc make(): record { foo: string } {
                    return record { foo: "hello" };
                }
                """;
        Ast.FunctionDecl function = (Ast.FunctionDecl) declarations(source).getFirst();
        assertTrue(function.returnType().isRecordType());
        Ast.ReturnStmt returned = (Ast.ReturnStmt) function.body().getFirst();
        Ast.ObjectExpr literal = assertInstanceOf(Ast.ObjectExpr.class, returned.value());
        assertEquals(Ast.ObjectKind.RECORD, literal.kind());
        assertInstanceOf(Ast.LiteralExpr.class, literal.fields().getFirst().value());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void explicitInferenceCapturesLocalStructuralAliasWithoutATypeStatement() {
        String source = """
                fnc make(): string {
                    const Row first = infer<Row> record { foo: "hello" };
                    const Row second = record { foo: "world" };
                    return second.foo;
                }
                """;
        Ast.FunctionDecl function = (Ast.FunctionDecl) declarations(source).getFirst();
        Ast.BindingStmt first = (Ast.BindingStmt) function.body().get(0);
        assertEquals("Row", first.inferredTypeName());
        Ast.BindingStmt second = (Ast.BindingStmt) function.body().get(1);
        assertNull(second.inferredTypeName());
        assertEquals(Ast.ObjectKind.RECORD,
                ((Ast.ObjectExpr) second.initializer()).kind());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void aliasMarkerInValueBindingRemainsLegal() {
        String source = """
                type T = record { foo: string };
                fnc make(): T {
                    const type T x = record { foo: "hello" };
                    return x;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void constTypeDeclarationIsRejectedWithAnExplicitDiagnostic() {
        for (String source : List.of(
                "const type T = record { foo: string };",
                "fnc make(): void { const type T = record { foo: string }; return; }")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source));
            assertTrue(error.getMessage().contains("'const type' is not a declaration"),
                    error.getMessage());
        }
    }

    @Test
    void runtimeNamesCannotBecomeFieldTypesAndTypeNamesCannotBecomeFieldValues() {
        String invalidType = """
                type T = record { foo: runtimeName };
                fnc make(string runtimeName): void { return; }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalidType)));

        String invalidValue = """
                type T = record { foo: string };
                fnc make(): T {
                    const T x = record { foo: T };
                    return x;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalidValue)));
    }

    @Test
    void plainRecordDoesNotCreateAnUndeclaredAlias() {
        String source = """
                fnc make(): string {
                    const NotDeclared x = record { foo: "hello" };
                    return x.foo;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }
}
