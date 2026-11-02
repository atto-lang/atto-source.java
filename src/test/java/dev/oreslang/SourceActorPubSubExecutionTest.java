package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceActorPubSubExecutionTest {
    @Test
    void sharedActorSubscribesAndReceivesTypedInMemoryPublication() throws Exception {
        assertEquals(
                "subscribed|p-1",
                run(fixture("actor-pubsub-v1.ores"), "source-actor-pubsub-v1.ores"));
    }

    @Test
    void actorMayJoinMultipleGroupsAndLeavingOnePreservesTheOther() throws Exception {
        assertEquals(
                "ready-b|b-still-live",
                run(
                        fixture("actor-pubsub-multigroup-v1.ores"),
                        "source-actor-pubsub-multi-group.ores"));
    }

    @Test
    void topicPayloadTypeMismatchFailsBeforeExecution() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-payload-type.ores"),
                        "source-actor-pubsub-type-mismatch.ores"));
        assertTrue(error.getMessage().contains("Topic.publish value"), error.getMessage());
    }

    @Test
    void actorCannotDefineTopicOrGrantMembershipAuthority() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-actor-authority.ores"),
                        "source-pubsub-actor-authority-rejected.ores"));
        assertTrue(
                error.getMessage().contains(
                        "actor code may resolve an existing Topic<T> but cannot define one"),
                error.getMessage());
    }

    @Test
    void privateActorCannotUseZeroCopyTopicSubscription() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-private-subscribe.ores"),
                        "source-private-pubsub-rejected.ores"));
        assertTrue(
                error.getMessage().contains(
                        "Topic.subscribe currently requires a shared actor"),
                error.getMessage());
    }

    @Test
    void pubSubHandlesCannotCrossActorMailboxBoundary() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-mailbox-capability.ores"),
                        "source-pubsub-mailbox-capability-rejected.ores"));
        assertTrue(
                error.getMessage().contains("cannot use Topic across an actor boundary"),
                error.getMessage());
    }

    @Test
    void pubSubHandlesCannotCrossAsyncTaskBoundary() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-async-capability.ores"),
                        "source-pubsub-async-capability-rejected.ores"));
        assertTrue(
                error.getMessage().contains("must be concrete owned task-safe data"),
                error.getMessage());
    }

    @Test
    void functionTopicPayloadFailsBeforeExecution() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-callable-topic.ores"),
                        "source-pubsub-callable-topic-rejected.ores"));
        assertTrue(error.getMessage().contains("Topic payload"), error.getMessage());
    }

    @Test
    void nestedFunctionTopicPayloadFailsBeforeExecution() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-nested-callable-topic.ores"),
                        "source-pubsub-nested-callable-topic-rejected.ores"));
        assertTrue(error.getMessage().contains("Topic payload"), error.getMessage());
    }

    @Test
    void pubSubRejectsTypedActorRefCapabilityPayload() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(fixture("actor-pubsub-reject-actor-ref-topic.ores"),
                        "source-pubsub-actor-ref-payload-rejected.ores"));
        assertTrue(error.getMessage().contains("requires MessageData"),
                error.getMessage());
    }

    @Test
    void pubSubRejectsNestedSharedMutexPayload() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(fixture("actor-pubsub-reject-synchronized-topic.ores"),
                        "source-pubsub-synchronized-payload-rejected.ores"));
        assertTrue(error.getMessage().contains("requires MessageData"),
                error.getMessage());
    }

    @Test
    void pubSubRejectsNominalPayloadThatRetainsEvaluatorOwner() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class, () -> run(
                        fixture("actor-pubsub-reject-nominal-topic.ores"),
                        "source-pubsub-nominal-payload-rejected.ores"));
        assertTrue(error.getMessage().contains("requires MessageData"),
                error.getMessage());
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(
                Path.of("contracts", "v1", "fixtures", name),
                StandardCharsets.UTF_8);
    }

    private static String run(String sourceText, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}
