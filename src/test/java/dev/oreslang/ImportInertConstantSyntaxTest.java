package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source parser/typechecker gates for compiler-owned constant data.
 * These tests do not certify import-time runtime initialization or native parity.
 */
final class ImportInertConstantSyntaxTest {
    @Test
    void fileAndModuleCompileTimeConstantsHaveCorrectVisibilityAndTypes() {
        Ast.Program program = Parser.parse("""
                define pub const String s = "foo";
                define pub const int x = 42;

                define module Z as
                    pub const int a = 123;
                    pub const String b = "bbb";
                    pub const Symbol sym1 = #zzz;
                    pub const Symbol sym2 = Symbol.for('abc');
                end
                """);
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
        Ast.ModuleDecl module = program.modules().stream()
                .filter(candidate -> candidate.name().equals("Z"))
                .findFirst().orElseThrow();
        assertEquals(4, module.declarations().size());
        Ast.FieldDecl sym2 = assertInstanceOf(Ast.FieldDecl.class, module.declarations().get(3));
        OresSymbol constant = assertInstanceOf(OresSymbol.class,
                assertInstanceOf(Ast.LiteralExpr.class, sym2.initializer()).value());
        assertEquals(OresSymbol.process("abc"), constant);
        for (Ast.Decl decl : module.declarations()) {
            assertEquals(Ast.Visibility.PUBLIC, ((Ast.FieldDecl) decl).visibility());
        }
    }

    @Test
    void fileConstSupportsLegacyPubDefinePermutation() {
        Ast.Program program = assertDoesNotThrow(() ->
                Parser.parse("pub define const int count = 7;"));
        Ast.FieldDecl constant = assertInstanceOf(Ast.FieldDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(Ast.BindingKind.CONST, constant.bindingKind());
        assertEquals(Ast.Visibility.PUBLIC, constant.visibility());
    }

    @Test
    void privateConstantsDefaultToPrivate() {
        Ast.Program program = Parser.parse("""
                define const int internal = 7;
                define module Z as
                    const int hidden = 11;
                end
                """);
        Ast.FieldDecl fileConstant = (Ast.FieldDecl) program.modules().stream()
                .filter(candidate -> candidate.name().equals("__file__"))
                .findFirst().orElse(program.modules().getLast())
                .declarations().getFirst();
        assertEquals(Ast.Visibility.PRIVATE, fileConstant.visibility());
        Ast.FieldDecl moduleConstant = (Ast.FieldDecl) program.modules().stream()
                .filter(candidate -> candidate.name().equals("Z"))
                .findFirst().orElseThrow()
                .declarations().getFirst();
        assertEquals(Ast.Visibility.PRIVATE, moduleConstant.visibility());
    }

    @Test
    void rejectTypeMismatchAndArbitraryFileModuleConstantCalls() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("define pub const int x = \"bar\";")));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("define pub const int x = run();")));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                        define module Z as
                            pub const int x = runtime_value();
                        end
                        """)));
    }

    @Test
    void rejectDynamicAndMalformedStaticSymbolFactory() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub const Symbol s = Symbol.for(dynamicName);"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub const Symbol s = Symbol.for();"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub const Symbol s = Symbol.for(123);"));
    }

    @Test
    void defineModifierDoesNotEnableRuntimeValOrLet() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub val int x = 5;"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub let int x = 5;"));
    }
}
