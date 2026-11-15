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
    void aliasMarkerInValueBindingIsRedundantInsideCallables() {
        // Main (79a839368a) rejects every 'const type' spelling inside a
        // callable as redundant; the plain named binding is the legal form.
        String marker = """
                type T = record { foo: string };
                fnc make(): T {
                    const type T x = record { foo: "hello" };
                    return x;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(marker));
        assertTrue(error.getMessage().contains("'const type' is redundant"), error.getMessage());

        String plain = """
                type T = record { foo: string };
                fnc make(): T {
                    const T x = record { foo: "hello" };
                    return x;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(plain));
    }

    @Test
    void constTypeDeclarationIsRejectedWithAnExplicitDiagnostic() {
        for (String source : List.of(
                "const type T = record { foo: string };",
                "fnc make(): void { const type T = record { foo: string }; return; }")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source));
            // Module scope: this PR's diagnostic. Callable scope: main's
            // earlier block-level "'const type' is redundant" (79a839368a).
            assertTrue(error.getMessage().contains("'const type' is not a declaration")
                            || error.getMessage().contains("'const type' is redundant"),
                    error.getMessage());
        }
    }

    @Test
    void runtimeNamesCannotBecomeFieldTypesAndTypeNamesCannotBecomeFieldValues() {
        // Main resolves type alias bodies lazily (an unused alias is not
        // validated), so exercise the alias: a runtime parameter name used
        // as a field type can never be satisfied by a value.
        String invalidType = """
                type T = record { foo: runtimeName };
                fnc make(string runtimeName): T { return record { foo: runtimeName }; }
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

    @Test
    void nameFirstAndTypeFirstStructCaptureShareTheSameCheckedSyntax() {
        String source = """
                fnc type_first(): float {
                    const Point initial = infer<Point> struct{x: 1.0, y: 2.0};
                    const replacement: Point = infer struct{y: 4.0, x: 3.0};
                    return replacement.x;
                }

                fnc name_first(): float {
                    const initial: Point = infer<Point> struct{x: 1.0, y: 2.0};
                    const Point replacement = infer struct{x: 3.0, y: 4.0};
                    return replacement.y;
                }
                """;
        List<Ast.Decl> declarations = declarations(source);
        Ast.FunctionDecl nameFirst = (Ast.FunctionDecl) declarations.get(1);
        Ast.BindingStmt initial = (Ast.BindingStmt) nameFirst.body().getFirst();
        assertEquals("initial", initial.name());
        assertEquals("Point", initial.declaredType().name());
        assertEquals("Point", initial.inferredTypeName(),
                "infer<T> must capture the same name in name-first declarations");
        Ast.ObjectExpr initializer = assertInstanceOf(Ast.ObjectExpr.class, initial.initializer());
        assertEquals(Ast.ObjectKind.INFERRED_STRUCT, initializer.kind());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void nameFirstShortStructInferenceCreatesOnlyALexicalType() {
        String source = """
                fnc make(): float {
                    const first: Point = infer struct{x: 1.0, y: 2.0};
                    const second: Point = infer struct{y: 4.0, x: 3.0};
                    return second.y;
                }
                """;
        Ast.FunctionDecl function = (Ast.FunctionDecl) declarations(source).getFirst();
        Ast.BindingStmt first = (Ast.BindingStmt) function.body().getFirst();
        assertEquals("Point", first.declaredType().name());
        assertNull(first.inferredTypeName(),
                "short inference must be proved by the checker, not forged by the parser");
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse(source));
        assertEquals(1, checked.shortInferredAliases().size(),
                "only the introducing binding may create a lexical type alias");
        Ast.FunctionDecl checkedFunction = (Ast.FunctionDecl)
                checked.program().modules().getFirst().declarations().getFirst();
        Ast.BindingStmt checkedFirst = (Ast.BindingStmt) checkedFunction.body().getFirst();
        assertEquals("Point", checked.shortInferredAliases().get(checkedFirst),
                "short alias proof must belong to the checked AST declaration");
        assertNull(checked.shortInferredAliases().get(first),
                "a separately parsed, value-equal binding must not inherit the proof");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void nameFirstCaptureRejectsMismatchedTypeAndCrossKindErasure() {
        String mismatch = """
                fnc bad(): void {
                    const value: Other = infer<Point> struct{x: 1};
                    return;
                }
                """;
        IllegalArgumentException parseError = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(mismatch));
        assertTrue(parseError.getMessage().contains("binding type must match infer<T> exactly"),
                parseError.getMessage());

        String crossKind = """
                fnc bad(): void {
                    const value: Row = infer<Row> record{x: 1};
                    const structValue: Row = infer struct{x: 1};
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(crossKind),
                "a structural record alias must not authorize concrete struct values");
    }


    @Test
    void constTypeDeclarationRejectedInEveryBindingContext() {
        // The module/function binding guard must not be bypassed by parsing
        // a binding as a class field or a for-loop initializer.
        for (String source : List.of(
                """
                define class Widget as
                    const type T = record { foo: string };
                end
                """,
                """
                fnc bad(): void {
                    for (const type T = record { foo: string }; ; ) { }
                    return;
                }
                """,
                """
                fnc bad(): void {
                    for const type T = record { foo: string }; ; { }
                    return;
                }
                """)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source));
            assertTrue(failure.getMessage().contains("'const type' is not a declaration"),
                    failure.getMessage());
        }
    }

    @Test
    void constTypeAliasMarkerStillWorksInClassFields() {
        String source = """
                type T = record { foo: string };
                define class Widget as
                    const type T value = record { foo: "ok" };
                end
                """;
        assertDoesNotThrow(() -> Parser.parse(source),
                "the guard must only reject declarations, not typed const values");
    }

}
