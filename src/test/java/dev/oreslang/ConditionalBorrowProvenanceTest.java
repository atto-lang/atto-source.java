package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Ownership refinements on the right side of && must not create fresh owners. */
final class ConditionalBorrowProvenanceTest {
    @Test
    void refinementsChainedAcrossAndShareOneMoveOnlyOwnershipRoot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Animal as
                        end

                        define class Dog extends Animal as
                        end

                        fnc consume(Animal animal): void { return; }

                        fnc bad(Option<Animal> maybe): void {
                          if maybe matches Some(pet) && pet is type Dog dog then
                            consume(dog);
                            consume(pet);
                          fi
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("moved")
                || error.getMessage().contains("move"), error.getMessage());
    }
}
