package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AttoLang HPC reference admission through the actual Graal/Truffle guest,
 * not a Java implementation of the reduction. Native LLVM O0/O2 is separately
 * gated by its exact-source Rust runner; CI evidence is not transferable.
 */
final class AttoHpcTileGuestTest {
    private static final String SOURCE = "/atto/hpc-reduction-v1/i64-fixed-tiles.atto";
    // Must stay identical to tests/fixtures/hpc-reduction-v1/i64-fixed-tiles.atto
    // on LLVM feature branch until the common conformance fixture owns the bytes.
    private static final String SHARED_GIT_BLOB = "9deb9935b4f65b4d53de92f200291a28cdb02a79";

    private static byte[] resource() throws IOException {
        try (InputStream input = AttoHpcTileGuestTest.class.getResourceAsStream(SOURCE)) {
            assertNotNull(input, "Missing checked-in .atto tile source");
            byte[] bytes = input.readAllBytes();
            assertTrue(bytes.length > 0 && bytes.length <= 4096, "Unexpected source size");
            return bytes;
        }
    }

    private static String gitBlob(byte[] bytes) {
        try {
            var hash = MessageDigest.getInstance("SHA-1");
            hash.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            hash.update(bytes);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static Value evaluate(Context context, String program) {
        return context.eval("ores", program);
    }

    @Test
    void exactCommonI64TileSourceCompilesAndExecutesInGraalGuest() throws Exception {
        byte[] bytes = resource();
        assertEquals(SHARED_GIT_BLOB, gitBlob(bytes), "Source differs from LLVM guest bytes");
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            Value result = evaluate(context, new String(bytes, StandardCharsets.UTF_8));
            assertTrue(result.isNumber(), "Guest must return a number");
            assertTrue(result.fitsInLong(), "Guest result must be signed i64");
            assertEquals(42L, result.asLong(), "Canonical tile merge result");
        }
    }

    private static long f64Bits(String program) {
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            Value result = evaluate(context, program);
            assertTrue(result.isNumber(), "Float guest must return a number");
            return Double.doubleToRawLongBits(result.asDouble());
        }
    }

    @Test
    void binary64SequentialTileCancellationIsObservedInActualGuest() {
        String program = """
                fnc tile_sum(float a, float b, float c, float d): float {
                  return ((a + b) + c) + d;
                }
                pub fnc main(): float {
                  return tile_sum(10000000000000000.0, 1.0, -10000000000000000.0, 1.0);
                }
                """;
        assertEquals(Double.doubleToRawLongBits(1.0), f64Bits(program),
                "Sequential tile must preserve its declared addition grouping");
    }

    @Test
    void binary64TwoTileMergeDoesNotReassociateInputs() {
        String program = """
                fnc tile_sum(float a, float b): float {
                  return a + b;
                }
                pub fnc main(): float {
                  return tile_sum(10000000000000000.0, 1.0)
                       + tile_sum(-10000000000000000.0, 1.0);
                }
                """;
        assertEquals(Double.doubleToRawLongBits(0.0), f64Bits(program),
                "Two-tile result must match fixed tile grouping, not single-stream grouping");
    }

    // Exact same reviewed Atto source is used by LLVM's Rust/Clang observer.
    // The LLVM variant invokes tile_reduce8 through the native i64 ABI;
    // here each generated main() calls tile_reduce8 through the Graal guest.
    private static final String PARAMETERIZED_SOURCE =
            "/atto/hpc-reduction-v1/i64-parameterized-tiles.atto";
    private static final String PARAMETERIZED_BLOB =
            "88795ff752006d020ea08c6585a8b3b03bf641c7";
    private static final int RANDOM_CASES = 96;
    private static final String VECTOR_MATRIX_SHA256 =
            "b921d3a36a08917bd5ce7681eb9e00c7b37bb62e8308e0b8615c0c7ffb3ff575";

    private static long oracle(long[] v) {
        assertEquals(8, v.length);
        long first = 0L;
        long second = 0L;
        for (int i = 0; i < 4; ++i) first = Math.addExact(first, v[i]);
        for (int i = 4; i < 8; ++i) second = Math.addExact(second, v[i]);
        return Math.addExact(first, second);
    }

    private static List<long[]> vectors() {
        List<long[]> rows = new ArrayList<>();
        rows.add(new long[]{0, 0, 0, 0, 0, 0, 0, 0});
        rows.add(new long[]{1, 2, 3, 4, 5, 6, 7, 8});
        rows.add(new long[]{-1, -2, -3, -4, -5, -6, -7, -8});
        rows.add(new long[]{Long.MAX_VALUE, -Long.MAX_VALUE, 10, -5,
                Long.MIN_VALUE, Long.MAX_VALUE, 2, 3});
        rows.add(new long[]{Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                -Long.MAX_VALUE, 7, -7, 0, 0});
        rows.add(new long[]{Long.MAX_VALUE, 0, 0, 0, -Long.MAX_VALUE, 0, 0, 0});
        rows.add(new long[]{Long.MIN_VALUE, 1, 0, 0, Long.MAX_VALUE, -1, 0, 0});
        rows.add(new long[]{10_000_000_000_000_000L, 1, -10_000_000_000_000_000L, 1,
                -10_000_000_000_000_000L, 1, 10_000_000_000_000_000L, 1});
        rows.add(new long[]{5_000_000_000L, 4_000_000_000L, -3_000_000_000L, 7,
                -9, 10, 0, 0});
        rows.add(new long[]{255, 1, 1, 1, 1, 1, 1, 1});
        rows.add(new long[]{Long.MAX_VALUE, -1, 0, 0, -Long.MAX_VALUE, 1, 0, 0});
        long state = 0x5EED_F00D_BADC_0FFEL;
        for (int k = 0; k < RANDOM_CASES; ++k) {
            long[] v = new long[8];
            for (int i = 0; i < 8; ++i) {
                state ^= state << 13;
                state ^= state >>> 7;
                state ^= state << 17;
                v[i] = Long.remainderUnsigned(state, 1_000_001L) - 500_000L;
            }
            rows.add(v);
        }
        return List.copyOf(rows);
    }

    @TestFactory
    Stream<DynamicTest> signedI64ParameterizedGuestMatchesCheckedOracle() throws Exception {
        byte[] bytes;
        try (InputStream input = AttoHpcTileGuestTest.class.getResourceAsStream(PARAMETERIZED_SOURCE)) {
            assertNotNull(input, "Missing exact shared parameterized Atto guest source");
            bytes = input.readAllBytes();
        }
        assertEquals(PARAMETERIZED_BLOB, gitBlob(bytes), "Parameterized source drifted across backends");
        String original = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(1, original.split("return 42;", -1).length - 1,
                "Only one reviewed main return may be replaced with runtime case literals");
        List<long[]> rows = vectors();
        assertEquals(107, rows.size(), "Keep Java cases aligned with LLVM Rust 107-case matrix");
        String canonicalRows = rows.stream().map(row ->
                Arrays.stream(row).mapToObj(Long::toString)
                        .collect(Collectors.joining(",")) + "\n")
                .collect(Collectors.joining());
        String actualMatrixHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(canonicalRows.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(VECTOR_MATRIX_SHA256, actualMatrixHash,
                "Full-width Graal vectors drifted from the LLVM Rust oracle matrix");
        return IntStream.range(0, rows.size()).mapToObj(index ->
                DynamicTest.dynamicTest("hpc-i64-differential-" + index, () -> {
                    long[] row = rows.get(index);
                    long expected = oracle(row);
                    String arguments = Arrays.stream(row)
                            .mapToObj(Long::toString)
                            .collect(Collectors.joining(", "));
                    String compiledAtto = original.replace("return 42;",
                            "return tile_reduce8(" + arguments + ");");
                    try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
                        Value observed = evaluate(context, compiledAtto);
                        assertTrue(observed.fitsInLong(), "Guest result must fit i64 for case " + index);
                        assertEquals(expected, observed.asLong(),
                                "Graal i64 tile mismatch case " + index + " inputs=" + Arrays.toString(row));
                    }
                }));
    }
}
