package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit strict-source checked-AST guest bridge; not Native Image evidence. */
final class StoredPairStrictGuestTest {
    private static String source(String body) {
        return "pub fnc main(): int { " + body + " }";
    }
    private static long run(String body) throws Exception {
        String source = source(body);
        var checked = OresCompiler.parseAndTypeCheckStrictAtto(source);
        String filename = "stored-pair-strict.atto";
        try (var lease = OresLanguage.openPrecheckedProgram(filename,source,checked);
             var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            return context.eval(Source.newBuilder(OresLanguage.ID,source,filename).build()).asLong();
        }
    }
    @Test void sameNameFormsAndFactoryShareCheckedScalarAst() {
        String a=source("const x := 42; const Pair<int> p=x; return p.value;");
        String b=source("const x = x := 42; const Pair<int> p=x; return p.value;");
        String c=source("const x = Pair.of(\"x\",42); const Pair<int> p=x; return p.value;");
        assertEquals(Parser.parseStrictAtto(a),Parser.parseStrictAtto(b));
        assertEquals(Parser.parseStrictAtto(a),Parser.parseStrictAtto(c));
    }
    @Test void actualStrictCheckedGuestExecution() throws Exception {
        for (String body : new String[]{
                "const x := 42; const Pair<int> p=x; return p.value;",
                "const x=x:=42; const Pair<int> p=x; return p.value;",
                "const x=Pair.of(\"x\",42); const Pair<int> p=x; return p.value;",
                "const x=y:=42; return if x.key eq \"y\" then x.value else 99 fi;",
                "const x=\"not an identifier\" := 42; return if x.key eq \"not an identifier\" then x.value else 99 fi;",
                "const x:=40; const y:=2; return x.value+y.value;"
        }) assertEquals(42L,run(body),body);
    }
    @Test void rejectUnsupportedOrUnsafeBeforeGuestExecution() {
        for (String body : new String[]{
                "const x:=1; const x:=2; return 0;",
                "const x:=x; return 0;",
                "const x=x:=x; return 0;",
                "const x:=true; return 0;",
                "const x:=42; return x;",
                "const x:=42; x.value=7; return 0;",
                "const x:=42; return x.key;",
                "const x:=42; return x.other;",
                "const x=Pair.of(7,42); return 0;",
                "const int x=42; const Pair<int> p=x; return 0;",
                "const x:=42; return __atto_pair_local_0;",
                "const x:=9223372036854775808; return 0;",
                "const Pair:=42; return 0;"
        }) assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(source(body)),body);
    }
}
