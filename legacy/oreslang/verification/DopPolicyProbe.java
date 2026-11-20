import dev.oreslang.types.DataOrientedBorrowChecker;
import dev.oreslang.types.DataOrientedBorrowChecker.*;
import java.io.*;
import java.util.*;

/** Portable policy probe; no Truffle/Graal runtime needed. */
public final class DopPolicyProbe {
    static String optional(String s) { return s.equals("-") ? null : new String(HexFormat.of().parseHex(s), java.nio.charset.StandardCharsets.UTF_8); }
    static Loan loan(String[] s, int i) {
        return new Loan(new Place(Layout.values()[Integer.parseInt(s[i])], optional(s[i+1]),
            optional(s[i+2]), optional(s[i+3]), optional(s[i+4]),
            s[i+5].equals("-") ? null : new Range(Long.parseLong(s[i+5]), Long.parseLong(s[i+6])),
            Long.parseLong(s[i+7])), Access.values()[Integer.parseInt(s[i+8])]);
    }
    static void requireRejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | NullPointerException e) { return; }
        throw new AssertionError("invalid loan set accepted");
    }
    public static void main(String[] args) throws Exception {
        List<Loan> malformed = Arrays.asList((Loan)null);
        requireRejected(() -> DataOrientedBorrowChecker.canRunConcurrently(malformed, List.of()));
        requireRejected(() -> DataOrientedBorrowChecker.canRunConcurrently(List.of(), malformed));
        requireRejected(() -> DataOrientedBorrowChecker.requireInternallyValid(malformed));
        try (var input = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    String[] s = line.split("\t", -1);
                    Loan a = loan(s,0), b = loan(s,9);
                    boolean conflict = DataOrientedBorrowChecker.conflicts(a,b);
                    if (DataOrientedBorrowChecker.canRunConcurrently(List.of(a),List.of(b)) == conflict)
                        throw new AssertionError("set/pair policy mismatch");
                    boolean rejected = false;
                    try { DataOrientedBorrowChecker.requireInternallyValid(List.of(a,b)); }
                    catch (IllegalArgumentException e) { rejected = true; }
                    if (rejected != conflict) throw new AssertionError("internal/pair policy mismatch");
                    DataOrientedBorrowChecker.requireCurrentEpoch(a.place(),a.place().epoch());
                    requireRejected(() -> DataOrientedBorrowChecker.requireCurrentEpoch(a.place(),a.place().epoch() ^ 1));
                    System.out.println(conflict ? "conflict" : "independent");
                } catch (IllegalArgumentException | NullPointerException | IndexOutOfBoundsException e) {
                    System.out.println("invalid");
                }
            }
        }
    }
}
