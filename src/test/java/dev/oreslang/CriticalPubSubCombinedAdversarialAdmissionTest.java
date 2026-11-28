package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An additive semantic regression gate on the mainline-first critical/pubsub
 * merge. It cannot prove concurrency, Graal guest execution or working CI
 * without executed exact-head JUnit and compiler receipts.
 *
 * These tests intentionally use committed real source fixtures, not synthetic
 * host equivalents of a Topic or actor send.
 */
@Timeout(45)
final class CriticalPubSubCombinedAdversarialAdmissionTest {
    private static String fixture(String stem, String extension) throws Exception {
        return Files.readString(Path.of("contracts", "v1", "fixtures",
                stem + "." + extension), StandardCharsets.UTF_8);
    }

    @Test
    void pairedNegativeFixturesRetainIdenticalAttoAndOresSourceBytes() throws Exception {
        for (String name : new String[] {
                "actor-pubsub-reject-mailbox-capability",
                "actor-pubsub-reject-nested-callable-topic",
                "actor-pubsub-reject-payload-type",
                "actor-pubsub-reject-suspends-capability"
        }) {
            assertArrayEquals(
                    Files.readAllBytes(Path.of("contracts", "v1", "fixtures", name + ".atto")),
                    Files.readAllBytes(Path.of("contracts", "v1", "fixtures", name + ".ores")),
                    name + " must not drift across the two source suffixes");
        }
    }

    @Test
    void actorMailboxCannotCarryAnActorOwnedTopic() throws Exception {
        requireSemanticRejection("actor-pubsub-reject-mailbox-capability");
    }

    @Test
    void nestedCallableCannotSneakIntoTopicPayloadType() throws Exception {
        requireSemanticRejection("actor-pubsub-reject-nested-callable-topic");
    }

    @Test
    void topicPublishCannotSilentlyCoerceAnIntegerIntoStringPayload() throws Exception {
        requireSemanticRejection("actor-pubsub-reject-payload-type");
    }

    @Test
    void sourceTaskCannotImportActorOwnedTopicParameter() throws Exception {
        requireSemanticRejection("actor-pubsub-reject-suspends-capability");
    }

    @Test
    void stillUnsupportedSynchronousTryPublishCannotSilentlyRunAsAsyncPublish() throws Exception {
        String source = fixture("actor-pubsub-reject-unlowered-try-publish", "atto");
        assertNotNull(Parser.parse(source), "fixture must be a source-level admission question");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void jointCriticalAndPublisherSourceRemainsAcceptedByCompilerAdmission() throws Exception {
        String source = fixture("critical-pubsub-integration-v1", "atto");
        assertNotNull(Parser.parse(source));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source),
                "the negative cases must not turn the combined legitimate source into a reject");
    }

    private static void requireSemanticRejection(String stem) throws Exception {
        String source = fixture(stem, "atto");
        assertNotNull(Parser.parse(source),
                "negative case must parse before its ownership/type gate: " + stem);
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "fail closed on forbidden Topic ownership/type capability: " + stem);
        assertNotNull(denied.getMessage(), "semantic refusal needs a meaningful diagnostic");
        assertFalse(denied.getMessage().isBlank(), "empty error is not a usable diagnostic");
    }
}
