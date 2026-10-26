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
                define pub macro derive_eq(
                    Type target,
                    Const<int> salt,
                    Identifier field_name,
                    Syntax<Expr> initializer
                ) -> Syntax<Decl> {
                    return build_decl(target, salt, field_name, initializer);
                }
                """));

        Ast.MacroDecl macro = assertInstanceOf(
                Ast.MacroDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals("derive_eq", macro.name());
        assertEquals(Ast.Visibility.PUBLIC, macro.visibility());
        assertEquals(4, macro.parameters().size());

        assertEquals(Ast.MacroParamKind.TYPE, macro.parameters().get(0).type().kind());
        assertEquals(Ast.MacroParamKind.CONST, macro.parameters().get(1).type().kind());
        assertEquals("int", macro.parameters().get(1).type().constType().name());
        assertEquals(Ast.MacroParamKind.IDENTIFIER, macro.parameters().get(2).type().kind());
        assertEquals(Ast.MacroParamKind.SYNTAX, macro.parameters().get(3).type().kind());
        assertEquals(Ast.MacroSyntaxKind.EXPR, macro.parameters().get(3).type().syntaxKind());

        assertFalse(macro.resultKind().list());
        assertEquals(Ast.MacroSyntaxKind.DECL, macro.resultKind().syntaxKind());
    }

    @Test
    void parsesDeclarationListResultWithoutTreatingItAsRuntimeType() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                define macro derive_fields(Type target) -> SyntaxList<Decl> {
                    return build_fields(target);
                }
                """));

        Ast.MacroDecl macro = assertInstanceOf(
                Ast.MacroDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertTrue(macro.resultKind().list());
        assertEquals(Ast.MacroSyntaxKind.DECL, macro.resultKind().syntaxKind());
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
    void compilerMetaTypesCannotLeakIntoRuntimeAbi() {
        for (String type : new String[]{
                "Type",
                "Const<int>",
                "Identifier",
                "Syntax<Expr>",
                "SyntaxList<Decl>"
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck("""
                            fnc bad(%s runtime_value) -> void {
                                return;
                            }
                            """.formatted(type)),
                    type);
            assertTrue(failure.getMessage().contains("compiler-only macro meta type"), failure::toString);
            assertTrue(failure.getMessage().contains("eliminated before AOT code generation"), failure::toString);
        }
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
        assertTrue(invalidList.getMessage().contains("restricted to SyntaxList<Decl>"), invalidList::toString);

        IllegalArgumentException listParameter = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(SyntaxList<Decl> declarations) -> Syntax<Decl> {
                            return first_decl(declarations);
                        }
                        """));
        assertTrue(listParameter.getMessage().contains("not a macro parameter type"), listParameter::toString);
    }

    @Test
    void rejectsNestedMetaConstantsDuplicateNamesAndOrdinaryGenerics() {
        IllegalArgumentException nestedMeta = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(Const<Type> target) -> Syntax<Decl> {
                            return build_decl(target);
                        }
                        """));
        assertTrue(nestedMeta.getMessage().contains("not another macro meta type"), nestedMeta::toString);

        IllegalArgumentException nestedWrappedMeta = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(Const<List<Syntax<Expr>>> values) -> Syntax<Decl> {
                            return build_decl(values);
                        }
                        """));
        assertTrue(nestedWrappedMeta.getMessage().contains("not another macro meta type"),
                nestedWrappedMeta::toString);

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(Type value, Identifier value) -> Syntax<Decl> {
                            return build_decl(value);
                        }
                        """));
        assertTrue(duplicate.getMessage().contains("duplicate macro parameter 'value'"), duplicate::toString);

        IllegalArgumentException generic = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad<T>(Type target) -> Syntax<Decl> {
                            return build_decl(target);
                        }
                        """));
        assertTrue(generic.getMessage().contains("does not declare ordinary generic parameters"), generic::toString);
    }

    @Test
    void quoteIntrinsicCannotBeShadowedByMacroParameter() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define macro bad(Const<int> quote) -> Syntax<Expr> {
                            return quote(quote);
                        }
                        """));

        assertTrue(failure.getMessage().contains("'quote' is reserved"),
                failure::toString);
        assertTrue(failure.getMessage().contains("compile-time macro quotation"),
                failure::toString);
    }

    @Test
    void rejectsMacroDeclarationsInsideRuntimeBlocks() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc outer() -> void {
                            define macro local(Type target) -> Syntax<Decl> {
                                return build_decl(target);
                            }
                            return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("compile-time declaration"), failure::toString);
        assertTrue(failure.getMessage().contains("runtime block"), failure::toString);
    }
}
