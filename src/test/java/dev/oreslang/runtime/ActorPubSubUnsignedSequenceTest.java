package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Exact unsigned u64 receipt boundary shared with LLVM's uint64_t native ABI.
 * These arithmetic tests are NOT compiled AttoLang guest or Native Image proof.
 */
final class ActorPubSubUnsignedSequenceTest {
    @Test
    void signedStorageCrossesUnsignedU64BoundaryWithoutReusingZero() {
        assertEquals(1L, ActorEventBus.nextTopicSequenceUnsigned(0L));
        assertEquals(Long.MAX_VALUE,
                ActorEventBus.nextTopicSequenceUnsigned(Long.MAX_VALUE - 1L));
        assertEquals(Long.MIN_VALUE,
                ActorEventBus.nextTopicSequenceUnsigned(Long.MAX_VALUE),
                "2^63 is a legal unsigned topic sequence");
        assertEquals(-1L, ActorEventBus.nextTopicSequenceUnsigned(-2L),
                "unsigned 2^64-1 is the final legal sequence");
        assertThrows(ArithmeticException.class,
                () -> ActorEventBus.nextTopicSequenceUnsigned(-1L),
                "unsigned u64 must not roll over and reuse zero");
    }

    @Test
    void guestReceiptSerializesUnsignedValueRatherThanSignedJavaLong() {
        var done = OresFuture.completed(List.<Void>of());
        var firstHigh = new ActorEventBus.PublishReceipt(
                0, 0, 0, 0, done, Long.MIN_VALUE);
        var last = new ActorEventBus.PublishReceipt(
                0, 0, 0, 0, done, -1L);
        assertEquals("9223372036854775808", firstHigh.sequenceUnsignedString());
        assertEquals("18446744073709551615", last.sequenceUnsignedString());
        assertEquals(0L, new ActorEventBus.PublishReceipt(
                0, 0, 0, 0, done).sequence(),
                "zero still means no committed publication in legacy receipts");
    }

    @Test
    void actualReliablePublicationPreservesUnsignedSequenceAfterFullAndExhaustion()
            throws Exception {
        // A private test fixture seeds the hidden topic-local counter. This
        // exercises the real atomic RELIABLE publication/receipt path without
        // publishing 2^63 events, and does NOT expose a production setter.
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorEventBus events = group.events();
            events.topic("unsigned-boundary", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            var topicsField = ActorEventBus.class.getDeclaredField("topics");
            topicsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> topics = (Map<String, ?>) topicsField.get(events);
            Object topicState = topics.get("unsigned-boundary");
            var sequenceField = topicState.getClass().getDeclaredField("sequence");
            sequenceField.setAccessible(true);
            AtomicLong counter = (AtomicLong) sequenceField.get(topicState);

            counter.set(Long.MAX_VALUE - 1L);
            ActorEventBus.PublishReceipt signedLast =
                    events.tryPublishReliableSystem("unsigned-boundary", "last-signed")
                            .orElseThrow();
            assertEquals("9223372036854775807",
                    signedLast.sequenceUnsignedString());
            ActorEventBus.PublishReceipt unsignedFirst =
                    events.tryPublishReliableSystem("unsigned-boundary", "first-unsigned")
                            .orElseThrow();
            assertEquals(Long.MIN_VALUE, unsignedFirst.sequence());
            assertEquals("9223372036854775808",
                    unsignedFirst.sequenceUnsignedString(),
                    "real topic admission must not reject at 2^63");

            // Exhaustion is denied without admitting an event, consuming
            // sequence zero, or incrementing the topic's publication count.
            counter.set(-2L);
            ActorEventBus.PublishReceipt unsignedLast =
                    events.tryPublishReliableSystem("unsigned-boundary", "last-unsigned")
                            .orElseThrow();
            assertEquals("18446744073709551615",
                    unsignedLast.sequenceUnsignedString());
            long acceptedBeforeExhaustion = events.topics().getFirst().published();
            assertThrows(ArithmeticException.class,
                    () -> events.tryPublishReliableSystem("unsigned-boundary", "wrapped"));
            assertEquals(-1L, counter.get(),
                    "exhaustion must preserve committed last sequence");
            assertEquals(acceptedBeforeExhaustion,
                    events.topics().getFirst().published(),
                    "exhaustion must not commit an event or issue a phantom receipt");
        }
    }


    @Test
    void fullAtSignedBoundaryDoesNotBurnUnsignedSequenceOrAdmitPartialDelivery()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            ActorEventBus events = group.events();
            ActorEventBus.Topic<String> topic = events.topic(
                    "unsigned-full", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            CountDownLatch subscribed = new CountDownLatch(1);
            CountDownLatch readComplete = new CountDownLatch(1);
            AtomicReference<String> received = new AtomicReference<>();
            AtomicReference<Throwable> actorError = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            if ("subscribe".equals(message)) {
                                join.join();
                                topic.subscribe();
                                subscribed.countDown();
                            } else if ("read".equals(message)) {
                                var event = topic.subscribe().tryRead().orElseThrow();
                                received.set(event.value());
                                readComplete.countDown();
                            }
                        } catch (Throwable failure) {
                            actorError.compareAndSet(null, failure);
                            subscribed.countDown();
                            readComplete.countDown();
                        }
                    });
            actor.send("subscribe");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS),
                    "actor must establish an actual bounded subscription");
            assertNull(actorError.get());

            var topicsField = ActorEventBus.class.getDeclaredField("topics");
            topicsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> topics = (Map<String, ?>) topicsField.get(events);
            var sequenceField = topics.get("unsigned-full").getClass()
                    .getDeclaredField("sequence");
            sequenceField.setAccessible(true);
            AtomicLong counter = (AtomicLong) sequenceField.get(topics.get("unsigned-full"));
            counter.set(Long.MAX_VALUE);

            ActorEventBus.PublishReceipt first =
                    events.tryPublishReliableSystem("unsigned-full", "first")
                            .orElseThrow();
            assertEquals("9223372036854775808", first.sequenceUnsignedString());
            assertEquals(1, first.accepted());
            assertTrue(first.completion().isDone());
            assertTrue(events.tryPublishReliableSystem("unsigned-full", "full").isEmpty(),
                    "second atomic admission must fail when the one-slot queue is full");
            assertEquals(Long.MIN_VALUE, counter.get(),
                    "Full must not advance the unsigned-u64 topic sequence");
            assertEquals(1L, events.topics().getFirst().published(),
                    "Full must not count as a committed event");

            actor.send("read");
            assertTrue(readComplete.await(5, TimeUnit.SECONDS));
            assertNull(actorError.get());
            assertEquals("first", received.get(),
                    "the refused event must never appear in the subscriber mailbox");
            ActorEventBus.PublishReceipt afterRead =
                    events.tryPublishReliableSystem("unsigned-full", "after")
                            .orElseThrow();
            assertEquals("9223372036854775809", afterRead.sequenceUnsignedString(),
                    "the next accepted event uses the contiguous unsigned sequence");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

}
