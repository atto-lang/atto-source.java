package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration regressions against current mainline: eager ordinary class
 * initializers do not inherit unchecked rt effects from pure-looking operands.
 */
final class MainlineClassFieldEffectGateTest {
    @Test
    void rejectRuntimeIntrinsicEvenWithLiteralOperand() {
        for (String operation : java.util.List.of("fail", "take", "borrow", "share", "copy")) {
            String source = """
                    define class Unsafe as
                      final int answer: rt %s 42;
                    end
                    """.formatted(operation);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)),
                    "unproven rt " + operation + " must not enter eager initialization");
            assertTrue(error.getMessage().contains("without a proven transitive effect bound"),
                    "unexpected rt " + operation + " diagnostic: " + error.getMessage());
        }
    }

    @Test
    void rejectNestedUnwindingEffect() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    define class Unsafe as
                      final int answer: 2 + (rt fail "stop");
                    end
                    """)));
        assertTrue(error.getMessage().contains("without a proven transitive effect bound"));
    }

    @Test
    void rejectUnknownTransitiveCall() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    fnc effectful(): int {
                      stdio.stdout.write("not during construction");
                      return 42;
                    }
                    define class Unsafe as
                      final int answer: effectful();
                    end
                    """)));
        assertTrue(error.getMessage().contains("without a proven transitive effect bound"));
    }

    @Test
    void retainPlainScalarInitializer() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                    define class Safe as
                      pub final int answer: 40 + 2;
                    end
                    """)));
    }
}
