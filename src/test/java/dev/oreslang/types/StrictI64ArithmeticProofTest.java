package dev.oreslang.types;

import org.junit.jupiter.api.Test;
import dev.oreslang.parser.Parser;
import static org.junit.jupiter.api.Assertions.*;
import static dev.oreslang.types.RefinementProofs.Interval;

final class StrictI64ArithmeticProofTest {
    @Test void signedExtremaAndUniversalSafety() {
        var min = Interval.exactly(Long.MIN_VALUE);
        var max = Interval.exactly(Long.MAX_VALUE);
        var zero = Interval.exactly(0);
        var one = Interval.exactly(1);
        var neg = Interval.exactly(-1);
        var all = Interval.anyI64();
        assertFalse(max.provesArithmetic("+", one));
        assertFalse(min.provesArithmetic("-", one));
        assertFalse(min.provesArithmetic("*", neg));
        assertFalse(min.provesArithmetic("/", neg));
        assertFalse(all.provesArithmetic("/", zero));
        assertFalse(all.provesArithmetic("/", neg));
        assertTrue(all.provesArithmetic("/", one));
        assertTrue(all.provesArithmetic("*", zero));
        assertFalse(all.provesNegation());
        assertTrue(new Interval(-42,42).provesNegation());
        assertFalse(all.provesArithmetic("unknown", zero));
    }
    @Test void realSourceAdmissionNeverFallsBack() {
        String pre="define dimension Time; define unit Second: Time; type Bounded = int where self >= 1 and self <= 42; ";
        for(String expression:new String[]{"40+2","84 / 2","42 as Bounded","-9223372036854775808"})
            assertNotNull(TypeChecker.check(Parser.parseVerifiedStrictI64(pre+"pub fnc main() -> int { return "+expression+"; }")));
        assertNotNull(TypeChecker.check(Parser.parseVerifiedStrictI64(pre+
            "fnc f(Bounded x) -> int { return x+1; } pub fnc main() -> int { return f(41); }")));
        for(String expression:new String[]{"42/0","-9223372036854775808 / -1","9223372036854775807+1","(9223372036854775807+1)*0"}) {
            var fault=assertThrows(RuntimeException.class,()->Parser.parseVerifiedStrictI64(pre+"pub fnc main() -> int { return "+expression+"; }"));
            assertTrue(fault.getMessage().contains("E_VERIFY_UNPROVEN:"),fault.getMessage());
        }
        for(String body:new String[]{
            "fnc f(int x) -> int { return -x; } pub fnc main() -> int { return f(-42); }",
            "fnc f(int x) -> int { return x+1; } pub fnc main() -> int { return f(41); }",
            "fnc f(int x) -> Bounded { return x as Bounded; } pub fnc main() -> int { return f(42); }",
            "pub fnc main() -> int { val int n = 9223372036854775807+1; return 42; }"}) {
            var fault=assertThrows(RuntimeException.class,()->Parser.parseVerifiedStrictI64(pre+body));
            assertTrue(fault.getMessage().contains("E_VERIFY_UNPROVEN:"),fault.getMessage());
        }
        var ordinary=assertThrows(RuntimeException.class,()->Parser.parseVerifiedStrictI64("pub fnc main() -> int { return 42; }"));
        assertTrue(ordinary.getMessage().contains("E_VERIFY_UNSUPPORTED:"));
        var i32=assertThrows(RuntimeException.class,()->Parser.parseVerifiedStrictI64(pre+"type Narrow = i32 where self > 0; pub fnc main() -> int { return 42; }"));
        assertTrue(i32.getMessage().contains("E_VERIFY_UNSUPPORTED:"));
    }

    @Test void strictReturningConditionals() {
        String pre="define dimension Time; define unit Second: Time; type Bounded = int where self >= 1 and self <= 42; ";
        for(String source:new String[]{"fnc f(int x) -> int { if x != 0 { return 42/x; } return 0; } pub fnc main() -> int { return f(1); }",
            "fnc f(int x) -> int { if x != 0 && 42/x > 0 { return 1; } return 42; } pub fnc main() -> int { return f(0); }",
            "fnc f(int x) -> int { if x == 0 || 42/x > 0 { return 42; } return 0; } pub fnc main() -> int { return f(0); }",
            "fnc f(int x) -> int { if x != 0 && x != -1 { return -9223372036854775808/x; } return 0; } pub fnc main() -> int { return f(2); }",
            "fnc f(int x) -> int { if !(x == 0) { return 42/x; } else { return 42; } } pub fnc main() -> int { return f(0); }",
            "fnc f(int x) -> int { if x < 0 || x > 0 { return 42/x; } return 0; } pub fnc main() -> int { return f(-1); }"})
            assertNotNull(TypeChecker.check(Parser.parseVerifiedStrictI64(pre+source)));
        for(String source:new String[]{"fnc f(int x) -> int { if 42/x > 0 && x != 0 { return 42; } return 0; } pub fnc main() -> int { return f(1); }",
            "fnc f(int x) -> int { if x != 0 { return -9223372036854775808/x; } return 0; } pub fnc main() -> int { return f(2); }",
            "fnc f(int x) -> int { if x != 0 || 42/x > 0 { return 42; } return 0; } pub fnc main() -> int { return f(0); }",
            "fnc f(int x, int y) -> int { if x != 0 { return 42/y; } return 0; } pub fnc main() -> int { return f(1,1); }",
            "fnc f(int x) -> int { if x != 0 || x == 0 { return 42/x; } return 0; } pub fnc main() -> int { return f(1); }"}){
            var error=assertThrows(RuntimeException.class,()->Parser.parseVerifiedStrictI64(pre+source));
            assertTrue(error.getMessage().contains("E_VERIFY_UNPROVEN:"),error.getMessage());
        }
    }

    @Test void executeStrictCheckedAstThroughRealGuestRuntime() throws Exception {
        String pre="define dimension Time; define unit Second: Time; type Bounded = int where self >= 1 and self <= 42; ";
        String[] bodies={
            "pub fnc main() -> int { return 40+2; }",
            "fnc f(int x) -> int { if x != 0 { return 42/x; } return 0; } pub fnc main() -> int { return f(1); }",
            "fnc f(int x) -> int { if x != 0 && 42/x > 0 { return 1; } return 42; } pub fnc main() -> int { return f(0); }",
            "fnc f(int x) -> int { if x == 0 || 42/x > 0 { return 42; } return 0; } pub fnc main() -> int { return f(0); }",
            "fnc f(int x) -> int { if x != 0 && x != -1 { return -9223372036854775808/x; } return 0; } pub fnc main() -> int { return f(2); }",
            "pub fnc main() -> int { let Bounded x = 1; while x < 42 { x = x+1; } return x; }",
            "pub fnc main() -> int { let int x = 1; x = 42; return x; }",
            "pub fnc main() -> int { let int x = 42; while x > 0 { x = x-1; } return x+42; }",
            "pub fnc main() -> int { let int x = 0; while x < 21 { x = x+1; } while x < 42 { x = x+1; } return x; }"
        };
        long[] want={42,42,42,42,-4611686018427387904L,42,42,42,42};
        for(int i=0;i<bodies.length;i++){
            String text=pre+bodies[i],name="strict-guest-"+i+".atto";
            var ast=dev.oreslang.compiler.OresCompiler.analyze(Parser.parseVerifiedStrictI64(text));
            var source=org.graalvm.polyglot.Source.newBuilder(dev.oreslang.OresLanguage.ID,text,name).cached(false).build();
            try(var context=org.graalvm.polyglot.Context.newBuilder(dev.oreslang.OresLanguage.ID).allowExperimentalOptions(true).option("engine.WarnInterpreterOnly","false").build();
                var lease=dev.oreslang.OresLanguage.openPrecheckedProgram(name,text,ast)){
                var value=context.eval(source);
                assertEquals(want[i],value.asLong(),name);
            }
        }
    }

    @Test void rejectStaleOrUnsafeLoopProofs() {
        String pre="define dimension Time; define unit Second: Time; type Bounded = int where self >= 1 and self <= 42; ";
        String[] bodies={
            "pub fnc main() -> int { let int x = 1; x = 0; return 42/x; }",
            "pub fnc main() -> int { let int x = 0; while x >= 0 { x = x+1; } return x; }",
            "pub fnc main() -> int { let Bounded x = 1; while x <= 42 { x = x+1; } return x; }",
            "pub fnc main() -> int { let int x = 1; while x > 0 { x = x-1; } return 42/x; }",
            "fnc f(int x) -> int { let int d = x; if d != 0 { d = 1; return 42/d; } return 42/d; } pub fnc main() -> int { return f(0); }",
            "pub fnc main() -> int { let int x = 1; while x < 42 { x = x+1; x = x+1; } return x; }"
        };
        String[] codes={"E_VERIFY_UNPROVEN","E_VERIFY_UNPROVEN","E_REFINEMENT_BINDING","E_VERIFY_UNPROVEN","E_VERIFY_UNPROVEN","E_VERIFY_UNSUPPORTED"};
        for(int i=0;i<bodies.length;i++){
            String source=pre+bodies[i];
            var fault=assertThrows(RuntimeException.class,()->Parser.parseVerifiedStrictI64(source));
            assertTrue(fault.getMessage().contains(codes[i]+":"),fault.getMessage());
        }
    }

    @Test void compareToConcreteCheckedOperations() {
        for (long lo=-4; lo<=4; lo++) for (long hi=lo; hi<=4; hi++)
            for (long rlo=-4; rlo<=4; rlo++) for(long rhi=rlo; rhi<=4; rhi++)
                for (String op : new String[]{"+","-","*","/"}) {
                    boolean safe=true;
                    for(long x=lo;x<=hi;x++) for(long y=rlo;y<=rhi;y++) {
                        try {
                            switch(op) {
                                case "+" -> Math.addExact(x,y);
                                case "-" -> Math.subtractExact(x,y);
                                case "*" -> Math.multiplyExact(x,y);
                                case "/" -> { if(y==0 || x==Long.MIN_VALUE && y==-1) throw new ArithmeticException(); }
                            }
                        } catch(ArithmeticException fault) { safe=false; }
                    }
                    assertEquals(safe, new Interval(lo,hi).provesArithmetic(op,new Interval(rlo,rhi)));
                }
    }
}
