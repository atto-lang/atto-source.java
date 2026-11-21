package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.UnloweredDefaultArgValsGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ParameterPresenceV3SyntaxTest {

    private static Ast.FunctionDecl onlyFunction(Ast.Program p) {
        return p.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl)
                .map(d -> (Ast.FunctionDecl) d)
                .findFirst().orElseThrow();
    }

    @Test
    void typeQuestionAndNameQuestionRemainIndependent() {
        Ast.Program p = Parser.parse("""
                define module m
                  @DefaultArgVals(a: 5, b: "charlie")
                  pub fnc run(int? a, string b?): void { return; }
                end
                """);
        List<Ast.Param> args = onlyFunction(p).parameters();
        assertEquals(2, args.size());
        assertEquals("Option", args.get(0).type().name());
        assertEquals("int", args.get(0).type().arguments().getFirst().name());
        assertTrue(args.get(0).optionTypeSugar());
        assertFalse(args.get(0).omittable());
        assertEquals("string", args.get(1).type().name());
        assertFalse(args.get(1).optionTypeSugar());
        assertTrue(args.get(1).omittable());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(p));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(p));
    }

    @Test
    void doubleQuestionIsOptionValueWithSeparateOmittability() {
        Ast.Program p = Parser.parse("""
                define module m
                  pub fnc run(int? a?): void { return; }
                end
                """);
        Ast.Param param = onlyFunction(p).parameters().getFirst();
        assertEquals("Option", param.type().name());
        assertTrue(param.optionTypeSugar());
        assertTrue(param.omittable());
        assertThrows(IllegalArgumentException.class,
                () -> UnloweredDefaultArgValsGuard.reject(p));
    }

    @Test
    void bareOptionValueIsStillRequired() {
        Ast.Program p = Parser.parse("""
                define module m
                  pub fnc run(Option<int> value): void { return; }
                end
                """);
        Ast.Param param = onlyFunction(p).parameters().getFirst();
        assertFalse(param.omittable());
        assertFalse(param.optionTypeSugar());
        assertDoesNotThrow(() -> UnloweredDefaultArgValsGuard.reject(p));
    }

    @Test
    void omittableScalarWithoutDefaultIsDeclarationError() {
        Ast.Program p = Parser.parse("""
                define module m
                  pub fnc run(int value?): void { return; }
                end
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> UnloweredDefaultArgValsGuard.reject(p));
        assertTrue(e.getMessage().contains("needs an explicit default"));
    }

    @Test
    void defaultedOmittableScalarRemainsStaged() {
        Ast.Program p = Parser.parse("""
                define module m
                  @DefaultArgVals(value: 5)
                  pub fnc run(int value?): void { return; }
                end
                """);
        assertEquals(1, onlyFunction(p).parameters().size());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(p));
    }

    @Test
    void unknownAndDuplicateAnnotationKeysFailBeforeRuntime() {
        Ast.Program unknown = Parser.parse("""
                define module m
                  @DefaultArgVals(missing: 5)
                  pub fnc run(int value): void { return; }
                end
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> UnloweredDefaultArgValsGuard.reject(unknown));
        assertTrue(e.getMessage().contains("unknown formal"));

        Ast.Program duplicate = Parser.parse("""
                define module m
                  @DefaultArgVals(value: 1)
                  @DefaultArgVals(value: 2)
                  pub fnc run(int value): void { return; }
                end
                """);
        IllegalArgumentException d = assertThrows(IllegalArgumentException.class,
                () -> UnloweredDefaultArgValsGuard.reject(duplicate));
        assertTrue(d.getMessage().contains("duplicate @DefaultArgVals"));
    }

    @Test
    void inlineReceiverDoesNotConsumeDefaultedParameterSlot() {
        Ast.Program p = Parser.parse("""
                define module m
                  define class Foo as
                    @DefaultArgVals(x: 5)
                    foo(self, int x?): int { return x; }
                  end
                end
                """);
        Ast.ClassDecl klass = p.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        Ast.MethodDecl method = klass.methods().getFirst();
        assertNotNull(method.explicitReceiverType());
        assertEquals(1, method.arity());
        assertEquals("x", method.parameters().getFirst().name());
        assertTrue(method.parameters().getFirst().omittable());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(p));
    }

    @Test
    void constructorAndActorMethodsRemainGated() {
        for (String body : new String[] {
                "define class Foo as constructor(int x?) { } end",
                "define actor Foo as pub foo(self, int x?): void { return; } end"
        }) {
            Ast.Program p = Parser.parse("define module m " + body + " end");
            assertThrows(IllegalArgumentException.class,
                    () -> UnloweredDefaultArgValsGuard.reject(p));
        }
    }

    @Test
    void structuralOrMutableOmittableFormalsRejectImmediately() {
        for (String param : new String[] {
                "structural Box x?",
                "int mut x?"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    Parser.parse("define module m fnc f(" + param
                            + "): void { return; } end"));
        }
    }

    @Test
    void forgedLambdaAstCannotSmuggleOmissionMetadata() {
        Ast.Param forged = new Ast.Param(Ast.TypeRef.simple("int"),
                "value", false, false, true, false);
        assertThrows(IllegalArgumentException.class, () ->
                new Ast.LambdaExpr(List.of(forged), new Ast.NameExpr("value"), null));
    }

    @Test
    void optionalFormalCannotEscapeThroughImportedSignatureShape() {
        Ast.Program p = Parser.parse("""
                define module m
                  pub define class Foo as
                    pub f(Option<int> x?): void { return; }
                  end
                end
                """);
        Ast.ClassDecl klass = p.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () ->
                UnloweredDefaultArgValsGuard.rejectClass(klass));
    }

    @Test
    void optionalTypedLambdaDoesNotBypassChecker() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module m
                  fnc f(): void {
                    val g = fnc (int a?) -> a;
                    return;
                  }
                end
                """));
    }
}
