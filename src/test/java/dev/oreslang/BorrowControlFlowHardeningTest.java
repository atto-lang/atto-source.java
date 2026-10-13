package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression: mutually exclusive paths must not inherit sibling move state. */
final class BorrowControlFlowHardeningTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 1;
            end

            """;

    private static void admitted(String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                BOX + "fnc exercise(): void {\n" + body + "\nreturn;\n}\n")));
    }

    private static void rejectedAsMoved(String body) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        BOX + "fnc exercise(): void {\n" + body + "\nreturn;\n}\n")));
        assertTrue(error.getMessage().toLowerCase().contains("moved"),
                error.getMessage());
    }

    @Test
    void independentSwitchArmsDoNotSeeOneAnothersMoves() {
        admitted("""
                  let Box box = new Box();
                  do switch 1
                    case 1 -> {
                      val Box consumed = rt take box;
                    }
                    case 2 -> {
                      stdio.println(box.value);
                    }
                    default -> {
                      stdio.println(box.value);
                    }
                  end
                """);
    }

    @Test
    void anySwitchArmMovePoisonsSubsequentUse() {
        rejectedAsMoved("""
                  let Box box = new Box();
                  do switch 1
                    case 1 -> {
                      val Box consumed = rt take box;
                    }
                    case 2 -> {
                      stdio.println(box.value);
                    }
                  end
                  stdio.println(box.value);
                """);
    }

    @Test
    void catchCannotReadOwnerThatTryMayHaveMoved() {
        rejectedAsMoved("""
                  let Box box = new Box();
                  try {
                    val Box consumed = rt take box;
                  } catch (err) {
                    stdio.println(box.value);
                  }
                """);
    }

    @Test
    void finallyCannotReadOwnerMovedInCatch() {
        rejectedAsMoved("""
                  let Box box = new Box();
                  try {
                    stdio.println(box.value);
                  } catch (err) {
                    val Box consumed = rt take box;
                  } finally {
                    stdio.println(box.value);
                  }
                """);
    }

    @Test
    void independentTryAndCatchReadPathsRemainAdmissible() {
        admitted("""
                  let Box box = new Box();
                  try {
                    stdio.println(box.value);
                  } catch (err) {
                    stdio.println(box.value);
                  } finally {
                    stdio.println(box.value);
                  }
                """);
    }
}
