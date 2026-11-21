package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceActorPubSubExecutionTest {
    @Test
    void suspendsTaskBoundaryRejectsOtherAndNestedNonSendCapabilities() {
        for (String type : new String[] {
                "Topic<String>",
                "Subscription<String>",
                "ActorGroup",
                "Option<Topic<String>>",
                "Option<Subscription<String>>"
        }) {
            String source = "suspends fnc bad(" + type
                    + " handle): void { return; } pub fnc main(): void { return; }";
            Ast.Program parsed = Parser.parse(source);
            Ast.FunctionDecl bad = (Ast.FunctionDecl)
                    parsed.modules().getFirst().declarations().getFirst();
            assertTrue(bad.suspends(), type);
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), type);
            assertTrue(denied.getMessage().contains("must be concrete owned task-safe data"),
                    type + ": " + denied.getMessage());
        }
    }

    @Test
    void suspendsTaskReturnCannotExportActorOwnedTopic() {
        String source = "suspends fnc leak(): Topic<String> {"
                + " return actor.group(\"restricted\").topic<String>(\"events\", \"reliable\", 1);"
                + " } pub fnc main(): void { return; }";
        Parser.parse(source);
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(denied.getMessage().contains("must be concrete owned task-safe data"),
                denied.getMessage());
    }

    @Test
    void suspendsActorReceiveRetainsActorLocalTopicAndSubscriptionUse() throws Exception {
        // Direct actor-owned handle use must remain legal; only crossing the
        // independent SourceTask boundary is denied.
        String source = fixture("actor-pubsub-v1.atto");
        Parser.parse(source);
        OresCompiler.parseAndTypeCheck(source);
    }

    @Test
    void suspendsTopicCapabilityFailsTaskSendBoundaryAfterParserAdmission() throws Exception {
        String source = fixture("actor-pubsub-reject-suspends-capability.atto");
        assertEquals(fixture("actor-pubsub-reject-suspends-capability.ores"), source);
        Ast.Program parsed = Parser.parse(source);
        Ast.FunctionDecl bad = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        assertTrue(bad.suspends());
        assertFalse(bad.async());
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(denied.getMessage().contains("must be concrete owned task-safe data"),
                denied.getMessage());
        assertFalse(denied.getMessage().contains("'async' callable modifier is removed"),
                "retired syntax rejection is not a Topic Send proof");
    }

    @Test
    void positivePubSubFixturesUseSuspendsOnlyEntrypoints() throws Exception {
        // A lexical migration guard, not a runtime proof. The selected
        // Polyglot tests below must still execute these source bytes.
        for (String name : new String[] {
                "actor-pubsub-v1.ores",
                "actor-pubsub-v1.atto",
                "actor-pubsub-multigroup-v1.ores",
                "actor-pubsub-multigroup-v1.atto",
                "actor-pubsub-legacy-fanout-two.atto"
        }) {
            String source = fixture(name);
            assertTrue(source.contains("pub suspends fnc main(): void {"),
                    "missing canonical suspending entrypoint in " + name);
            assertFalse(source.contains("pub async routine main()"),
                    "retired async entrypoint remains in positive fixture " + name);
        }
    }


    @Test
    void sharedActorSubscribesAndReceivesTypedInMemoryPublication() throws Exception {
        assertEquals(
                "subscribed|p-1",
                run(fixture("actor-pubsub-v1.ores"), "source-actor-pubsub-v1.ores"));
    }

    @Test
    void attolangFilenameExecutesExistingPubSubGuest() throws Exception {
        // Explicit OresLanguage ID and MIME select the guest engine. This is
        // a .atto fixture regression, NOT proof of CLI extension discovery,
        // portable atomic try_publish semantics or Native Image execution.
        assertEquals(fixture("actor-pubsub-v1.ores"), fixture("actor-pubsub-v1.atto"));
        assertEquals(
                "subscribed|p-1",
                run(fixture("actor-pubsub-v1.atto"), "source-actor-pubsub-v1.atto"));
    }

    @Test
    void attolangExtensionPreservesAuthorityAndPayloadRejections() throws Exception {
        // Compile-negative protections must survive the .ores -> .atto migration.
        assertEquals(
                fixture("actor-pubsub-reject-actor-authority.ores"),
                fixture("actor-pubsub-reject-actor-authority.atto"));
        assertEquals(
                fixture("actor-pubsub-reject-nested-callable-topic.ores"),
                fixture("actor-pubsub-reject-nested-callable-topic.atto"));

        PolyglotException authorityError = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-actor-authority.atto"),
                        "source-pubsub-actor-authority-rejected.atto"));
        assertTrue(
                authorityError.getMessage().contains(
                        "actor code may resolve an existing Topic<T> but cannot define one"),
                authorityError.getMessage());

        PolyglotException payloadError = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-nested-callable-topic.atto"),
                        "source-pubsub-nested-callable-topic-rejected.atto"));
        assertTrue(payloadError.getMessage().contains("Topic payload"),
                payloadError.getMessage());
    }

    @Test
    void canonicalShareBangUsesSameOwnershipAstInsidePortableTryPublish() {
        // This uses the real compiler Parser, but is NOT a guest try_publish pass.
        String source = """
                pub suspends fnc main(): void {
                  val String event = "ready";
                  val group = actor.group("payments");
                  val topic = group.topic<String>("events", "reliable", 1);
                  val receipt = topic.try_publish(%s);
                  return;
                }
                """;
        Ast.Program legacy = Parser.parse(source.formatted("rt share event"));
        assertEquals(legacy, Parser.parse(source.formatted("share! event")));
        assertEquals(legacy, Parser.parse(source.formatted("share!event")));
        assertThrows(RuntimeException.class,
                () -> Parser.parse(source.formatted("share !event")),
                "split share ! is not an ownership operator");

        for (String spelling : new String[]{
                "rt share event", "share! event", "share!event"}) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source.formatted(spelling)),
                    spelling);
            assertTrue(error.getMessage().contains("unknown Topic callable member"),
                    spelling + ": " + error.getMessage());
        }
    }

    @Test
    void canonicalShareBangTypeChecksWithExistingAsyncTopicPublish() {
        // Topic.publish is already typed; unlike portable try_publish it does
        // NOT establish all-or-nothing synchronous RELIABLE receipt semantics.
        String source = """
                pub suspends fnc main(): void {
                  val String event = "ready";
                  val group = actor.group("payments");
                  val topic = group.topic<String>("events", "reliable", 1);
                  val future = topic.publish(%s);
                  return;
                }
                """;
        for (String spelling : new String[]{
                "rt share event", "share! event", "share!event"}) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> OresCompiler.parseAndTypeCheck(source.formatted(spelling)),
                    spelling);
        }
    }

    @Test
    void portableTryPublishRejectedUntilNativeLowering() throws Exception {
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(
                        fixture("actor-pubsub-reject-unlowered-try-publish.atto"),
                        "source-pubsub-try-publish-not-lowered.atto"));
        assertTrue(error.getMessage().contains("unknown Topic callable member"),
                error.getMessage());
    }

    @Test
    @Timeout(30)
    void legacyAsyncGuestFanoutRunsOnTwoIndependentSharedActors() throws Exception {
        // This executes compiled guest actor receive handlers, unlike a
        // host-only EventBus unit test. It does not certify portable v1
        // synchronous try_publish or sequence-bearing delivery envelopes.
        assertEquals(
                "ready|ready|event-42|event-42",
                run(
                        fixture("actor-pubsub-legacy-fanout-two.atto"),
                        "actor-pubsub-legacy-fanout-two.atto"));
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
    void attoMultiGroupGuestPreservesOtherGroupAfterLeave() throws Exception {
        // Independent output check: do not silently treat an identical
        // .ores text fixture as proof that the .atto guest was executed.
        assertEquals(
                fixture("actor-pubsub-multigroup-v1.ores"),
                fixture("actor-pubsub-multigroup-v1.atto"));
        assertEquals(
                "ready-b|b-still-live",
                run(
                        fixture("actor-pubsub-multigroup-v1.atto"),
                        "actor-pubsub-multigroup-v1.atto"));
    }

    @Test
    void attoPubSubTypeAndAuthorityNegativesStayFailClosed() throws Exception {
        // Keep the established legacy rejection diagnostic on exactly the
        // same bytes with a new .atto filename. This does not imply that
        // portable try_publish or native AOT lowering is implemented.
        String[][] rejected = {
                {"actor-pubsub-reject-payload-type", "Topic.publish value"},
                {"actor-pubsub-reject-private-subscribe",
                        "Topic.subscribe currently requires a shared actor"},
                {"actor-pubsub-reject-mailbox-capability",
                        "cannot use Topic across an actor boundary"},
                {"actor-pubsub-reject-async-capability",
                        "must be concrete owned task-safe data"},
                {"actor-pubsub-reject-suspends-capability",
                        "must be concrete owned task-safe data"}
        };
        for (String[] vector : rejected) {
            String stem = vector[0];
            assertEquals(fixture(stem + ".ores"), fixture(stem + ".atto"),
                    "fixture extension migration altered guest source: " + stem);
            PolyglotException error = assertThrows(PolyglotException.class,
                    () -> run(fixture(stem + ".atto"), stem + ".atto"),
                    "compiler unexpectedly accepted rejected .atto source: " + stem);
            assertTrue(error.getMessage().contains(vector[1]),
                    stem + ": " + error.getMessage());
        }
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
    void pubSubHandlesCannotCrossIndependentSuspendsTaskBoundary() throws Exception {
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
