package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared Java/LLVM narrow two-i64 SOURCE envelope.
 * Java execution assertions are SoA HOST-REFERENCE ONLY, not Truffle guests.
 */
final class DopInvertedSharedEnvelopeTest {
    private static String source(int capacity, String body) {
        return "define inverted struct Row as let value: int; token: int; end\n"
            + "fnc main(): int {\n"
            + "let rows = InvertedArray<Row>.with_capacity(" + capacity + ");\n"
            + body + "\n}\n";
    }

    private static void denied(int capacity, String body) {
        var error = assertThrows(IllegalArgumentException.class,
            () -> OresCompiler.compileDopInvertedScalarI64(source(capacity,body)));
        assertTrue(error.getMessage().contains("DOP_INVERTED_V1_REJECTED"),
            "unsupported program must fail during typed compilation");
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("contracts", "dop-inverted-v1", name));
    }

    /**
     * Git's blob identity is SHA1("blob " + rawByteCount + NUL + rawBytes).
     * An in-repo source edit must not silently become cross-backend parity.
     * SHA-256 evidence for hosted execution is an independent later gate.
     */
    private static String gitBlobId(String name) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(
                "contracts", "dop-inverted-v1", name));
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length)
                .getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    @Test
    void sharedFixtureBytesMatchPinnedJavaAndLlvmGitBlobIdentities() throws Exception {
        assertEquals("5392eed2f33be3c8c69809f0521efe9c7a20440f",
                gitBlobId("capacity-256-42.atto"));
        assertEquals("e052544a6cde137cabe0db6bc7f73b1e072db174",
                gitBlobId("capacity-257-rejected.atto"));
        assertEquals("793b29a0e190124137aebe293629d18c82ae5f08",
                gitBlobId("capacity-zero-rejected.atto"));
    }

    @Test
    void byteIdenticalSharedCapacitySourcesHaveExpectedTypedAdmission() throws Exception {
        var allowed = OresCompiler.compileDopInvertedScalarI64(
                fixture("capacity-256-42.atto"));
        assertEquals(256, allowed.capacity());
        assertEquals(42L, allowed.executeHostReference());

        for (String rejected : new String[]{
                "capacity-257-rejected.atto", "capacity-zero-rejected.atto"}) {
            var denied = assertThrows(IllegalArgumentException.class, () ->
                    OresCompiler.compileDopInvertedScalarI64(fixture(rejected)));
            assertTrue(denied.getMessage().contains("DOP_INVERTED_V1_REJECTED"));
            assertTrue(denied.getMessage().contains("capacity"));
        }
    }

    @Test
    void sharedCapacityBoundariesAreExactlyOneThroughTwoHundredFiftySix() {
        assertEquals(256, DopInvertedScalarCompilerV1.MAX_SHARED_CAPACITY);
        assertEquals(1024, DopInvertedScalarCompilerV1.MAX_SHARED_OPERATIONS);

        denied(0, "return rows.get(0, value);");
        denied(257, "rows.push(1,2); return rows.get(0, value);");
        denied(4096, "rows.push(1,2); return rows.get(0, value);");

        var one = OresCompiler.compileDopInvertedScalarI64(source(1,
            "rows.push(42, 7); return rows.get(0, value);"));
        assertEquals(42L, one.executeHostReference());
        assertEquals(1, one.capacity());

        var large = OresCompiler.compileDopInvertedScalarI64(source(256,
            "rows.push(42, 7); return rows.get(0, value);"));
        assertEquals(256, large.capacity());
        assertEquals(42L, large.executeHostReference());
    }

    private static String operations(int count) {
        if (count < 2) throw new IllegalArgumentException("count must allow a return");
        StringBuilder body = new StringBuilder();
        int current = 0;
        // Every pair is a valid push/swap-erase round trip. The final push
        // leaves exactly one row, and the final return observes 42.
        while (count - current > 2) {
            body.append("rows.push(1, 2);\nrows.erase_swap(0);\n");
            current += 2;
        }
        body.append("rows.push(42, 7);\nreturn rows.get(0, value);\n");
        return body.toString();
    }

    @Test
    void exactlyOneThousandTwentyFourSourceOperationsAreAccepted() {
        String code = operations(1024);
        var plan = OresCompiler.compileDopInvertedScalarI64(source(1,code));
        assertEquals(1024, plan.instructions().size());
        assertEquals(42L, plan.executeHostReference());
        assertEquals(42L, plan.executeHostReference());
    }

    @Test
    void OneThousandTwentyFiveOperationsAreRejectedBeforeExecution() {
        // Use a forged-but-well-formed extra expression rather than relying on
        // lexical errors to trigger the compiler operation-budget guard.
        String body = operations(1024).replace(
            "return rows.get(0, value);",
            "rows.set(0, value, 42); return rows.get(0, value);");
        denied(1,body);
    }

    @Test
    void sharedBoundsRemainFailClosedForDynamicDenseRead() {
        var program = OresCompiler.compileDopInvertedScalarI64(source(1,
            "rows.push(42,7); return rows.get(1,value);"));
        assertThrows(IllegalArgumentException.class,program::executeHostReference);
        denied(1, "rows.push(42,7); rows.erase_swap(1); return rows.get(0,value);");
    }
}
