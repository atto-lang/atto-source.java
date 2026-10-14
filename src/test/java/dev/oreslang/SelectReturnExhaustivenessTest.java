package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SelectReturnExhaustivenessTest {
    @Test
    void blockingProceduralSelectWithGuaranteedReturnSatisfiesNonVoidCallable() throws Exception {
        String source = """
                pub fnc compute(): int {
                  do select over
                    default -> {
                      return 8;
                    }
                  end
                }

                pub fnc main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source file = Source.newBuilder(OresLanguage.ID, source, "select-exhaustive.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(file);
        }
        assertEquals("8", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void blockingSelectWithoutDefaultIsExhaustiveOnNormalCompletion() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc compute(Channel<int> input): int {
                  do select over
                    when readch input: val x -> {
                      return x;
                    }
                  end
                }
                """)));
    }

    @Test
    void immediateSelectWithoutDefaultMayFallThroughWithoutReturning() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                fnc broken(Channel<int> input): int {
                  try select {
                    when readch input: val x {
                      return x;
                    }
                  }
                }
                """)));
    }

    @Test
    void immediateSelectWithDefaultCanGuaranteeReturn() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc compute(Channel<int> input): int {
                  try select first {
                    when readch input: val x {
                      return x;
                    }
                    default: {
                      return 0;
                    }
                  }
                }
                """)));
    }

    @Test
    void detachedNonblockingSelectCannotSatisfyCallableReturn() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                actor fnc broken(): int {
                  val Channel<int> input = Channel.new<int>(1);
                  do nb select {
                    when readch input: val x {
                      return x;
                    }
                  }
                }
                """)));
    }

    @Test
    void proceduralArmFallthroughDoesNotSatisfyCallableReturn() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                fnc broken(): int {
                  do select among
                    default -> {
                      stdio.println("no return");
                    }
                  end
                }
                """)));
    }
}
