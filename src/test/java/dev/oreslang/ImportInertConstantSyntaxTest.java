package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;

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
                .filter(candidate -> candidate.name().equals(Parser.ROOT_MODULE))
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

    @Test
    void moduleHeaderVisibilityIsExplicitAndPrivateByDefault() {
        Ast.Program program = Parser.parse("""
                define pub module PublicData as
                    pub const int answer = 42;
                    const int hidden = 7;
                end
                define module PrivateData as
                    pub const int misleadingExport = 1;
                end
                """);
        assertEquals(Ast.Visibility.PUBLIC, program.modules().get(0).visibility());
        assertEquals(Ast.Visibility.PRIVATE, program.modules().get(1).visibility());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
    }

    @Test
    void namedImportsMustRejectPrivateModulesEvenWithPublicMembers() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lib.ores", """
                define module Hidden as
                    pub const int answer = 42;
                end
                """);
        sources.put("main.ores", """
                import module Hidden from "./lib.ores";
                pub fnc main(): void { return; }
                """);
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources));
        sources.put("lib.ores", """
                define pub module Hidden as
                    pub const int answer = 42;
                end
                """);
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(sources));
    }

    @Test
    void privateNamedModuleCannotLeakPublicNestedClass() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lib.ores", """
                define module Hidden as
                    pub define class Box as
                    end
                end
                """);
        sources.put("main.ores", """
                import class Box from "./lib.ores";
                pub fnc main(): void { return; }
                """);
        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(sources));
        sources.put("lib.ores", """
                define pub module Hidden as
                    pub define class Box as
                    end
                end
                """);
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(sources));
    }

    @Test
    void staticSymbolFactoryCannotBeShadowedByImportsOrDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define pub const Symbol s = Symbol.for("abc");
                const int Symbol = 123;
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                import * as Symbol from "./other.ores";
                define pub const Symbol s = Symbol.for("abc");
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define pub const Symbol s = Symbol.for("abc");
                define module Symbol as
                end
                """));
    }

    @Test
    void foldReferencesAcrossRootAndModuleWithoutGuestCode() {
        Ast.Program program = Parser.parse("""
                define pub const int base = 40;
                define pub module Config as
                    pub const int answer = base + 2;
                    pub const int doubled = answer * 2;
                    pub const Symbol status = Symbol.for("ready");
                end
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        Ast.ModuleDecl config = program.modules().stream()
                .filter(module -> module.name().equals("Config")).findFirst().orElseThrow();
        assertEquals(42L, ((Ast.LiteralExpr) ((Ast.FieldDecl) config.declarations().get(0))
                .initializer()).value());
        assertEquals(84L, ((Ast.LiteralExpr) ((Ast.FieldDecl) config.declarations().get(1))
                .initializer()).value());
        assertEquals(OresSymbol.process("ready"),
                ((Ast.LiteralExpr) ((Ast.FieldDecl) config.declarations().get(2))
                        .initializer()).value());
    }

    @Test
    void foldQualifiedModuleConstantsAndRejectPrivateCrossModuleAccess() {
        Ast.Program good = Parser.parse("""
                define pub module A as
                    pub const int publicNumber = 7;
                end
                define pub module B as
                    pub const int doubled = A.publicNumber * 2;
                end
                """);
        assertDoesNotThrow(() -> TypeChecker.check(good));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define pub module A as
                    const int hidden = 7;
                end
                define pub module B as
                    pub const int leaked = A.hidden;
                end
                """));
    }

    @Test
    void rejectCyclicConstantDependenciesAcrossLegalSameFileModules() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                define pub module A as
                    pub const int one = B.two + 1;
                end
                define pub module B as
                    pub const int two = A.one + 1;
                end
                """));
        assertTrue(failure.getMessage().contains("circular constant dependency"), failure::toString);
    }

    @Test
    void constantEvaluationRejectsArithmeticOverflowAndRuntimeCalls() {
        IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub const int x = 9223372036854775807 + 1;"));
        assertTrue(overflow.getMessage().contains("overflow"), overflow::toString);

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub const int x = expensive();"));

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub const int x = 1 / 0;"));
    }

    @Test
    void foldedPublicConstantDependencyChangesImporterAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("constants.ores", """
                define pub const int base = 40;
                define pub module Config as
                    pub const int answer = base + 2;
                end
                """);
        sources.put("app.ores", """
                import module Config from "./constants.ores";
                pub fnc main(): void { return; }
                """);
        assertTrue(compiler.compile(sources).rebuilt("app.ores"));
        assertTrue(compiler.compile(sources).reused("app.ores"));
        sources.put("constants.ores", """
                define pub const int base = 41;
                define pub module Config as
                    pub const int answer = base + 2;
                end
                """);
        assertTrue(compiler.compile(sources).rebuilt("app.ores"),
                "public inherited constant values must be rehashed and invalidate clients");
    }

    @Test
    void publicConstantValueChangesInvalidateImportersButPrivateChangesDoNot() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lib.ores", """
                define pub module Data as
                    pub const int answer = 42;
                    const int internal = 10;
                end
                """);
        sources.put("main.ores", """
                import module Data from "./lib.ores";
                pub fnc main(): void { return; }
                """);

        var original = compiler.compile(sources);
        assertTrue(original.rebuilt("lib.ores"));
        assertTrue(original.rebuilt("main.ores"));
        assertTrue(compiler.compile(sources).reused("main.ores"));

        sources.put("lib.ores", """
                define pub module Data as
                    pub const int answer = 43;
                    const int internal = 10;
                end
                """);
        var updated = compiler.compile(sources);
        assertTrue(updated.rebuilt("lib.ores"));
        assertTrue(updated.rebuilt("main.ores"),
                "an inlined public constant changes ABI even if type remains int");

        sources.put("lib.ores", """
                define pub module Data as
                    pub const int answer = 43;
                    const int internal = 11;
                end
                """);
        var privateOnly = compiler.compile(sources);
        assertTrue(privateOnly.rebuilt("lib.ores"));
        assertTrue(privateOnly.reused("main.ores"),
                "private constant changes must not invalidate external ABI");
    }
}
