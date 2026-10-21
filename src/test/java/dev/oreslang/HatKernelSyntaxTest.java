package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HatKernelSyntaxTest {
    @Test
    void kernelIsReservedAndCanonicalDeclarationBuildsDedicatedAst() {
        var tokens = new Lexer("define pub kernel K as end").scan();
        assertTrue(tokens.stream().anyMatch(t ->
                t.lexeme().equals("kernel") && t.type() == Token.Type.KERNEL));

        Ast.Program program = Parser.parse("""
                define pub kernel VectorAdd as
                  fnc run(int index): void { return; }
                end
                """);

        Ast.Decl declaration = program.modules().getFirst().declarations().getFirst();
        Ast.KernelDecl kernel = assertInstanceOf(Ast.KernelDecl.class, declaration);
        assertEquals("VectorAdd", kernel.name());
        assertEquals(Ast.Visibility.PUBLIC, kernel.visibility());
        assertEquals(1, kernel.functions().size());
        assertEquals("run", kernel.functions().getFirst().name());
    }

    @Test
    void kernelRequiresDefineAndAsAndRejectsNonFunctionMembers() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub kernel K as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub kernel K end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub kernel K as const int n = 1; end"));
    }

    @Test
    void executableAdmissionFailsClosedUntilHatLoweringExists() {
        Ast.Program program = Parser.parse("define pub kernel K as end");
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(program));
        assertTrue(error.getMessage().contains("HAT"));
        assertTrue(error.getMessage().contains("ownership"));
    }

    @Test
    void hatStdlibIsPackagedSourceAndTypeChecks() throws Exception {
        String source = CoreLibrarySources.source("std/hat");
        assertTrue(source.contains("define module hat"));
        assertTrue(source.contains("BackendProvider"));
        assertTrue(source.contains("DeviceBuffer"));
        assertTrue(source.contains("Fence"));
        assertFalse(source.contains("java:"));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
    }
}
