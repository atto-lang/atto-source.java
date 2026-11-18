package dev.oreslang;

import dev.oreslang.runtime.StrictIntegerArithmetic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Conformance of the Java arithmetic helper to the pinned canonical v1 data.
 * A deliberately narrow reader accepts only the immutable 26-vector JSON
 * schema; it is NOT a general JSON parser and is not guest execution proof.
 */
final class StrictIntegerContractV1OracleTest {
    static final String CONTRACT_BLOB = "3f65f557f06e8cd0d34a738a0d357d6c2e477665";
    static final Path DEFAULT_CONTRACT =
            Path.of("contracts/v1/strict-named-integer-arithmetic-v1.json");

    private static final Pattern ROW = Pattern.compile(
            "\\{\\s*\"id\"\\s*:\\s*\"([a-z0-9_]+)\"\\s*,\\s*"
            + "\"operation\"\\s*:\\s*\"(rem|mod|pow|bit_xor)\"\\s*,\\s*"
            + "\"left\"\\s*:\\s*\"(-?[0-9]+)\"\\s*,\\s*"
            + "\"right\"\\s*:\\s*\"(-?[0-9]+)\"\\s*,\\s*"
            + "\"expect\"\\s*:\\s*\\{\\s*\"(value|error)\"\\s*:\\s*"
            + "\"(-?[A-Z0-9_]+|-?[0-9]+)\"\\s*\\}\\s*\\}");

    private static void ensure(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String gitBlob(byte[] bytes) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        sha.update(bytes);
        return HexFormat.of().formatHex(sha.digest());
    }

    private static String readPinned(Path path) throws Exception {
        ensure(!Files.isSymbolicLink(path), "symlinked arithmetic contract");
        ensure(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "missing arithmetic contract");
        long size = Files.size(path);
        ensure(size > 0 && size <= 8192, "contract input size limit");
        byte[] bytes = Files.readAllBytes(path);
        ensure(CONTRACT_BLOB.equals(gitBlob(bytes)), "unreviewed arithmetic contract blob");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long invoke(String op, long left, long right) {
        return switch (op) {
            case "rem" -> StrictIntegerArithmetic.rem(left, right);
            case "mod" -> StrictIntegerArithmetic.mod(left, right);
            case "pow" -> StrictIntegerArithmetic.pow(left, right);
            case "bit_xor" -> StrictIntegerArithmetic.bitXor(left, right);
            default -> throw new AssertionError("unknown contract operation: " + op);
        };
    }

    private record Result(int count, int errors) {}

    private static Result verifyRows(String json) {
        ensure(json.contains("\"contract\": \"oreslang.strict-named-integer-arithmetic.v1\""),
                "contract identity missing");
        ensure(json.contains("\"integer\": \"signed64\""), "contract width missing");
        int at = json.indexOf("\"cases\": [");
        ensure(at >= 0, "contract cases array missing");
        int begin = json.indexOf('[', at);
        int end = json.lastIndexOf(']');
        ensure(end > begin, "contract array truncated");
        String cases = json.substring(begin + 1, end);
        Matcher rows = ROW.matcher(cases);
        Set<String> seen = new HashSet<>();
        int cursor = 0;
        int count = 0;
        int errors = 0;
        while (rows.find()) {
            String delimiter = cases.substring(cursor, rows.start());
            ensure(delimiter.matches(count == 0 ? "\\s*" : "\\s*,\\s*"),
                    "unexpected or missing contract case separator at " + count);
            String id = rows.group(1);
            String operation = rows.group(2);
            long left = Long.parseLong(rows.group(3));
            long right = Long.parseLong(rows.group(4));
            String mode = rows.group(5);
            String expected = rows.group(6);
            ensure(seen.add(id), "duplicate contract case id " + id);
            try {
                long actual = invoke(operation, left, right);
                ensure(mode.equals("value"), "missing expected arithmetic error: " + id);
                ensure(actual == Long.parseLong(expected),
                        "contract mismatch: " + id + " got " + actual + ", expected " + expected);
            } catch (ArithmeticException | IllegalArgumentException thrown) {
                // Do not treat an AssertionError from the oracle as the expected
                // operation failure. Only known Java arithmetic errors qualify.
                ensure(mode.equals("error"), "unexpected arithmetic exception: " + id);
                Class<? extends RuntimeException> kind = switch (expected) {
                    case "DIVISOR_ZERO", "OVERFLOW" -> ArithmeticException.class;
                    case "NEGATIVE_EXPONENT" -> IllegalArgumentException.class;
                    default -> throw new AssertionError("unknown canonical error type " + expected);
                };
                ensure(kind.isInstance(thrown), "wrong arithmetic exception type: " + id);
                errors++;
            }
            count++;
            cursor = rows.end();
        }
        ensure(cases.substring(cursor).matches("\\s*"), "unrecognized trailing contract rows");
        ensure(count == 26, "missing/extra canonical vectors: " + count);
        ensure(errors == 4, "canonical error case count mismatch: " + errors);
        return new Result(count, errors);
    }

    private static void rejectMalformed(String label, String altered) {
        try {
            verifyRows(altered);
        } catch (AssertionError | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("malformed arithmetic contract was accepted: " + label);
    }

    private static String change(String json, String exact, String replacement) {
        ensure(json.contains(exact), "mutation did not match canonical text: " + exact);
        return json.replace(exact, replacement);
    }

    private static void rejectSevenInvalidContracts(String json) {
        rejectMalformed("wrong-vector", change(json,
                "\"value\": \"1\"", "\"value\": \"77\""));
        rejectMalformed("duplicate-case-id", change(json,
                "rem_neg_pos", "rem_pos_pos"));
        rejectMalformed("unknown-operation", change(json,
                "\"operation\": \"rem\"", "\"operation\": \"unknown\""));
        rejectMalformed("missing-row-comma", change(json,
                "    },\n    {", "    }\n    {"));
        rejectMalformed("unrecognized-expect-field", change(json,
                "\"value\": \"1\"", "\"value\": \"1\", \"extra\": \"bad\""));
        rejectMalformed("unknown-error-code", change(json,
                "DIVISOR_ZERO", "UNEXPECTED_ERROR"));
        rejectMalformed("out-of-range-i64", change(json,
                "\"left\": \"9\"", "\"left\": \"9223372036854775808\""));
    }

    private static void report(Result result) {
        System.out.println("STRICT_I64_CONTRACT|PASS|cases=" + result.count()
                + "|errors=" + result.errors());
    }

    @Test
    void exactCanonicalContractVectorsMatchHostArithmetic() throws Exception {
        report(verifyRows(readPinned(DEFAULT_CONTRACT)));
    }

    @Test
    void malformedCanonicalRowsFailClosedBeforeCountingSuccess() throws Exception {
        rejectSevenInvalidContracts(readPinned(DEFAULT_CONTRACT));
    }

    public static void main(String[] args) throws Exception {
        ensure(args.length == 1, "expected exactly one path to canonical contract");
        String json = readPinned(Path.of(args[0]));
        report(verifyRows(json));
        rejectSevenInvalidContracts(json);
        System.out.println("STRICT_I64_CONTRACT_NEGATIVE|PASS|cases=7");
    }
}
