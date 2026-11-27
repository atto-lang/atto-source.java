package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CanonicalSixConditionalMainTest {
    private static void assertResult(String body, int expected) throws Exception {
        String source = "pub fnc main(): int { " + body + " }";
        var checked = OresCompiler.parseAndTypeCheckStrictAtto(source);
        String name = "conditional.atto";
        // Explicit canonical source admission; never reparse via the legacy
        // launcher and never reuse a checked AST across different source bytes.
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, checked);
             Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            var value = context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
            assertTrue(value.fitsInInt(), "expected guest int: " + source);
            assertEquals(expected, value.asInt(), source);
        }
    }

    @Test void allSixFormsBothPredicates() throws Exception {
        for (boolean p : new boolean[]{true, false}) {
            int expected = p ? 5 : 6;
            assertResult("return if " + p + " then 5 else 6 fi;", expected);
            assertResult("return " + p + " ? 5 : 6;", expected);
            assertResult("return " + p + " ? { return 5; } : { return 6; };", expected);
            assertResult("do if " + p + " then return 5; else return 6; fi", expected);
            assertResult("if " + p + " do return 5; else return 6; fi", expected);
            assertResult("if " + p + " { return 5; } else { return 6; }", expected);
        }
    }

    @Test void elifAndArmLocalReturn() throws Exception {
        assertResult("return if false then 1 elif true then 42 else 7 fi;", 42);
        assertResult("do if false then return 1; elif true then return 42; else return 7; fi", 42);
        assertResult("if false do return 1; elif true do return 42; else return 7; fi", 42);
        assertResult("val int v = true ? { return 40; } : { return 7; }; return v + 2;", 42);
        assertResult("do if true then if false { return 7; } fi return 42;", 42);
    }

    @Test void malformedCanonicalDelimitersReject() {
        for (String body : new String[]{
                "do if true do return 5; else return 6; fi",
                "do if true { return 5; } else { return 6; }",
                "do if false then return 5; elif true do return 6; fi",
                "if false do return 5; elif true then return 6; fi",
                "return if true then 5 fi;",
                "return if false then 5 elif true do 6 else 7 fi;",
                "if true { return 5; } fi"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto("pub fnc main(): int { " + body + " }"), body);
        }
    }

    @Test void boundedValueElif() {
        String source = "pub fnc main(): int { return if false then 0 "
                + "elif false then 0 ".repeat(127) + "else 42 fi; }";
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(source));
    }
}
