package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.DefaultArgumentPolicy;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.OptionalCallShapeBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Formal-local default syntax is retained as AST metadata, never executed.
 * Runtime admission remains blocked until the whole checked default pipeline
 * exists, including direct AST, imported classes and nested callable paths.
 */
final class DefaultAnnotationAdmissionTest {
    private static Ast.FunctionDecl function(Ast.Program program) {
        return program.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void declarationLevelDefaultCannotBeSilentlyIgnored() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        @Default(7)
                        pub fnc foo(int x): int { return x; }
                        """));
        assertTrue(failure.getMessage().contains("@Default requires compile-time semantic validation"),
                failure.getMessage());
    }

    @Test
    void parserRetainsParameterLocalDefaultWithoutRunningIt() {
        Ast.Program program = Parser.parse("""
                pub fnc foo(@Default(7) int x): int { return x; }
                """);
        Ast.Param formal = function(program).parameters().getFirst();
        assertEquals("x", formal.name());
        assertFalse(formal.optional()); // annotation adds omission, not Option<T>
        assertTrue(formal.hasDefault());
        assertEquals("Default", formal.annotations().getFirst().name());
        assertEquals(7L, formal.annotations().getFirst()
                .arguments().getFirst().intLiteralValue());
        assertEquals(DefaultArgumentPolicy.Literal.integer(7),
                DefaultArgumentPolicy.stagedLiteralFromAst(formal));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
    }

    @Test
    void recognizesClosedPositiveAndNegativeLiteralForms() {
        Ast.Program parsed = Parser.parse("""
                pub fnc foo(@Default(-5) int a, @Default(true) bool b,
                            @Default("abc") string c): int { return a; }
                """);
        List<Ast.Param> params = function(parsed).parameters();
        assertEquals(-5L, params.get(0).annotations().getFirst()
                .arguments().getFirst().intLiteralValue());
        assertEquals(DefaultArgumentPolicy.Literal.integer(-5),
                DefaultArgumentPolicy.stagedLiteralFromAst(params.get(0)));
        assertEquals(DefaultArgumentPolicy.Literal.bool(true),
                DefaultArgumentPolicy.stagedLiteralFromAst(params.get(1)));
        assertEquals(DefaultArgumentPolicy.Literal.string("abc"),
                DefaultArgumentPolicy.stagedLiteralFromAst(params.get(2)));
        assertTrue(params.get(1).annotations().getFirst()
                .arguments().getFirst().boolLiteralValue());
        assertEquals("abc", params.get(2).annotations().getFirst()
                .arguments().getFirst().stringLiteralValue());
    }

    @Test
    void retainsExplicitDefaultNoneSeparatelyFromNoAnnotation() {
        Ast.Program parsed = Parser.parse("""
                pub fnc foo(@Default(None) Option<int> id,
                            Option<int> other): void { return; }
                """);
        List<Ast.Param> params = function(parsed).parameters();
        assertTrue(params.get(0).hasDefault());
        assertEquals("None", params.get(0).annotations().getFirst()
                .arguments().getFirst().name());
        assertEquals(DefaultArgumentPolicy.Literal.none(),
                DefaultArgumentPolicy.stagedLiteralFromAst(params.get(0)));
        assertFalse(params.get(1).hasDefault());
        assertNull(DefaultArgumentPolicy.stagedLiteralFromAst(params.get(1)));
    }

    @Test
    void methodAndConstructorAnnotationsAreStagedAndFailClosed() {
        Ast.Program program = Parser.parse("""
                define class Box as
                  val int id;
                  constructor(@Default(4) int id) {
                    self.id = id;
                  }
                  pub fnc read(@Default(7) int fallback): int {
                    return fallback;
                  }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
    }

    @Test
    void lambdaDefaultCannotBypassGlobalAdmission() {
        Ast.Program parsed = Parser.parse("""
                pub fnc f(): int {
                    val callback = (@Default(2) int x) -> x;
                    return 3;
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(parsed));
    }

    @Test
    void malformedOrNonconstantParameterAnnotationsAreRejectedEarly() {
        for (String annotation : List.of(
                "@Default()",
                "@Default(1, 2)",
                "@Default<7>",
                "@Default(hello)",
                "@Default(makeValue())",
                "@Default(1 + 2)",
                "@Default(7) @Default(8)",
                "@Default(7) @Other(8)")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc f(" + annotation
                            + " int x): int { return x; }"), annotation);
        }
    }

    @Test
    void astFabricationCannotBypassDefaultLoweringGate() {
        Ast.Param forged = new Ast.Param(
                Ast.TypeRef.simple("int"), "x", false, false,
                List.of(new Ast.Annotation("Default",
                        List.of(Ast.TypeRef.intLiteral("7")))));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNoUnloweredOptionalFormals(
                        List.of(forged)));
        assertTrue(failure.getMessage().contains("@Default"));
        assertThrows(IllegalArgumentException.class, () -> new Ast.Param(
                Ast.TypeRef.simple("int"), "x", false, true, forged.annotations()));
    }

    @Test
    void malformedForgedLiteralCannotMasqueradeAsCompilerDefault() {
        Ast.Param forged = new Ast.Param(
                Ast.TypeRef.simple("int"), "x", false, false,
                List.of(new Ast.Annotation("Default",
                        List.of(Ast.TypeRef.simple("fakeDefault")))));
        assertThrows(IllegalArgumentException.class,
                () -> DefaultArgumentPolicy.stagedLiteralFromAst(forged));

        Ast.Param huge = new Ast.Param(
                Ast.TypeRef.simple("int"), "x", false, false,
                List.of(new Ast.Annotation("Default",
                        List.of(Ast.TypeRef.intLiteral("999999999999999999999999")))));
        assertThrows(IllegalArgumentException.class,
                () -> DefaultArgumentPolicy.stagedLiteralFromAst(huge));
    }

    @Test
    void ordinaryOptionTypeInLegacySourceRemainsRequired() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc takes(Option<int> id): void { return; }
                """)));
    }
}
