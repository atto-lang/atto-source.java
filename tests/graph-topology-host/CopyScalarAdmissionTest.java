import dev.oreslang.types.CopyScalarAdmission;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CopyScalarAdmissionTest {
    private static int passed;
    private static void check(boolean result) {
        if (!result) throw new AssertionError("case " + (passed + 1));
        passed++;
    }
    private static final class MutableBigInt extends BigInteger {
        private static final long serialVersionUID = 1L;
        MutableBigInt() { super("1"); }
    }
    private static final class MutableDecimal extends BigDecimal {
        private static final long serialVersionUID = 1L;
        MutableDecimal() { super("1"); }
    }
    private static final class HostNumber extends Number {
        private static final long serialVersionUID = 1L;
        public int intValue() { throw new AssertionError("host callback"); }
        public long longValue() { throw new AssertionError("host callback"); }
        public float floatValue() { throw new AssertionError("host callback"); }
        public double doubleValue() { throw new AssertionError("host callback"); }
    }
    public static void main(String[] args) {
        for (Object value : new Object[]{(byte) 1, (short) 2, 3, 4L, 5.0f, 6.0,
                BigInteger.TEN, BigDecimal.ONE}) {
            check(CopyScalarAdmission.isImmutableNumber(value));
            check(CopyScalarAdmission.isImmutableScalar(value));
        }
        for (Object value : new Object[]{new AtomicInteger(1), new AtomicLong(1),
                new MutableBigInt(), new MutableDecimal(), new HostNumber(), new Object(), null}) {
            check(!CopyScalarAdmission.isImmutableNumber(value));
            check(!CopyScalarAdmission.isImmutableScalar(value));
        }
        check(CopyScalarAdmission.isImmutableScalar("text"));
        check(CopyScalarAdmission.isImmutableScalar(true));
        check(!CopyScalarAdmission.isImmutableNumber("text"));
        check(!CopyScalarAdmission.isImmutableNumber(true));
        System.out.println("PASS " + passed + " scalar admission assertions");
    }
}
