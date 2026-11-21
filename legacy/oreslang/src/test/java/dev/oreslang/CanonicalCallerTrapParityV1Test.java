package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact byte-for-byte Atto contract fixtures, not convenient Java-only
 * rewrites. Any source edit requires the corresponding conformance contract
 * blob to be reviewed again. No native LLVM/.NET evidence is inferred here.
 *
 * Ordinary source `throw Error(...)` is NOT implemented by the current Java
 * compiler; the explicit negative test below is a known parity blocker.
 */
final class CanonicalCallerTrapParityV1Test {
    private record Guest(long value, String stdout) {}

    private static final Map<String, String> BLOBS = Map.copyOf(new LinkedHashMap<>() {{
        put("01-some-int.atto", "a83015bcbc66ed075440fc15972f6933189c2eee");
        put("02-some-result-ok.atto", "f2c4eb66f37ad239cc06b05f4be9aa8cac4712f5");
        put("03-some-result-err.atto", "3930a526cc7f1c94005354f1f4d94327d7106069");
        put("04-some-none.atto", "cb60075bf1ae15e86ff8013878f5da669269e610");
        put("05-ordinary-throw.atto", "1f3e6b694b3147a63a2528052cf5ed7c0b7a1a59");
        put("06-panic-bypass.atto", "1e1247eb310efea99a6a28aa8fd7013e76e98e7e");
        put("07-exactly-once.atto", "af0d97a1df81ad879529983b21ea2fb5da52824e");
        put("08-type-mismatch.atto", "740eb4a196f450ba834918b9fb1c41338177e46f");
        put("09-result-is-not-throw.atto", "fcc43cbc495b90b642c4881ae79f5d4a75a58258");
    }});

    private static byte[] bytes(String name) throws IOException {
        assertTrue(BLOBS.containsKey(name), "fixture must belong to reviewed contract");
        try (InputStream input = CanonicalCallerTrapParityV1Test.class
                .getResourceAsStream("/caller-trap-parity/" + name)) {
            assertNotNull(input, "canonical .atto resource missing: " + name);
            byte[] contents = input.readAllBytes();
            // Git blob SHA-1: SHA1("blob " + length + NUL + raw bytes).
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-1"); }
            catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
            digest.update(("blob " + contents.length + "\0").getBytes(StandardCharsets.UTF_8));
            digest.update(contents);
            assertEquals(BLOBS.get(name), java.util.HexFormat.of().formatHex(digest.digest()),
                    "fixture differs from canonical shared Atto source: " + name);
            return contents;
        }
    }

    private static String source(String name) throws IOException {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    private static Guest execute(String name) throws Exception {
        String code = source(name);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code), name);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source script = Source.newBuilder(OresLanguage.ID, code, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            Value result = context.eval(script);
            assertTrue(result.fitsInLong(), name + ": actual guest must return signed i64");
            return new Guest(result.asLong(), stdout.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void canonicalBytesAreIdenticalToSharedContract() throws Exception {
        for (String name : BLOBS.keySet()) bytes(name);
    }

    @Test
    void supportedPureAndTaggedValuesRunInActualGraalVmGuest() throws Exception {
        for (var entry : Map.of(
                "01-some-int.atto", 42L,
                "02-some-result-ok.atto", 42L,
                "03-some-result-err.atto", 7L,
                "04-some-none.atto", 13L,
                "09-result-is-not-throw.atto", 17L).entrySet()) {
            Guest actual = execute(entry.getKey());
            assertEquals(entry.getValue().longValue(), actual.value(), entry.getKey());
            assertEquals("", actual.stdout(), entry.getKey());
        }
    }

    @Test
    void trappedOperandRunsExactlyOnceWithRealGuestOutput() throws Exception {
        Guest result = execute("07-exactly-once.atto");
        assertEquals(42L, result.value());
        assertEquals("once", result.stdout());
    }

    @Test
    void panicIsNeverReclassifiedAsCaughtOrdinaryError() throws Exception {
        String name = "06-panic-bypass.atto";
        String code = source(name);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        PolyglotException panic = assertThrows(PolyglotException.class, () -> execute(name));
        assertTrue(panic.getMessage().contains("Option::unwrap"));
    }

    @Test
    void trapNeverImplicitlyCoercesOptionIntoBareInt() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source("08-type-mismatch.atto")));
    }

    @Test
    void javaGuestOrdinaryThrowIsTrappedAsErrAcrossACall() throws Exception {
        // Typed ordinary Error(message) propagation is implemented: the throw
        // crosses via_call() and the caller-side trap yields Err(Error) (exit 9).
        Guest actual = execute("05-ordinary-throw.atto");
        assertEquals(9L, actual.value());
        assertEquals("", actual.stdout());
    }
}
