package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MovedLetReinitializationTest {
    private static final String BOX = """
            define class Box as
              pub val int number;
            end

            fnc consume(Box value): void { return; }
            """;

    @Test
    void moveOnlyLetMayBeReinitializedWithOwnedReplacement() throws Exception {
        assertEquals("9", run(BOX + """
                pub routine main(): void {
                  let Box current = new Box(1);
                  consume(current);
                  current = new Box(9);
                  stdio.stdout.write(current.number);
                  return;
                }
                """));
    }

    @Test
    void loopMayMoveThenReinitializeOwnedBindingEachIteration() throws Exception {
        assertEquals("3", run(BOX + """
                pub routine main(): void {
                  let Box current = new Box(1);
                  let int i = 0;
                  loop do
                    if i >= 2 then break; fi
                    consume(current);
                    current = new Box(i + 2);
                    i = i + 1;
                  done
                  stdio.stdout.write(current.number);
                  return;
                }
                """));
    }

    @Test
    void movedBindingStillCannotBeReadInsideItsReplacement() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc id(Box value): Box { return value; }
                        fnc bad(): void {
                          let Box current = new Box(1);
                          consume(current);
                          current = id(current);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("moved"), failure.getMessage());
    }

    @Test
    void valCannotBeReinitializedAndOwnedLeaseCannotBeBypassed() {
        IllegalArgumentException fixed = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): void {
                          val Box current = new Box(1);
                          consume(current);
                          current = new Box(3);
                          return;
                        }
                        """)));
        assertTrue(fixed.getMessage().contains("fixed binding")
                || fixed.getMessage().contains("cannot reassign val"), fixed.getMessage());

        IllegalArgumentException borrowed = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): void {
                          let Box current = new Box(1);
                          val borrow Box view = rt borrow current;
                          current = new Box(3);
                          stdio.stdout.write(view.number);
                          return;
                        }
                        """)));
        assertTrue(borrowed.getMessage().contains("borrow"), borrowed.getMessage());
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "reinitialize-moved.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
