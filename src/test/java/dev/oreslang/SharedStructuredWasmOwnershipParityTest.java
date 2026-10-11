package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-identical structured source fixtures shared with LLVM/Wasm.
 * A successful Java test is required before Graal can move from pending.
 */
final class SharedStructuredWasmOwnershipParityTest {
    private static final String DIRECTORY = "/parity/structured/";
    private static String fixture(String name, String gitBlobSha) throws Exception {
        byte[] bytes;
        try (InputStream stream = SharedStructuredWasmOwnershipParityTest.class
                .getResourceAsStream(DIRECTORY + name)) {
            assertNotNull(stream, "missing conformance source " + name);
            bytes = stream.readAllBytes();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        assertEquals(gitBlobSha, HexFormat.of().formatHex(digest.digest()),
                "fixture no longer matches exact LLVM/Wasm source");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void assertExecutesFortyTwo(String name, String blob) throws Exception {
        String text = fixture(name, blob);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(text)),
                "Graal ownership admission diverges for identical Oreslang source");
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).build()) {
            Value value = context.eval(Source.newBuilder("ores", text, name).build());
            assertTrue(value.fitsInLong());
            assertEquals(42L, value.asLong(), "Graal must match native LLVM and Wasm32 result");
        }
    }

    @Test
    void exactSharedClassReadHasSameResultOnGraal() throws Exception {
        assertExecutesFortyTwo("class-read.ores", "af05262cfcd81fd13ed7e54b2e6b03db8032e20b");
    }

    @Test
    void exactMutableClassBorrowHasSameResultOnGraal() throws Exception {
        assertExecutesFortyTwo("class-mutable.ores", "3cad92b8c5f7c4d5b97472a7247ab0c10455078b");
    }

    @Test
    void exactOverlappingMutableAndSharedLoansRejectedOnGraal() throws Exception {
        String source = fixture("class-overlap.ores", "2664d2fa411e4e5ea84d1c817c3f9e31ac45b511");
        RuntimeException rejected = assertThrows(RuntimeException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(rejected.getMessage().toLowerCase().contains("borrow"),
                "rejection must be a borrow conflict, not an unrelated syntax mismatch: "
                        + rejected.getMessage());
    }
}
