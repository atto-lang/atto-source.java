package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nine local ports of oreslang-common-source PR #187: migrated to .atto,
 * canonical critical-read/write and rt mut borrow. These hashes bind the
 * migrated local bytes, not the original corpus blobs. Compiler/Truffle
 * execution establishes a Java result only; other backends remain unproven.
 */
final class ActorCriticalModeConformanceTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void write_then_read() throws Exception {
        runPositive("write_then_read", "b99b15b18776999bfc46ae82a60e631b690a65c7", "critical-modes-ok");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_read_mutation() throws Exception {
        reject("reject_read_mutation", "09481a19ec00f643088bfe07a4c6c44b6e0b07b0", "readonly");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_read_mutable_reborrow() throws Exception {
        reject("reject_read_mutable_reborrow", "570fdebdaeb70b5a25417746b5d2b042732d4cc8", "shared borrow");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_unknown_mode() throws Exception {
        reject("reject_unknown_mode", "08ae163cd2c44f1657c25fd311df193186f7c330", "critical state access mode");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_string_forged_write_mode() throws Exception {
        reject("reject_string_forged_write_mode", "0ab217e3edf944a510659e9ea0e765ffecf5e67f", "critical-read");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_await_in_write() throws Exception {
        reject("reject_await_in_write", "a1ab39e1e23806c15e40e370835b47b40fb47503", "cannot suspend");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_await_in_read() throws Exception {
        reject("reject_await_in_read", "d3e39ff8590f3c0cf096d794d04c0241e9c85a93", "cannot suspend");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_state_escape() throws Exception {
        reject("reject_state_escape", "11e687756decf8f557ee3c4c11bd667e52860b8d", "cannot suspend");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_direct_state_access() throws Exception {
        reject("reject_direct_state_access", "9754b8df89d7eee4cbf99ff6346b8225c5baa74f", "sealed");
    }

    private static String checkedSource(String fixture, String expectedGitBlob) throws Exception {
        String resource = "/parity/actor-critical-modes/" + fixture + ".atto";
        byte[] bytes;
        try (InputStream stream = ActorCriticalModeConformanceTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "Missing native conformance fixture: " + resource);
            bytes = stream.readAllBytes();
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        sha.update(bytes);
        assertEquals(expectedGitBlob, HexFormat.of().formatHex(sha.digest()),
                "Native conformance fixture differs from approved source Git blob");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void runPositive(String fixture, String gitBlob, String expectedStdout)
            throws Exception {
        String program = checkedSource(fixture, gitBlob);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, fixture + ".atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout).build()) {
            context.eval(source);
        }
        assertEquals(expectedStdout, stdout.toString(StandardCharsets.UTF_8));
        System.out.println("ACTOR_CRITICAL_CONFORMANCE_GUEST_PASS " + fixture
                + " blob=" + gitBlob);
    }

    private static void reject(String fixture, String gitBlob, String diagnostic)
            throws Exception {
        String program = checkedSource(fixture, gitBlob);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(failure.getMessage().contains(diagnostic),
                "Unexpected rejection category for " + fixture + ": " + failure.getMessage());
        System.out.println("ACTOR_CRITICAL_CONFORMANCE_REJECT_PASS " + fixture
                + " blob=" + gitBlob);
    }
}
