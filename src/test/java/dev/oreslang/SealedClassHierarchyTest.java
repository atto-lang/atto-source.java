package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Sealed / non-sealed / final class hierarchies (semantics ported from #1023). */
final class SealedClassHierarchyTest {
    private static void accepts(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejects(String source, String message) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(message), failure::getMessage);
    }

    @Test void sealedParentAdmitsSameModuleNonSealedSealedOrFinalChildren() {
        accepts("""
                define sealed class Base as
                  pub id(): int { return 1; }
                end
                define non-sealed class Open extends Base as
                  pub id(): int { return 2; }
                end
                define final class Closed extends Base as
                end
                define class FromOpen extends Open as
                end
                """);
    }

    @Test void directChildOfSealedMustChooseSealedNonSealedOrFinal() {
        rejects("""
                define sealed class Base as
                end
                define class Leak extends Base as
                end
                """, "must be declared sealed, non-sealed or final");
    }

    @Test void finalClassCannotBeExtended() {
        rejects("""
                define final class Base as
                end
                define class Child extends Base as
                end
                """, "cannot be extended");
    }

    @Test void sealedClassNeedsADirectSubclassAndNonSealedNeedsASealedParent() {
        rejects("define sealed class Base as\nend\n", "at least one direct subclass");
        rejects("""
                define class Plain as
                end
                define non-sealed class Child extends Plain as
                end
                """, "must directly extend a sealed class");
    }
}
