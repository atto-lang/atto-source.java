package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First closed, compile-time-only local type slice: function/routine-prologue
 * record aliases erase to structural TypeRefs before any compiler/runtime pass.
 * No synthetic runtime, nominal or importable type is created.
 */
final class LocalRecordTypeAliasTest {
    private static Ast.FunctionDecl function(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
    }

    private static void accepted(String source) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    private static void parseRejected(String source, String diagnostic) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(exception.getMessage().contains(diagnostic), exception.getMessage());
    }

    @Test
    void publicFncReturnTypeIsCanonicalStructuralRecord() {
        String source = """
                pub fnc foo(): T {
                    type T = record { foo: string };
                    const T x = record { foo: "foo" };
                    return x;
                }
                fnc user(): string {
                    const r = foo();
                    return r.foo;
                }
                """;
        Ast.FunctionDecl fn = function(source);
        assertTrue(fn.returnType().isRecordType());
        assertEquals("string", fn.returnType().recordMembers().get("foo").name());
        assertEquals(2, fn.body().size(), "local aliases are not runtime statements");
        Ast.BindingStmt value = (Ast.BindingStmt) fn.body().getFirst();
        assertTrue(value.declaredType().isRecordType());
        assertEquals(Ast.ObjectKind.RECORD,
                ((Ast.ObjectExpr) value.initializer()).kind());
        accepted(source);
    }

    @Test
    void routineAlsoAcceptsRecordTypeInItsReturnSignature() {
        String source = """
                pub routine foo(): T {
                    type T = record { foo: string };
                    return record { foo: "bar" };
                }
                """;
        assertTrue(function(source).returnType().isRecordType());
        accepted(source);
    }

    @Test
    void publicParameterAndReturnSignaturesBothEraseTheLocalTypeName() {
        String source = """
                pub fnc identity(T input): T {
                    type T = record { foo: string };
                    return input;
                }
                fnc client(): string {
                    const x = identity(record { foo: "works" });
                    return x.foo;
                }
                """;
        Ast.FunctionDecl fn = function(source);
        assertTrue(fn.parameters().getFirst().type().isRecordType());
        assertTrue(fn.returnType().isRecordType());
        accepted(source);
    }

    @Test
    void recordAliasInsideActorCallableIsNotSilentlyReinterpreted() {
        String source = """
                actor fnc wire(): void {
                    type T = record { foo: string };
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
    }

    @Test
    void explicitTypeMarkerStillWorksForLocalTransparentAlias() {
        String source = """
                fnc foo(): T {
                    type T = record { foo: string };
                    const type T value = record { foo: "yes" };
                    return value;
                }
                """;
        accepted(source);
    }

    @Test
    void localAliasCanUseEarlierLocalRecordAlias() {
        String source = """
                fnc foo(): B {
                    type A = record { foo: string };
                    type B = record { nested: A };
                    const B result = record { nested: record { foo: "ok" } };
                    return result;
                }
                """;
        Ast.TypeRef shape = function(source).returnType();
        assertTrue(shape.isRecordType());
        assertTrue(shape.recordMembers().get("nested").isRecordType());
        accepted(source);
    }

    @Test
    void forwardLocalRecordAliasesFailBeforePublishedAbiConstruction() {
        parseRejected("""
                pub fnc foo(): A {
                    type A = record { nested: B };
                    type B = record { foo: string };
                    return record { nested: record { foo: "oops" } };
                }
                """, "forward local record alias reference");
    }

    @Test
    void localTypeBindingsDoNotLeakToTheNextFunction() {
        String source = """
                fnc first(): string {
                    type T = record { foo: string };
                    return "yes";
                }
                fnc second(): string {
                    const T x = record { foo: "not visible" };
                    return x.foo;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void localRecordsCannotEraseToMutableStructs() {
        String source = """
                fnc bad(): void {
                    type T = record { foo: string };
                    const T x = infer struct { foo: "no" };
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void recordValueCannotUseMissingFieldOrIncorrectType() {
        for (String badValue : List.of(
                "record { other: \"foo\" }",
                "record { foo: 123 }")) {
            String source = """
                    fnc bad(): T {
                        type T = record { foo: string };
                        const T x = %s;
                        return x;
                    }
                    """.formatted(badValue);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
        }
    }

    @Test
    void declarationsAreStaticAndLimitedToCallablePrologue() {
        parseRejected("""
                fnc f(): void {
                    const x = 1;
                    type T = record { foo: string };
                    return;
                }
                """, "precede executable statements");
        parseRejected("""
                fnc f(): void {
                    type A = record { foo: string };
                    type A = record { bar: int };
                    return;
                }
                """, "duplicate local record type alias");
        parseRejected("""
                fnc f(): void {
                    type T = int;
                    return;
                }
                """, "requires record");
        parseRejected("""
                fnc f(): void {
                    type T = { foo: string };
                    return;
                }
                """, "requires record");
        parseRejected("""
                fnc f(): void {
                    type T<G> = record { foo: G };
                    return;
                }
                """, "generic local record aliases");
        parseRejected("""
                fnc f(): void {
                    type T = record { nested: T };
                    return;
                }
                """, "recursive local record alias");
    }

    @Test
    void localAliasCannotShadowLaterModuleAliasOrNominalType() {
        parseRejected("""
                fnc early(): T {
                    type T = record { foo: string };
                    return record { foo: "x" };
                }
                type T = record { foo: string };
                """, "conflicts with an existing imported, module, or nominal type name");

        parseRejected("""
                fnc early(): T {
                    type T = record { foo: string };
                    return record { foo: "x" };
                }
                define class T as
                end
                """, "conflicts with an existing imported, module, or nominal type name");
    }

    @Test
    void localAliasCannotCaptureImportAliasesOrModuleNames() {
        parseRejected("""
                import type External as T from "./external";
                fnc value(): T {
                    type T = record { foo: string };
                    return record { foo: "x" };
                }
                """, "conflicts with an existing imported, module, or nominal type name");

        parseRejected("""
                fnc value(): T {
                    type T = record { foo: string };
                    return record { foo: "x" };
                }
                define module T as
                end
                """, "conflicts with an existing imported, module, or nominal type name");
    }

    @Test
    void localAliasesInSeparateFunctionsCanStillShareSpelling() {
        String source = """
                fnc a(): T {
                    type T = record { foo: string };
                    return record { foo: "a" };
                }
                fnc b(): T {
                    type T = record { foo: string };
                    return a();
                }
                """;
        accepted(source);
    }

    @Test
    void recordAliasesDoNotManufactureNominalTypeIdentity() {
        String source = """
                fnc a(): T {
                    type T = record { foo: string };
                    return record { foo: "a" };
                }
                fnc b(): T {
                    type T = record { foo: string };
                    return a();
                }
                """;
        accepted(source);
    }

    @Test
    void invalidConstTypeAndClassStillFailClosed() {
        parseRejected("""
                fnc f(): void {
                    const type T = record { foo: string };
                    return;
                }
                """, "'const type' is not a declaration");
        parseRejected("""
                fnc f(): void {
                    define class T as end
                    return;
                }
                """, "class declarations are compile-time declarations");
    }
}
