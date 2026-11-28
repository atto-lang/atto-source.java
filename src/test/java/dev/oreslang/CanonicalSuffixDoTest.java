package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CanonicalSuffixDoTest {
    private static long run(String code) throws Exception {
        // Execute the exact strict-checked AST, not a second parse under the
        // legacy unversioned guest profile. The lease checks source identity.
        var checked = OresCompiler.parseAndTypeCheckStrictAtto(code);
        try (var ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build();
             var lease = OresLanguage.openPrecheckedProgram("suffix-do.atto", code, checked)) {
            return ctx.eval(Source.newBuilder(OresLanguage.ID, code, "suffix-do.atto")
                    .mimeType(OresLanguage.MIME_TYPE).cached(false).build()).asLong();
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
