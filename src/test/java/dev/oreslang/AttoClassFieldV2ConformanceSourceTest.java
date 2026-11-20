package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AttoLang class-field-v2 source fixtures mirrored byte-for-byte from
 * atto-platform-conformance #84. This tests the Java strict FRONT END only;
 * neither AST checks nor source Git hashes count as compiled guest evidence.
 */
final class AttoClassFieldV2ConformanceSourceTest {
    private static final Path ROOT = Path.of("tests/parity/class-fields-v2");

    private static final Map<String, String> GIT_BLOB_SHA = Map.of(
            "class-field-visibility-default-val.atto", "1cac9591ba7d6c0fcea73e5193d3b56a76a13e61",
            "class-field-implicit-val-constructor-assign.atto", "f8ecc3c49abbe3c7cea0e13b56ac0fff137b2797",
            "class-field-private-outside-class.atto", "c3f34523b6dba79ec80979b7bf403f5d4ca9e5e6",
            "class-field-const-runtime-call.atto", "45fad8ac2f6f93eb0d305178990b8b7a38343f8e");

    private static String source(String fixture) throws Exception {
        byte[] bytes = Files.readAllBytes(ROOT.resolve(fixture));
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
        assertEquals(GIT_BLOB_SHA.get(fixture), HexFormat.of().formatHex(digest.digest()),
                "fixture differs from canonical conformance Git blob");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void canonicalConformanceFixtureBlobsRemainByteIdentical() throws Exception {
        for (String fixture : GIT_BLOB_SHA.keySet()) {
            assertFalse(source(fixture).isBlank());
        }
    }

    @Test
    void packageScopedPositiveFixtureMustFailClosedUntilPackageIdentityIsProved() throws Exception {
        // Registry #84 expects a successful guest result 42. Java cannot
        // represent pub[pkg] without widening package access, so this is a
        // documented conformance GAP, not a passing positive guest test.
        String program = source("class-field-visibility-default-val.atto");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(program));
        assertTrue(error.getMessage().contains("E_PKG_VISIBILITY_UNSUPPORTED"),
                error::getMessage);
    }

    @Test
    void implicitValConstructorRebindIsRejected() throws Exception {
        String program = source("class-field-implicit-val-constructor-assign.atto");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(program));
    }

    @Test
    void privateClassFieldIsNotExposedToOutsideCode() throws Exception {
        String program = source("class-field-private-outside-class.atto");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(program));
    }

    @Test
    void nonConstantInitializerCannotEnterClassConstField() throws Exception {
        String program = source("class-field-const-runtime-call.atto");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(program));
    }
}
