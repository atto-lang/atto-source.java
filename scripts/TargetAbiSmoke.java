import dev.oreslang.compiler.TargetAbi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** Standalone ABI contract smoke without JUnit or build-system dependencies. */
public final class TargetAbiSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected contracts TSV path");
        var rows = Files.readAllLines(Path.of(args[0]));
        check(rows.size() == 6, "ABI contract must have five target profiles");
        check(rows.get(0).equals("triple\tpointer_bits\tendian\tabi\temission"),
              "ABI header drift");
        var names = new HashSet<String>();
        for (int i = 1; i < rows.size(); i++) {
            var data = rows.get(i).split("\\t", -1);
            check(data.length == 5 && names.add(data[0]), "invalid or duplicate target row");
            var target = TargetAbi.parse(data[0]);
            check(target.triple().equals(data[0])
                  && target.pointerBits() == Integer.parseInt(data[1])
                  && target.endian().equals(data[2])
                  && target.abi().equals(data[3])
                  && target.emission().equals(data[4]), "ABI target profile mismatch");
            check(target.hasExperimentalWasmIr() == data[4].equals("experimental-wasm32-ir"),
                  "experimental emission metadata mismatch");
            check(target.checkedContiguousBytes(0) == 0, "zero byte allocation rejected");
            check(target.checkedContiguousBytes(target.maxContiguousBytes())
                  == target.maxContiguousBytes(), "maximum target allocation rejected");
            if (target.pointerBits() == 32) {
                rejects(() -> target.checkedContiguousBytes(1L << 31));
                rejects(() -> target.checkedJavaByteBufferBytes(1L << 31));
            } else {
                check(target.checkedContiguousBytes(1L << 33) == 1L << 33,
                      "valid 64-bit logical allocation rejected");
                rejects(() -> target.checkedJavaByteBufferBytes(1L << 33));
            }
            rejects(() -> target.checkedContiguousBytes(-1));
        }
        for (String invalid : new String[]{"", "host", "arm64",
                "wasm64-unknown-unknown", "aarch64-unknown-linux-musl"}) {
            boolean rejected = false;
            try { TargetAbi.parse(invalid); } catch (IllegalArgumentException ex) { rejected = true; }
            check(rejected, "unsupported triple accepted: " + invalid);
        }
        System.out.println("PASS: JDK standalone target ABI exact-table and allocation bounds");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void rejects(Runnable f) {
        boolean rejected = false;
        try { f.run(); } catch (ArithmeticException ex) { rejected = true; }
        check(rejected, "unsafe contiguous allocation accepted");
    }
}
