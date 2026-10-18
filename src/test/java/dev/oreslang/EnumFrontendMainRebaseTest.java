package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Syntax/AST-only rebase gate. This suite does NOT assert runtime enum support;
 * enabling executable enum values requires the staged TypeChecker, ownership,
 * evaluator and LLVM guest lowering to be merged and verified separately.
 */
final class EnumFrontendMainRebaseTest {
    @Test
    void enumAndMatchPatternTokensAreReserved() {
        var lexemes = new Lexer("define enum Color as Red end define match pattern P as Color.Red end").scan();
        assertTrue(lexemes.stream().anyMatch(t -> t.type() == Token.Type.ENUM));
        assertTrue(lexemes.stream().anyMatch(t -> t.type() == Token.Type.PATTERN));
    }

    @Test
    void independentNominalEnumsAndNamedPatternStayDistinctInAst() {
        Ast.Program program = Parser.parse("""
                define enum Color as
                    Red
                    Blue
                end
                define enum Mode as
                    Red
                    Blue
                end
                define match pattern Selection as
                    Color.Red
                    ...Mode
                end
                """);
        assertEquals(1, program.modules().size());
        List<Ast.Decl> entries = program.modules().getFirst().declarations();
        assertEquals(3, entries.size());
        Ast.EnumDecl color = assertInstanceOf(Ast.EnumDecl.class, entries.get(0));
        Ast.EnumDecl mode = assertInstanceOf(Ast.EnumDecl.class, entries.get(1));
        Ast.PatternDecl pattern = assertInstanceOf(Ast.PatternDecl.class, entries.get(2));
        assertEquals("Color", color.name());
        assertEquals("Mode", mode.name());
        assertEquals(List.of("Red", "Blue"), color.cases().stream().map(Ast.EnumCase::name).toList());
        assertEquals(List.of("Red", "Blue"), mode.cases().stream().map(Ast.EnumCase::name).toList());
        assertEquals(List.of(1, 2), color.cases().stream().map(Ast.EnumCase::tag).toList());
        assertEquals(List.of("Color.Red", "...Mode"), pattern.alternatives());
    }

    @Test
    void enumAndNamedPatternWithinNamedModuleRetainLexicalOwnership() {
        Ast.Program parsed = Parser.parse("""
                define module Colors as
                    define enum Color as
                        Red
                        Blue
                    end
                    define match pattern Important as
                        Color.Red
                    end
                end
                define module Modes as
                    define enum Mode as
                        Fast
                    end
                    define pattern AllModes as
                        ...Mode
                    end
                end
                """);
        assertEquals(List.of("Colors", "Modes"),
                parsed.modules().stream().map(Ast.ModuleDecl::name).toList());
        assertEquals(List.of("Color", "Important"),
                parsed.modules().getFirst().declarations().stream()
                        .map(d -> d instanceof Ast.EnumDecl e ? e.name()
                                : ((Ast.PatternDecl) d).name()).toList());
        assertEquals(List.of("Mode", "AllModes"),
                parsed.modules().get(1).declarations().stream()
                        .map(d -> d instanceof Ast.EnumDecl e ? e.name()
                                : ((Ast.PatternDecl) d).name()).toList());
        IllegalArgumentException blocked = assertThrows(
                IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(parsed));
        assertTrue(blocked.getMessage().contains("syntax-only"),
                blocked.getMessage());
    }

    @Test
    void malformedModuleEnumModifiersCannotBypassGlobalAdmission() {
        for (String member : List.of(
                "define suspend enum Color as Red end",
                "@Serializable define enum Color as Red end",
                "define async match pattern P as Color.Red end")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define module Work as\n"
                            + member + "\nend"), member);
        }
    }

    @Test
    void explicitMaxIntWireTagIsAcceptedWithoutImplicitOverflow() {
        Ast.EnumDecl en = assertInstanceOf(Ast.EnumDecl.class,
                Parser.parse("define enum Boundary as Last = 2147483647 end")
                        .modules().getFirst().declarations().getFirst());
        assertEquals(Integer.MAX_VALUE, en.cases().getFirst().tag());
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("define enum Boundary as Last = 2147483647 Next end"));
    }

    @Test
    void duplicateAndUnorderedEnumCasesAreRejectedAtParseBoundary() {
        for (String source : List.of(
                "define enum Bad as Red Red end",
                "define enum Bad as Red = 1 Blue = 1 end",
                "define enum Bad as Red = 2 Blue = 1 end",
                "define enum Bad as Red = 0 end",
                "define enum Bad as end")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void matchPatternDuplicatesAndUnqualifiedCasesReject() {
        for (String source : List.of(
                "define match pattern P as Color.Red Color.Red end",
                "define match pattern P as Red end",
                "define match pattern P as end",
                "define match pattern P as Color.Red | Color.Blue end")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void oversizedEnumAndPatternDeclarationsFailBeforeAstExpansion() {
        StringBuilder tooManyCases = new StringBuilder("define enum Overflow as\n");
        StringBuilder tooManyAlts = new StringBuilder("define match pattern Overflow as\n");
        for (int i = 0; i <= 4096; i++) {
            tooManyCases.append("Case").append(i).append("\n");
            tooManyAlts.append("Color.Case").append(i).append("\n");
        }
        tooManyCases.append("end\n");
        tooManyAlts.append("end\n");

        IllegalArgumentException enumError = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(tooManyCases.toString()));
        assertTrue(enumError.getMessage().contains("enum case declaration limit"),
                enumError.getMessage());

        IllegalArgumentException patternError = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(tooManyAlts.toString()));
        assertTrue(patternError.getMessage().contains("named pattern alternative limit"),
                patternError.getMessage());
    }

    @Test
    void standaloneLegacyPatternSpellingRetainsSyntaxCompatibility() {
        Ast.PatternDecl pat = assertInstanceOf(Ast.PatternDecl.class,
                Parser.parse("define pattern P as Color.Red end")
                        .modules().getFirst().declarations().getFirst());
        assertEquals(List.of("Color.Red"), pat.alternatives());
    }

    @Test
    void unsupportedModifiersAndAnnotationsFailClosed() {
        for (String source : List.of(
                "define suspend enum Color as Red end",
                "define async enum Color as Red end",
                "define pub enum Color as Red end",
                "@Serializable define enum Color as Red end",
                "define suspend match pattern P as Color.Red end")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void typeCheckerMustRejectSyntaxOnlyEnumAndPatternDeclarations() {
        for (String source : List.of(
                "define enum Color as Red end",
                "define match pattern P as Color.Red end",
                "define pattern P as Color.Red end")) {
            Ast.Program parsed = Parser.parse(source);
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> dev.oreslang.types.TypeChecker.checkTypes(parsed), source);
            assertTrue(denied.getMessage().contains("syntax-only"),
                    denied.getMessage());
        }
    }

    @Test
    void existingClassAndAritySyntaxIsStillParsedOnNewMainParser() {
        Ast.Program result = Parser.parse("""
                define class Worker as
                    pub work(): int { return 1; }
                    pub work(int x): int { return x; }
                end
                """);
        Ast.ClassDecl worker = assertInstanceOf(Ast.ClassDecl.class,
                result.modules().getFirst().declarations().getFirst());
        assertEquals(List.of(0, 1), worker.methods().stream().map(Ast.MethodDecl::arity).toList());
    }
}
