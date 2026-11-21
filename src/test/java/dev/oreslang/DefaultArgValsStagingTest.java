package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.UnloweredDefaultArgValsGuard;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DefaultArgValsStagingTest {
    private static Ast.Program parseFunction(String defaults) {
        return Parser.parse("""
                define module m
                  %s
                  pub fnc run(int a, string b): void { return; }
                end
                """.formatted(defaults));
    }

    @Test
    void parsesClosedNamedDefaultsWithoutExecutingThem() {
        Ast.Program program = parseFunction("@DefaultArgVals(a: 5, b: \"charlie\")");
        Ast.ModuleDecl module = program.modules().stream()
                .filter(m -> m.name().equals("m")).findFirst().orElseThrow();
        Ast.FunctionDecl fn = module.declarations().stream()
                .filter(d -> d instanceof Ast.FunctionDecl)
                .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow();
        assertEquals(1, fn.annotations().size());
        Ast.Annotation annotation = fn.annotations().getFirst();
        assertEquals("DefaultArgVals", annotation.name());
        assertEquals(2, annotation.arguments().size());
        assertEquals("a", annotation.arguments().get(0).namedParameterName());
        assertTrue(annotation.arguments().get(0).namedParameterValue().isIntLiteral());
        assertEquals("b", annotation.arguments().get(1).namedParameterName());
        assertTrue(annotation.arguments().get(1).namedParameterValue().isStringLiteral());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("parse-only"));
        // Defense in depth: a direct AST-to-evaluator path must not execute
        // a declaration whose default values have not been materialized.
        IllegalArgumentException runtimeGate = assertThrows(
                IllegalArgumentException.class, () -> UnloweredDefaultArgValsGuard.reject(program));
        assertTrue(runtimeGate.getMessage().contains("@DefaultArgVals"));
    }

    @Test
    void parsesSignedBoolAndNoneLiteralWitnesses() {
        Ast.Program program = Parser.parse("""
                define module m
                  @DefaultArgVals(a: -7, b: true, c: None)
                  fnc run(int a, bool b, Option<int> c): void { return; }
                end
                """);
        Ast.FunctionDecl fn = program.modules().stream()
                .filter(m -> m.name().equals("m"))
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl)
                .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow();
        Ast.Annotation annotation = fn.annotations().getFirst();
        assertEquals(3, annotation.arguments().size());
        assertEquals(-7L, annotation.arguments().get(0).namedParameterValue().intLiteralValue());
        assertTrue(annotation.arguments().get(1).namedParameterValue().isBoolLiteral());
        assertEquals("None", annotation.arguments().get(2).namedParameterValue().name());
    }

    @Test
    void rejectsMalformedDefaultAnnotationForms() {
        for (String annotation : new String[] {
                "@DefaultArgVals",
                "@DefaultArgVals<>",
                "@DefaultArgVals()",
                "@DefaultArgVals(a = 1)",
                "@DefaultArgVals(a: 1, a: 2)",
                "@DefaultArgVals(a: 1,)",
                "@DefaultArgVals(a: defaultValue())",
                "@DefaultArgVals(a: -false)",
                "@DefaultArgVals(a: 1 + 2)"
        }) {
            assertThrows(IllegalArgumentException.class, () -> parseFunction(annotation), annotation);
        }
    }

    @Test
    void namedDefaultMetadataCannotReclassifyInlineSelfAsAValueSlot() {
        Ast.Program program = Parser.parse("""
                define module m
                  define class Foo as
                    @DefaultArgVals(x: 5)
                    pub foo(self, int x): int { return x; }
                  end
                end
                """);
        Ast.ClassDecl klass = program.modules().stream()
                .filter(m -> m.name().equals("m"))
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        Ast.MethodDecl method = klass.methods().getFirst();
        assertEquals(1, method.arity());
        assertEquals("x", method.parameters().getFirst().name());
        assertNotNull(method.explicitReceiverType());
        assertEquals("self", method.explicitReceiverType().name());
        assertEquals("x", method.annotations().getFirst().arguments()
                .getFirst().namedParameterName());
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("@DefaultArgVals"));
    }

    @Test
    void linkedClassMetadataCannotBypassTheDefaultArgumentGate() {
        Ast.Program program = Parser.parse("""
                define module m
                  pub define class Linked as
                    @DefaultArgVals(x: 3)
                    pub handle(int x): int { return x; }
                  end
                end
                """);
        Ast.ClassDecl linked = program.modules().stream()
                .filter(m -> m.name().equals("m"))
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> UnloweredDefaultArgValsGuard.rejectClass(linked));
        assertTrue(error.getMessage().contains("parse-only"));
    }

    @Test
    void stagedMetadataCannotSilentlyRunOnMethodsConstructorsOrFields() {
        Ast.Program program = Parser.parse("""
                define module m
                  define class Example as
                    @DefaultArgVals(x: 4)
                    constructor(int x) { }
                    @DefaultArgVals(x: 2)
                    pub foo(int x): int { return x; }
                    @DefaultArgVals(x: 3)
                    val int field = 5;
                  end
                end
                """);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("@DefaultArgVals"));
        assertThrows(IllegalArgumentException.class, () -> new OresEvalRootNode(null, program));
    }
}
