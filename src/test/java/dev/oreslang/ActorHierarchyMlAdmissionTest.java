package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-compiler actor hierarchy admission targets. This suite intentionally
 * refuses to count parser success as runtime or ML/autograd support.
 * Review against actor protocol PR #185 and sealed-state PR #1804.
 */
final class ActorHierarchyMlAdmissionTest {
    @Test
    void defineActorNormalizesToSharedNominalClassWithoutAmbientSuperpowers() {
        Ast.Program source = Parser.parse("""
                define actor A1 as
                  pub value(): int { return 42; }
                end
                """);
        Ast.ClassDecl a1 = (Ast.ClassDecl) source.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass && klass.name().equals("A1"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.SHARED, a1.actorKind());
    }

    @Test
    void aCompatibleActorSuperclassCanSupplyAnInheritedMethod() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Base {
                  pub fnc value(): int { return 42; }
                }
                shared actor A1 extends Base {
                  pub fnc derived(): int { return self.value(); }
                }
                """)));
    }

    @Test
    void actorCannotWeakenTheInheritedExecutionDomain() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        isoactor Parent {}
                        shared actor A1 extends Parent {}
                        """)));
        assertTrue(error.getMessage().contains("isolation kind"), error.getMessage());
    }

    @Test
    void rejectOrdinaryObjectAndMultipleConcreteActorSuperclasses() {
        for (String source : new String[] {
                """
                define class Plain as
                end
                define actor A1 extends Plain as
                  pub value(): int { return 42; }
                end
                """,
                """
                shared actor Left {}
                shared actor Right {}
                shared actor A1 extends Left, Right {}
                """
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    "Actor ancestry must not gain a nonactor or a second concrete parent");
        }
    }

    @Test
    void rejectOutOfTurnStateAndModelSyntaxBeforeAssumingSupport() {
        for (String source : new String[] {
                """
                define actor A1 as
                  state { int count = 1; }
                  pub leak(): int { return self.count; }
                end
                """,
                """
                define model Neural as
                  Dense(2,1)
                end
                """
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    "Staged actor-state/model grammar must not silently imply executable ownership");
        }
    }
}
