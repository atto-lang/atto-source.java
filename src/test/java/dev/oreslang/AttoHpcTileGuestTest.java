package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

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
}
