package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage-0 source vectors pinned byte-for-byte to common-source macro #218.
 * This is a compiler front-end gate, NOT a Graal Native Image guest execution.
 */
final class MacroSharedScalarAotVectorsTest {
    private static String source(String name) {
        try (InputStream input = MacroSharedScalarAotVectorsTest.class.getResourceAsStream(
                "/macro-scalar-aot-v1/" + name)) {
            if (input == null) throw new IllegalStateException("missing shared macro vector " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read shared macro vector " + name, e);
        }
    }

    @Test
    void sharedLiteralIdentityAndNestedProgramsExpandIntoOrdinaryLiteral42() {
        for (String name : List.of("literal-42.atto", "identity-42.atto", "nested-42.atto")) {
            Ast.Program checked = assertDoesNotThrow(
                    () -> OresCompiler.parseAndTypeCheck(source(name)), name);
            assertTrue(checked.modules().stream()
                    .flatMap(module -> module.declarations().stream())
                    .noneMatch(decl -> decl instanceof Ast.MacroDecl
                            || decl instanceof Ast.MacroInvocationDecl),
                    name + " must not retain executable macro declarations");
            Ast.FunctionDecl function = checked.modules().stream()
                    .flatMap(module -> module.declarations().stream())
                    .filter(Ast.FunctionDecl.class::isInstance)
                    .map(Ast.FunctionDecl.class::cast)
                    .filter(fn -> "main".equals(fn.name()))
                    .findFirst().orElseThrow();
            Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class,
                    function.body().getFirst(), name);
            Ast.LiteralExpr value = assertInstanceOf(Ast.LiteralExpr.class,
                    returned.value(), name);
            assertEquals(42L, value.value(), name + " is guest integer 42");
        }
    }

    @Test
    void sharedNegativeProgramsFailBeforeExecutableSemanticAdmission() {
        for (String name : List.of("overflow-reject.atto", "wrong-kind-reject.atto",
                "dynamic-const-reject.atto", "unresolved-reject.atto")) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> OresCompiler.parseAndTypeCheck(source(name)), name);
            assertNotNull(failure.getMessage(), name);
            assertFalse(failure.getMessage().isBlank(), name);
        }
    }
}
