package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TupleRuntimeTagGuestTest {
    private static String runGuest(String source) throws Exception {
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "tuple-runtime-tag.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .strip();
    }

    @Test
    void identicalTupleSpellingsUseGuestValueEqualityAndExactPatterns() throws Exception {
        String guest = """
                fnc shaped(): [int, string] {
                  return tuple {7, "seven"};
                }
                fnc legacyContextualReturn(): [int, string] {
                  return [9, "nine"];
                }
                fnc matchPair(): int {
                  match shaped() over
                    on (n, label) -> { return n; }
                  end
                }
                fnc matchEntry(): int {
                  const entry = Entry.of("nested", tuple {3, 4});
                  match entry.value over
                    on tuple {n, m} -> { return n + m; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(tuple {} == ());
                  stdio.println(tuple {42} == (42,));
                  stdio.println(tuple {1, 2} == (1, 2));
                  stdio.println(matchPair());
                  stdio.println(legacyContextualReturn()[0]);
                  stdio.println(matchEntry());
                  return;
                }
                """;
        assertEquals("true\ntrue\ntrue\n7\n9\n7", runGuest(guest));
    }

    @Test
    void suspendableTupleResultsSurviveAwaitAndTupleSpread() throws Exception {
        String guest = """
                async fnc tupleResult(): (int, int) {
                  return tuple {4, 2};
                }
                fnc add(int a, int b): int {
                  return a + b;
                }
                pub fnc main(): void {
                  const pair = await tupleResult();
                  stdio.println(add(...pair));
                  match pair over
                    on tuple {x, y} -> { stdio.println(x * 10 + y); }
                  end
                  return;
                }
                """;
        assertEquals("6\n42", runGuest(guest));
    }

    @Test
    void arraysNeverStaticallyImpersonateTuplesOrEntries() {
        String guest = """
                fnc takes([int, int] tupleValue): int {
                  return tupleValue[0];
                }
                fnc bad(): int {
                  return takes([1, 2]);
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));

        String pattern = """
                fnc bad(): int {
                  match arr[1, 2] over
                    on tuple {a, b} -> { return a; }
                    on _ -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(pattern)));
        assertTrue(error.getMessage().contains("tuple pattern requires"),
                error.getMessage());
    }
}
