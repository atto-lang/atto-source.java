package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ContextualIntGuestTest {
    static String source(String context,String expr,String body){
        return "define class Scale as pub static fnc apply::("+context+")(int value) -> int { return "+expr+"; } end fnc main() -> int { "+body+" }";
    }
    static String single(String body){return source("int factor","factor * value",body);}
    static void guest(String src,long expected){
        try(Context c=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()){
            var value=c.eval(Source.newBuilder(OresLanguage.ID,src,"contextual-int.atto").mimeType(OresLanguage.MIME_TYPE).buildLiteral());
            assertEquals(expected,value.asLong(),src);
        }
    }
    @Test void directAndReusableGuest(){
        guest(single("return Scale::(6).apply(7);"),42);
        guest(single("const x = Scale::(2); const y = Scale::(3); return x.apply(4) + y.apply(4);"),20);
        guest(source("int factor, int offset","factor * value + offset","const x = Scale::(2,3); const y = Scale::(4,5); return x.apply(7) + y.apply(7);"),50);
        guest(single("const x = Scale::(6); const y = x; const z = y; return z.apply(7);"),42);
        guest(single("const x = Scale::(2 + 4); return x.apply(7);"),42);
    }
    @Test void malformedSourceFailsBeforeGuest(){
        for(String body:new String[]{
            "return Scale::(true).apply(7);",
            "return Scale::(2,3).apply(7);",
            "return Scale::(2).apply();",
            "return Scale::(2).other(7);",
            "const x = Scale::(2); return x;",
            "const x = Scale::(2); const x = x; return x.apply(7);",
            "const x = Scale::(2); const y = x; y = 3; return x.apply(7);",
            "return Scale::(factor=2).apply(7);",
            "return Scale::(2).apply([7);",
            "return atto_context_v1_member(2,7);",
            "const x = Scale::(6); return x.apply(7); const y = x;",
            "return Scale::(2).apply(\"7\");"
        })assertThrows(IllegalArgumentException.class,()->OresCompiler.parseAndTypeCheckNumericProfile(single(body),true),body);
    }
    @Test void ordinaryGuestUnaffected(){guest("pub fnc main():int { return 42; }",42);}
}
