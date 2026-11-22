package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-identical guest .atto fixtures shared with LLVM and conformance.
 * These are source ownership tests, not proof of five-backend parity.
 */
final class LogicalBorrowCompilerGuestTest {
    private static String fixture(String name, String expectedBlob) throws Exception {
        byte[] bytes;
        try (InputStream stream = LogicalBorrowCompilerGuestTest.class
                .getResourceAsStream("/parity/logical-borrow-compiler-v1/" + name)) {
            assertNotNull(stream, "missing shared source fixture: " + name);
            bytes = stream.readAllBytes();
        }
        assertTrue(bytes.length > 30 && bytes.length < 1024, "bounded source fixture");
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.writeBytes(("blob " + bytes.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        blob.writeBytes(bytes);
        assertEquals(expectedBlob, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(blob.toByteArray())));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void movedUniqueScalarExecutesInRealGraalGuest() throws Exception {
        String program = fixture("owned-take-positive.atto",
                "50676385d9dd6982764a6a60c0c63da56862ec94");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program,
                "logical-borrow-owned-take-positive.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        assertEquals("42", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void senderUseAfterTakeIsSemanticOwnershipError() throws Exception {
        String program = fixture("use-after-take-reject.atto",
                "79d8d5880feffacb0a4f960de79f9155d28de469");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        String diagnostic = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        // Reject a real post-transfer read of this exact owner, not any
        // unrelated error mentioning ownership or a previously moved field.
        assertTrue(diagnostic.contains(
                "atto ownership error: e_moved: use of moved value 'owner'"),
                failure::getMessage);
    }
}
