package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable admission-time grant fence regression. A bounded host-only
 * mailbox pause establishes an exact order; it is NOT a guest or realtime
 * scheduler endorsement. Execute on JDK25 before promoting the queue.
 */
@Timeout(30)
final class QueueGrantAdmissionFenceTest {
    @Test
    void producerMailQueuedBeforeGrantStaysUnauthorized() throws Exception {
        CountDownLatch holdStarted = new CountDownLatch(1);
        CountDownLatch releaseHold = new CountDownLatch(1);
        LinkedBlockingQueue<String> forwarded = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> replies = new LinkedBlockingQueue<>();
        AtomicReference<ActorRuntime.ActorRef<String>> queue = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 3, 64, 128))) {
            ActorRuntime.ActorRef<String> producer =
                    runtime.<String>spawnShared(() -> (msg, context) -> {
                        queue.get().send("probe");
                        forwarded.add("forwarded");
                    });

            ActorRuntime.ActorRef<String> parent =
                    runtime.<String>spawnShared(() -> (msg, context) -> {
                        switch (msg) {
                            case "spawn" -> {
                                var child = runtime.<String>spawnChildShared(
                                        startup -> (command, childContext) -> {
                                            if (command.equals("hold")) {
                                                holdStarted.countDown();
                                                if (!releaseHold.await(5, TimeUnit.SECONDS)) {
                                                    throw new IllegalStateException(
                                                            "mailbox hold was not released");
                                                }
                                            } else if (command.equals("probe")) {
                                                boolean permitted = childContext
                                                        .queueCommandAuthority().orElseThrow()
                                                        .permits(ActorRuntime.QueueOperation.SEND, 0);
                                                replies.add(Boolean.toString(permitted));
                                            }
                                        });
                                queue.set(child.ref());
                                replies.add("spawned");
                            }
                            case "grant" -> {
                                runtime.grantCurrentQueueProducer(queue.get(), 0, producer);
                                replies.add("granted");
                            }
                            default -> replies.add("unexpected");
                        }
                    });

            try {
                parent.send("spawn");
                assertEquals("spawned", replies.poll(5, TimeUnit.SECONDS));
                queue.get().send("hold");
                assertTrue(holdStarted.await(5, TimeUnit.SECONDS),
                        "queue handler did not enter the controlled hold");

                // The producer's first mail has already acquired a mailbox
                // sequence when this sender returns from child.send.
                producer.send("forward");
                assertEquals("forwarded", forwarded.poll(5, TimeUnit.SECONDS));

                parent.send("grant");
                assertEquals("granted", replies.poll(5, TimeUnit.SECONDS));
                releaseHold.countDown();
                assertEquals("false", replies.poll(5, TimeUnit.SECONDS),
                        "messages queued before grant must not gain authority");

                producer.send("forward");
                assertEquals("forwarded", forwarded.poll(5, TimeUnit.SECONDS));
                assertEquals("true", replies.poll(5, TimeUnit.SECONDS),
                        "mail admitted after the grant can use its producer slot");
            } finally {
                releaseHold.countDown();
            }
        }
    }
}
