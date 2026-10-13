package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pre-1.0 canonical class-field syntax; local bindings keep their own spelling. */
final class CanonicalClassFieldDeclarationTest {
    private static Ast.ClassDecl parseClass(String classBody) {
        Ast.Program program = Parser.parse("define class Sample as\n" + classBody + "\nend\n");
        return (Ast.ClassDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl)
                .findFirst().orElseThrow();
    }

    @Test
    void parsesAllFourIndependentFieldCapabilities() {
        Ast.ClassDecl klass = parseClass("""
                  int rebindable: 5;
                  mut int rebindableMut: 6;
                  final int fixed: 7;
                  final mut int fixedMut: 8;
                """);
        assertEquals(4, klass.fields().size());
        assertEquals(Ast.BindingKind.LET, klass.fields().get(0).bindingKind());
        assertFalse(klass.fields().get(0).mutableReferent());
        assertEquals(Ast.BindingKind.LET, klass.fields().get(1).bindingKind());
        assertTrue(klass.fields().get(1).mutableReferent());
        assertEquals(Ast.BindingKind.CONST, klass.fields().get(2).bindingKind());
        assertFalse(klass.fields().get(2).mutableReferent());
        assertEquals(Ast.BindingKind.CONST, klass.fields().get(3).bindingKind());
        assertTrue(klass.fields().get(3).mutableReferent());
        for (Ast.FieldDecl field : klass.fields()) assertNotNull(field.initializer());
    }

    @Test
    void finalRequiresDeclarationSiteInitializerAndCannotUseConstructorBody() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> parseClass("final int missing;"));
        assertTrue(failure.getMessage().contains("requires a declaration-site initializer"));

        assertThrows(IllegalArgumentException.class, () -> parseClass("""
                  final mut int missing;
                  constructor() { self.missing = 10; }
                """));
    }

    @Test
    void acceptsLegacyFieldAliasesWhileMigratingTheirInitializationSemantics() {
        Ast.ClassDecl klass = parseClass("""
                  const int fixed: 1;
                  val int mutable: 2;
                  let int rebindable = 3;
                  const mut int fixedMutable = 4;
                """);
        assertEquals(4, klass.fields().size());
        assertEquals(Ast.BindingKind.CONST, klass.fields().get(0).bindingKind());
        assertEquals(Ast.BindingKind.VAL, klass.fields().get(1).bindingKind());
    }

    @Test
    void canonicalFieldDoesNotStealMethodOrLegacyNameColonType() {
        Ast.ClassDecl klass = parseClass("""
                  count: int = 1;
                  final(): int { return 3; }
                  mutValue(mut self)(): void { return; }
                """);
        assertEquals(1, klass.fields().size());
        assertEquals("count", klass.fields().getFirst().name());
        assertEquals(2, klass.methods().size());
    }

    @Test
    void staticCheckerAcceptsCanonicalFinalAndMutableFieldViews() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Sample as
                  int number: 5;
                  final int answer: 42;
                end
                pub fnc main(): int {
                  val Sample value = new Sample();
                  value.number = value.answer;
                  return value.number;
                }
                """)));
    }

    @Test
    void constructorCannotReassignAlreadyInitializedFinalField() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    define class Sample as
                      final int answer: 42;
                      constructor() {
                        self.answer = 7;
                      }
                    end
                    pub fnc main(): int {
                      val Sample value = new Sample();
                      return value.answer;
                    }
                    """)));
        assertTrue(failure.getMessage().contains("final fields cannot be assigned in constructors"));
    }

    @Test
    void duplicateMutFieldQualifierIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> parseClass("final mut mut int bad: 1;"));
        assertTrue(failure.getMessage().contains("duplicate 'mut'"));
    }
}
