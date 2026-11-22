package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-source-unit imported class metadata must retain affine ownership. */
final class ImportedClassBorrowOwnershipTest {
    private static final Ast.Program LIBRARY = Parser.parse("""
            define pub class Box as
              pub let int value = 42;
            end
            """);
    private static final Ast.ClassDecl BOX = LIBRARY.modules().stream()
            .flatMap(module -> module.declarations().stream())
            .filter(Ast.ClassDecl.class::isInstance)
            .map(Ast.ClassDecl.class::cast)
            .findFirst().orElseThrow();

    private static Ast.Program consumer(String body) {
        return Parser.parse("""
                import class Box from "./box.atto";
                """ + body);
    }

    private static void check(String body) {
        OwnershipChecker.checkWithImportedClasses(consumer(body), Map.of("Box", BOX));
    }

    @Test
    void sharedBorrowOfImportedClassPreservesOwner() {
        assertDoesNotThrow(() -> check("""
                fnc read(borrow Box view): int { return view.value; }
                pub fnc main(): int {
                  val Box owner = new Box();
                  return read(rt borrow owner) + owner.value;
                }
                """));
    }

    @Test
    void takingImportedClassInvalidatesOriginalOwner() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> check("""
                        pub fnc bad(): int {
                          val Box owner = new Box();
                          val Box current = rt take owner;
                          return owner.value;
                        }
                        """));
        assertTrue(failure.getMessage().toLowerCase().contains("moved"),
                failure.getMessage());
    }

    @Test
    void borrowedImportedClassCannotBePromotedToOwnedReturn() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> check("""
                        fnc leak(borrow Box view): Box { return rt copy view; }
                        pub fnc main(): int { return 42; }
                        """));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow")
                        || failure.getMessage().toLowerCase().contains("copy")
                        || failure.getMessage().toLowerCase().contains("loan"),
                failure.getMessage());
    }

    @Test
    void borrowedImportedClassCannotBeTaken() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> check("""
                        fnc bad(borrow Box view): int {
                          val Box owned = rt take view;
                          return owned.value;
                        }
                        pub fnc main(): int { return 42; }
                        """));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow")
                        || failure.getMessage().toLowerCase().contains("take")
                        || failure.getMessage().toLowerCase().contains("loan"),
                failure.getMessage());
    }
}
