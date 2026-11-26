package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CanonicalSuffixDoTest {
    private static long run(String code) throws Exception {
        TypeChecker.check(Parser.parseStrictAtto(code));
        try (var ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            return ctx.eval(Source.newBuilder(OresLanguage.ID, code, "suffix-do.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build()).asLong();
        }
    }
    @Test void imperativeReturnExitsFunction() throws Exception {
        assertEquals(42, run("pub fnc main(): int { if true do return 42; fi return 0; }"));
    }
    @Test void elifDoAndElseRemainImperative() throws Exception {
        assertEquals(42, run("pub fnc main(): int { if false do return 1; elif true do return 42; else return 2; fi }"));
    }
    @Test void negatedHeadsRetainTheirPolarity() throws Exception {
        assertEquals(42, run("pub fnc main(): int { ifnot true do return 1; fi unless false do return 42; fi return 0; }"));
    }
    @Test void valueThenAndTernaryRemainValues() throws Exception {
        assertEquals(42, run("pub fnc main(): int { val int n = if true then 20 else 1 fi; return n + (true ? 22 : 2); }"));
    }
}
