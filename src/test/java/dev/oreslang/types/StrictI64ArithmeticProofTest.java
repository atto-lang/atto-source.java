package dev.oreslang.types;

import org.junit.jupiter.api.Test;
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
