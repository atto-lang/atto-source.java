package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StagedArityOverlapV3Test {

    @Test void scalarOptionalAndRequiredOverloadsCannotOverlap() {
        Ast.Program program = Parser.parse("""
                define module m
                  fnc f(int a): int { return a; }
                  fnc f(int a, int b?): int { return a; }
                end
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StagedArityOverlapV3.check(program));
        assertTrue(failure.getMessage().contains("overlapping caller-visible arity 1"));
    }

    @Test void namedAnnotationDefaultCanCreateArityCollision() {
        Ast.Program program = Parser.parse("""
                define module m
                  fnc f(int a): int { return a; }
                  @DefaultArgVals(b: 5)
                  fnc f(int a, int b): int { return a; }
                end
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StagedArityOverlapV3.check(program));
        assertTrue(failure.getMessage().contains("overlapping caller-visible arity 1"));
    }

    @Test void receiverSyntaxDoesNotPreventCollision() {
        Ast.Program program = Parser.parse("""
                define module m
                  define class Foo as
                    foo(self, int a): int { return a; }
                    foo(self, int a, int b?): int { return a; }
                  end
                end
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StagedArityOverlapV3.check(program));
        assertTrue(failure.getMessage().contains("overlapping caller-visible arity 1"));
    }

    @Test void actorMethodsShareReceiverErasedArityRule() {
        Ast.Program program = Parser.parse("""
                define module m
                  define actor Worker as
                    pub handle(self, int a): void { return; }
                    pub handle(int a, Option<int> b?): void { return; }
                  end
                end
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StagedArityOverlapV3.check(program));
        assertTrue(failure.getMessage().contains("overlapping caller-visible arity 1"));
    }

    @Test void staticAndInstanceOverloadNamespacesAreSeparate() {
        Ast.Program program = Parser.parse("""
                define module m
                  define class Foo as
                    pub foo(self, int a?): void { return; }
                    pub static fnc foo(int a): void { return; }
                  end
                end
                """);
        assertDoesNotThrow(() -> StagedArityOverlapV3.check(program));
    }

    @Test void disjointRequiredAritySlotsStayIndependent() {
        Ast.Program program = Parser.parse("""
                define module m
                  fnc f(int a): int { return a; }
                  fnc f(int a, int b): int { return b; }
                end
                """);
        assertDoesNotThrow(() -> StagedArityOverlapV3.check(program));
    }

    @Test void sameMethodNamesInUnrelatedClassesStayIndependent() {
        Ast.Program program = Parser.parse("""
                define module m
                  define class Foo as
                    foo(self, int x?): void { return; }
                  end
                  define class Bar as
                    foo(self, int x): void { return; }
                  end
                end
                """);
        assertDoesNotThrow(() -> StagedArityOverlapV3.check(program));
    }

    @Test void fullTypeCheckerStillFailsClosedAfterPreliminaryOverlapPass() {
        Ast.Program program = Parser.parse("""
                define module m
                  fnc f(Option<int> value?): void { return; }
                end
                """);
        assertDoesNotThrow(() -> StagedArityOverlapV3.check(program));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(e.getMessage().contains("staged"));
    }
}
