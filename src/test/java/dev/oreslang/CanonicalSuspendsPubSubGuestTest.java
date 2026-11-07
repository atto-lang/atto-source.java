package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proposed canonical suspension syntax executed by the real OresLanguage guest.
 *
 * <p>The pinned source is byte-identical to platform-conformance #579. These
 * tests do not prove exact-head CI, backend parity, portable try_publish,
 * supervisor CONTROL ordering, reliable ACK semantics, or trace authenticity.
 * A passing JUnit method is meaningful only when a real GraalVM Polyglot
 * Context.eval step is independently verified in the source repository.</p>
 */
final class CanonicalSuspendsPubSubGuestTest {
    private static final String FIXTURE =
            "actor-pubsub-formal-suspends-v1.atto";
    private static final String PINNED_GIT_BLOB =
            "fe10d9e5018a3f030a10bc31586e851198aaa4a3";
    private static final String EXPECTED_STDOUT = "subscribed|p-1";

    @Test
    void canonicalSuspendsSourceMatchesPinnedFormalFixture() throws Exception {
        byte[] body = fixtureBytes();
        assertEquals(PINNED_GIT_BLOB, gitBlobSha(body),
                "canonical source changed without a reviewed conformance update");
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.contains("pub suspends fnc main(): void {"),
                "guest must use canonical suspends fnc entrypoint");
        assertFalse(text.contains("pub async routine main()"),
                "legacy syntax cannot satisfy the canonical fixture gate");
    }

    @Test
    @Timeout(30)
    void canonicalSuspendsFncExecutesThroughGraalPolyglot() throws Exception {
        byte[] body = fixtureBytes();
        assertEquals(PINNED_GIT_BLOB, gitBlobSha(body),
                "guest source must be exactly the formal candidate");
        Source source = Source.newBuilder(
                OresLanguage.ID, new String(body, StandardCharsets.UTF_8), FIXTURE)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals(EXPECTED_STDOUT, output.toString(StandardCharsets.UTF_8));
    }

    private static byte[] fixtureBytes() throws Exception {
        return Files.readAllBytes(Path.of("contracts", "v1", "fixtures", FIXTURE));
    }

    private static String gitBlobSha(byte[] bytes) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-1");
        hash.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        hash.update(bytes);
        return HexFormat.of().formatHex(hash.digest());
    }
}
