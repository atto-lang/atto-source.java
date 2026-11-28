import dev.oreslang.runtime.StrictIntegerArithmetic;
import java.math.BigInteger;
import java.util.SplittableRandom;
public class PowerProbe {
    static long pass, overflow;
    static void check(long b,int e) {
        BigInteger exact = BigInteger.valueOf(b).pow(e);
        boolean expectedOverflow = exact.bitLength() > 63;
        long got;
        try { got = StrictIntegerArithmetic.pow(b,e); }
        catch (ArithmeticException error) {
            if (!expectedOverflow) throw new AssertionError("unexpected overflow "+b+"^"+e,error);
            overflow++; return;
        }
        if (expectedOverflow || !exact.equals(BigInteger.valueOf(got))) throw new AssertionError("wrong result "+b+"^"+e+": "+got);
        pass++;
    }
    public static void main(String[] args) {
        long[] edges={Long.MIN_VALUE,Long.MIN_VALUE+1,-3037000500L,-3037000499L,-2,-1,0,1,2,3037000499L,3037000500L,Long.MAX_VALUE-1,Long.MAX_VALUE};
        for(long b:edges) for(int e=0;e<=64;e++) check(b,e);
        var rng=new SplittableRandom(20261126);
        for(int i=0;i<40000;i++) check(rng.nextLong(),rng.nextInt(0,65));
        for(int i=0;i<40000;i++) check(rng.nextInt(-1000,1001),rng.nextInt(0,65));
        for(int i=0;i<20000;i++) check(rng.nextLong(-3037000500L,3037000501L),2);
        for(long b:edges) {try {StrictIntegerArithmetic.pow(b,-1); throw new AssertionError("negative exponent accepted");} catch(IllegalArgumentException expected) {}}
        for(long b: new long[]{-1,0,1}) {
            long expected=b;
            if(StrictIntegerArithmetic.pow(b,Long.MAX_VALUE)!=expected) throw new AssertionError("large exponent");
        }
        System.out.printf("Actual Java helper power: %d exact successes, %d exact overflow denials, %d bounded-exponent comparisons; 13 negative-exponent denials; 3 huge-exponent identities%n",pass,overflow,pass+overflow);
    }
}
