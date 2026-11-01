package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceActorPubSubExecutionTest {
    @Test
    void sharedActorSubscribesAndReceivesTypedInMemoryPublication() throws Exception {
        String program = """
                define actor Subscriber as
                  suspends receive(ActorMail<String> mail): void {
                    val group = actor.group("payments");
                    val topic = group.topic<String>("payment.completed");
                    val subscription = topic.subscribe();

                    self.send("subscribed");
                    val value = await subscription.next();
                    self.send(value);

                    subscription.close();
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val group = actor.group("payments");
                  val topic = group.topic<String>(
                    "payment.completed",
                    "reliable",
                    4
                  );

                  val subscriber = spawn Subscriber();
                  await subscriber.get_ready();
                  group.join(subscriber);

                  subscriber.send("start");

                  val ready = (await subscriber.outputs.next()).value.unwrap();
                  stdio.stdout.write(ready.value);
                  stdio.stdout.write("|");

                  await topic.publish("p-1");

                  val received = (await subscriber.outputs.next()).value.unwrap();
                  stdio.stdout.write(received.value);

                  await subscriber.get_done();
                  group.leave(subscriber);
                  group.close();
                  return;
                }
                """;

        assertEquals(
                "subscribed|p-1",
                run(program, "source-actor-pubsub-v1.ores"));
    }

    @Test
    void topicPayloadTypeMismatchFailsBeforeExecution() {
        String program = """
                pub async routine main(): void {
                  val group = actor.group("typed");
                  val topic = group.topic<String>("events", "reliable", 4);
                  await topic.publish(42);
                  return;
                }
                """;

        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(program, "source-actor-pubsub-type-mismatch.ores"));
        assertTrue(error.getMessage().contains("Topic.publish value"), error.getMessage());
    }

    @Test
    void privateActorCannotUseZeroCopyTopicSubscription() {
        String program = """
                define isoactor PrivateSubscriber as
                  receive(ActorMail<String> mail): void {
                    val group = actor.group("private-events");
                    val topic = group.topic<String>("events");
                    val subscription = topic.subscribe();
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val group = actor.group("private-events");
                  group.topic<String>("events", "reliable", 4);
                  val subscriber = spawn PrivateSubscriber();
                  await subscriber.get_ready();
                  group.join(subscriber);
                  subscriber.send("start");
                  await subscriber.get_done();
                  group.close();
                  return;
                }
                """;

        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(program, "source-private-pubsub-rejected.ores"));
        assertTrue(
                error.getMessage().contains("Topic.subscribe currently requires a shared actor"),
                error.getMessage());
    }

    @Test
    void pubSubHandlesCannotCrossActorMailboxBoundary() {
        String program = """
                define actor BadReceiver as
                  receive(ActorMail<Topic<String>> mail): void {
                    self.end();
                    return;
                  }
                end

                pub routine main(): void {
                  return;
                }
                """;

        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(program, "source-pubsub-mailbox-capability-rejected.ores"));
        assertTrue(
                error.getMessage().contains("cannot use Topic across an actor boundary"),
                error.getMessage());
    }

    @Test
    void pubSubHandlesCannotCrossAsyncTaskBoundary() {
        String program = """
                async fnc bad(Topic<String> topic): void {
                  return;
                }

                pub routine main(): void {
                  return;
                }
                """;

        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(program, "source-pubsub-async-capability-rejected.ores"));
        assertTrue(
                error.getMessage().contains("must be concrete owned task-safe data"),
                error.getMessage());
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
