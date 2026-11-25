package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class CanonicalConditionalJoinCurrentMainTest {
    private static void check(String source) {
        OresCompiler.parseAndTypeCheckStrictAtto(source);
    }

    @Test
    void namedCallableNeedsItsOwnReturn() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc choose(): int {
                    if true then return 42; else ^ 7; fi
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc choose(): int { if true then return 42; fi }
                """));
        assertDoesNotThrow(() -> check("""
                fnc choose(bool ready): int {
                    if ready then return 1; else ^ 2; fi
                    ^ if ready then return 42; else ^ 7; fi;
                }
                """));
    }

    @Test
    void checkedGuestExecutesDistinctJoinPoints() throws Exception {
        String source = """
                fnc choose(bool ready): int {
                    if ready then return 1; else ^ 2; fi
                    if false then return 99; fi
                    do if ready then return 42; fi
                    return if ready then 0 else 7 fi;
                }
                pub routine main(): void {
                    if true then do stdio.stdout.write(choose(true)); fi
                    stdio.stdout.write(":");
                    stdio.stdout.write(choose(false));
                }
                """;
        var checked = OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        // Execute the explicitly checked AST, not the legacy parser's
        // interpretation of the same tokens. No linked-source claim.
        String name = "canonical-conditional-join.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, checked);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        assertEquals("42:7", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void braceAndKeywordScopesAreIndependent() {
        assertDoesNotThrow(() -> check("""
                fnc choose(): int {
                    do if true then
                        if false { return 1; }
                    fi
                    return 2;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto("""
                fnc choose(): int {
                    do if true then
                        if false { return 1;
                    fi
                    return 2;
                }
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                fnc choose(): int { if true { return 1; } fi return 2; }
                """));
    }

    @Test
    void valueArmsMustProduceValuesAndActionArmsMustBeMarked() {
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc choose(): int { return if true then 42 fi; }"));
        assertThrows(IllegalArgumentException.class, () -> check(
                "fnc choose(): int { return if true then return void; else 7 fi; }"));
        assertThrows(IllegalArgumentException.class, () -> check(
                "pub routine main(): void { if true then stdio.stdout.write(1); fi }"));
        assertDoesNotThrow(() -> check(
                "pub routine main(): void { if true then do stdio.stdout.write(1); fi }"));
    }

    @Test
    void negationPreservesConditionBindingAdmission() {
        assertDoesNotThrow(() -> check(
                "fnc choose(): int { return unless true then 1 else 2 fi; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(
                "fnc choose(): int { do unless let x = Some(1) then return x; fi return 0; }"));
    }
}
