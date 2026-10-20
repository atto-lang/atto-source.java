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

final class TupleRuntimeKindLanguageTest {
    private static String execute(String code) throws Exception {
        var checked = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(checked);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, code, "tuple-runtime-kind.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
    }

    @Test
    void guestRetainsKindAcrossLiteralReturnEqualityAndForOf() throws Exception {
        String source = """
                fnc pair(): [int, int] {
                  return tuple {10, 11};
                }
                fnc compact(): [int, int] {
                  return (10, 11);
                }
                fnc sum(): int {
                  let int total = 0;
                  for let value of pair() do
                    total = total + value;
                  done
                  return total;
                }
                fnc empty(): [] {
                  return tuple {};
                }
                pub fnc main(): void {
                  stdio.println(pair() == compact());
                  stdio.println(pair()[0] + compact()[1]);
                  stdio.println(sum());
                  stdio.println(empty() == ());
                  return;
                }
                """;
        assertEquals("true\n21\n21\ntrue", execute(source));
    }

    @Test
    void typedPatternRunsOnTrueTupleAndArrayPatternIsRejected() throws Exception {
        String source = """
                fnc take([int, string] input): int {
                  match input over
                    on tuple {number, label} -> { return number; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(take((7, "seven")));
                  return;
                }
                """;
        assertEquals("7", execute(source));

        // Regardless of host List implementation, an Array must not enter
        // a tuple match through a merely structural positional check.
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): int {
                  match [1, 2] over
                    on tuple {x, y} -> { return x; }
                    on _ -> { return 0; }
                  end
                }
                """)));
    }

    @Test
    void explicitLegacyTupleCallStillMakesTupleCarrier() throws Exception {
        String source = """
                pub fnc main(): void {
                  const a = tuple(3, 4);
                  const b = tuple {3, 4};
                  stdio.println(a == b);
                  return;
                }
                """;
        assertEquals("true", execute(source));
    }

    @Test
    void arraysCannotBeReturnedAsTupleWithoutExplicitKindProof() throws Exception {
        String source = """
                fnc forged(): [int, int] {
                  return [1, 2];
                }
                pub fnc main(): void {
                  stdio.println(forged()[0]);
                  return;
                }
                """;
        // Static rejection is preferable; if contextual typing still permits
        // the historical spelling, the runtime return boundary must fail closed.
        try {
            TypeChecker.check(Parser.parse(source));
        } catch (IllegalArgumentException expectedStaticRejection) {
            return;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "forged-array-return.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            assertThrows(Exception.class, () -> context.eval(guest));
        }
    }
}
