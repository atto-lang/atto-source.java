package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MoveOnlyLocalReinitTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 7;
            end
            """;

    @Test
    void writableLocalCanBeReinitializedAfterOwnershipTransfer() throws Exception {
        String program = BOX + """
                pub routine main(): void {
                  let Box box = new Box();
                  val Box transferred = rt take box;
                  box = new Box();
                  stdio.stdout.write(box.value + transferred.value);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(output).build()) {
            context.eval(Source.newBuilder("ores", program, "reinit-local.ores").build());
        }
        assertEquals("14", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void movedLocalCannotBeReadOnItsOwnReinitializationRhs() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): void {
                          let Box box = new Box();
                          val Box transferred = rt take box;
                          box = box;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("moved"), failure.getMessage());
    }

    @Test
    void fixedBindingCannotBeReinitializedAfterMove() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): void {
                          val Box box = new Box();
                          val Box transferred = rt take box;
                          box = new Box();
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("fixed binding"), failure.getMessage());
    }
}
