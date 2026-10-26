package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MacroDeclarationParserTest {
    @Test
    void parsesDefineMacroAsDistinctCompileTimeDeclaration() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                define pub macro derive_eq(Type target, Const<int> salt) -> Syntax<Decl> {
                    return build_decl(target, salt);
                }
                """));

        Ast.MacroDecl macro = assertInstanceOf(
                Ast.MacroDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals("derive_eq", macro.name());
        assertEquals(Ast.Visibility.PUBLIC, macro.visibility());
        assertEquals(2, macro.parameters().size());
        assertEquals("Type", macro.parameters().get(0).type().name());
        assertEquals("Const", macro.parameters().get(1).type().name());
        assertEquals("int", macro.parameters().get(1).type().arguments().getFirst().name());
        assertEquals("Syntax", macro.resultKind().name());
        assertEquals("Decl", macro.resultKind().arguments().getFirst().name());
    }

    @Test
    void residualMacroFailsClosedBeforeExecutableSemanticAnalysis() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define macro derive(Type target) -> Syntax<Decl> {
                            return build_decl(target);
                        }
                        """));

        assertTrue(failure.getMessage().contains("compile-time-only"), failure::toString);
        assertTrue(failure.getMessage().contains("runtime fallback is forbidden"), failure::toString);
    }

    @Test
    void rejectsRuntimeMacroParametersAndInvalidResultKinds() {
        IllegalArgumentException runtimeParameter = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(int runtime_value) -> Syntax<Decl> {
                            return build_decl(runtime_value);
                        }
                        """));
        assertTrue(runtimeParameter.getMessage().contains("must use Type, Const<T>, Identifier, or Syntax<K>"),
                runtimeParameter::toString);

        IllegalArgumentException voidResult = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(Type target) -> void {
                            return;
                        }
                        """));
        assertTrue(voidResult.getMessage().contains("macro result kind must be"), voidResult::toString);

        IllegalArgumentException invalidList = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(Type target) -> SyntaxList<Expr> {
                            return build_decl(target);
                        }
                        """));
        assertTrue(invalidList.getMessage().contains("macro result kind must be"), invalidList::toString);
    }
}
