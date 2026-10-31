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
 * Nine cross-backend conformance programs copied byte-for-byte from
 * oreslang-common-source PR #187 within the same private organization.
 * Source-blob checks are necessary, not sufficient: the real compiler
 * and Truffle guest must execute to count as a backend pass.
 */
final class ActorCriticalModeConformanceTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void write_then_read() throws Exception {
        runPositive("write_then_read", "eb6e15e2670bd003d8106645faf053b82ab53c26", "critical-modes-ok");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_read_mutation() throws Exception {
        reject("reject_read_mutation", "66d9f1509bdf09fd5dd88be044c546ecd7b34eb9", "readonly");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_read_mutable_reborrow() throws Exception {
        reject("reject_read_mutable_reborrow", "ca96beecca803cecaa499a2d980334c5438cab5f", "shared borrow");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_unknown_mode() throws Exception {
        reject("reject_unknown_mode", "c003d3002841e644462c956a1ec96fdd3fe95d39", "critical state access mode");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_string_forged_write_mode() throws Exception {
        reject("reject_string_forged_write_mode", "0ab217e3edf944a510659e9ea0e765ffecf5e67f", "critical[read]");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_await_in_write() throws Exception {
        reject("reject_await_in_write", "12c24fa550bfadb1cbc55ee4e6de25308509cc41", "cannot suspend");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_await_in_read() throws Exception {
        reject("reject_await_in_read", "f4d182cef8ab1e376198f84020c45f9dd0e0aba9", "cannot suspend");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_state_escape() throws Exception {
        reject("reject_state_escape", "64bf6871b8d222854bce9e2343250923415f50af", "cannot suspend");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reject_direct_state_access() throws Exception {
        reject("reject_direct_state_access", "9754b8df89d7eee4cbf99ff6346b8225c5baa74f", "sealed");
    }

    private static String checkedSource(String fixture, String expectedGitBlob) throws Exception {
        String resource = "/parity/actor-critical-modes/" + fixture + ".ores";
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
        Source source = Source.newBuilder(OresLanguage.ID, program, fixture + ".ores")
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
