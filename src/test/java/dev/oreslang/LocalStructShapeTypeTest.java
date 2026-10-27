package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact struct shape is not the same type grammar as a readonly structural
 * record shape. No named nominal local struct declarations are admitted here.
 */
final class LocalStructShapeTypeTest {
    private static Ast.FunctionDecl function(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void structInTypePositionProducesClosedExactShape() {
        String source = """
                pub fnc make(): struct { foo: int } {
                    return struct { foo: int } { foo: 5 };
                }
                """;
        Ast.FunctionDecl fn = function(source);
        assertTrue(fn.returnType().isStructType());
        assertFalse(fn.returnType().isRecordType());
        assertEquals("int", fn.returnType().structMembers().get("foo").name());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void recordInTypePositionRemainsReadonlyStructural() {
        String source = """
                pub fnc make(): record { foo: int } {
                    return record { foo: 5 };
                }
                """;
        Ast.FunctionDecl fn = function(source);
        assertTrue(fn.returnType().isReadonlyRecordType());
        assertFalse(fn.returnType().isStructType());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void functionLocalStructShapeAliasErasesToExactShape() {
        String source = """
                pub fnc make(): T {
                    type T = struct { foo: int };
                    const T value = struct { foo: int } { foo: 5 };
                    return value;
                }
                """;
        Ast.FunctionDecl fn = function(source);
        assertTrue(fn.returnType().isStructType());
        assertEquals(2, fn.body().size());
        assertTrue(((Ast.BindingStmt) fn.body().getFirst()).declaredType().isStructType());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void independentLocalAliasesCanUseDifferentExactShapes() {
        String source = """
                fnc a(): T {
                    type T = struct { foo: int };
                    return struct { foo: int } { foo: 1 };
                }
                fnc b(): T {
                    type T = record { foo: string };
                    return record { foo: "hello" };
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void exactStructAndRecordAreNotInterchangeable() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(): T {
                    type T = struct { foo: int };
                    return record { foo: 5 };
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(): T {
                    type T = record { foo: int };
                    return struct { foo: int } { foo: 5 };
                }
                """)));
    }

    @Test
    void structAliasMayNotCaptureGenericOrRecursiveUnverifiedIdentity() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(): T {
                    type T<U> = struct { foo: U };
                    return struct { foo: int } { foo: 5 };
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(): T {
                    type T = struct { recursive: T };
                    return struct { recursive: int } { recursive: 5 };
                }
                """));
    }
}
