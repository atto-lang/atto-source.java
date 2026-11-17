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
        put("01-some-int.atto", "d0ec8a9bc9e73e016cf1c31e2cb35cb84ac281c7");
        put("02-some-result-ok.atto", "8dd86350bdfa4dc90e47b7dd5f44bd82b0266157");
        put("03-some-result-err.atto", "0f431f23d40d46cacd07d81714780e0061b6413e");
        put("04-some-none.atto", "038d5bfe66996efff91400a8d699dac3e6f43f54");
        put("05-ordinary-throw.atto", "57a646240880dfc4b633c2f9eeb5b9278dd0022f");
        put("06-panic-bypass.atto", "7b674493d50e688666b1fa37f59b990a27f109e4");
        put("07-exactly-once.atto", "6547de51bc2be9b52eca35359a00b692ed434cfb");
        put("08-type-mismatch.atto", "057544faa452e4942143503499c4a7b76072ff17");
        put("09-result-is-not-throw.atto", "c5a427450943ac32824e42c1261401777080724e");
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
    void javaGuestOrdinaryThrowEffectMustRemainExplicitlyUnimplemented() throws Exception {
        // This MUST become a positive guest test once typed ordinary
        // Error(message) propagation is implemented. It is NOT a parity pass.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source("05-ordinary-throw.atto")));
        assertTrue(failure.getMessage().contains("exceptional effect") ||
                   failure.getMessage().contains("exception and supervision"),
                "source ordinary throw must be a deliberate effect-admission denial");
    }
}
