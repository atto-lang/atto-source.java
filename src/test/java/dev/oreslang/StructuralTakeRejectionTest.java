package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Readonly structural parameter passing never consumes an owned caller value. */
final class StructuralTakeRejectionTest {
    private static final String SOURCE = """
            define class Sample as
              pub val int id = 3;
            end
            fnc read(struct{id: int} view): int { return view.id; }
            fnc fail(): int {
              val Sample owner = new Sample();
              return read(rt take owner);
            }
            """;

    @Test
    void directTransferIntoAnonymousStructuralParameterFailsClosed() {
        var program = assertDoesNotThrow(() -> Parser.parse(SOURCE));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
    }
}
