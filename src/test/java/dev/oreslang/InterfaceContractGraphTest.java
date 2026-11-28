package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Production TypeChecker coverage, not a reference-model test. */
final class InterfaceContractGraphTest {
    @Test
    void repeatedDiamondPreservesAllInheritedObligations() {
        StringBuilder source = new StringBuilder("define interface Root as fnc ping(): int; end\n");
        String parent = "Root";
        for (int i = 0; i < 7; i++) {
            String left = "Left" + i, right = "Right" + i, join = "Join" + i;
            source.append("define interface ").append(left).append(" extends ").append(parent).append(" as end\n");
            source.append("define interface ").append(right).append(" extends ").append(parent).append(" as end\n");
            source.append("define interface ").append(join).append(" extends ").append(left).append(", ").append(right).append(" as end\n");
            parent = join;
        }
        String graph = source.toString();
        String terminal = parent;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(graph +
                "define class Good implements " + terminal + " as pub ping(): int { return 42; } end")));
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(graph +
                "define class Bad implements " + terminal + " as end")));
        assertTrue(error.getMessage().contains("does not implement interface"), error.getMessage());
    }

    @Test
    void inheritanceCycleIsRejectedEvenWhenNoClassUsesIt() {
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define interface A extends B as end
                define interface B extends A as end
                """)));
        assertTrue(error.getMessage().contains("inheritance cycle"), error.getMessage());
    }

    @Test
    void incompatibleInheritedGenericArityStillRejects() {
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define interface A as fnc run(int value): int; end
                define interface B as fnc run<T>(T value): T; end
                define interface C extends A, B as end
                """)));
        assertTrue(error.getMessage().contains("incompatible generic arity"), error.getMessage());
    }
}
