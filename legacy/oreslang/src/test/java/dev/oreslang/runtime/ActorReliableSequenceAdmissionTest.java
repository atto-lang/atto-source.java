package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorReliableSequenceAdmissionTest {
    @Test
    void rejectedReliablePublicationDoesNotConsumeSequence() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorEventBus bus = group.events();
            bus.defineTopic("seq", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch readDone = new CountDownLatch(2);
            java.util.List<Long> sequences =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            AtomicReference<Throwable> failed = new AtomicReference<>();
            ActorRuntime.ActorRef<String> observer = runtime.spawnShared(() -> {
                AtomicReference<ActorEventBus.Subscription<Integer>> subscription =
                        new AtomicReference<>();
                return (message, ctx) -> {
                    try {
                        if ("subscribe".equals(message)) {
                            join.join();
                            subscription.set(bus.subscribe("seq"));
                            ready.countDown();
                        } else {
                            sequences.add(subscription.get().tryRead().orElseThrow().sequence());
                            readDone.countDown();
                        }
                    } catch (Throwable failure) {
                        failed.set(failure);
                        ready.countDown();
                        readDone.countDown();
                    }
                };
            });
            observer.send("subscribe");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNull(failed.get());
            assertTrue(bus.publishSystem("seq", 1).completion().isDone());
            ActorEventBus.PublishReceipt waiting = bus.publishSystem("seq", 2);
            assertFalse(waiting.completion().isDone());
            assertThrows(ActorEventBus.EventBackpressureException.class,
                    () -> bus.publishSystem("seq", 3));
            assertEquals(2L, bus.topics().getFirst().published());
            observer.send("read");
            waiting.completion().get(5, TimeUnit.SECONDS);
            observer.send("read");
            assertTrue(readDone.await(5, TimeUnit.SECONDS));
            assertNull(failed.get());
            assertEquals(java.util.List.of(1L, 2L), sequences);
            group.close();
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }
}
