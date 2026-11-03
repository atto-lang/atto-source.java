package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-runtime regression: revocation must invalidate producer commands
 * already admitted to the child mailbox but not yet dispatched.
 *
 * This is not a guest queue or atomic queue-mutation proof. It verifies that
 * the authority decision is revalidated against the live grant at dispatch.
 */
@Timeout(30)
final class QueueGrantRevocationFenceTest {
    @Test
    void revocationDeniesPreviouslyAdmittedButNotYetDispatchedProducerMail()
            throws Exception {
        CountDownLatch holdStarted = new CountDownLatch(1);
        CountDownLatch releaseHold = new CountDownLatch(1);
        LinkedBlockingQueue<String> producerProgress = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> observed = new LinkedBlockingQueue<>();
        AtomicReference<ActorRuntime.ActorRef<String>> queue = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 3, 64, 128))) {
            ActorRuntime.ActorRef<String> producer =
                    runtime.<String>spawnShared(() -> (message, context) -> {
                        queue.get().send(message);
                        producerProgress.add("forwarded:" + message);
                    });

            ActorRuntime.ActorRef<String> parent =
                    runtime.<String>spawnShared(() -> (message, context) -> {
                        switch (message) {
                            case "spawn" -> {
                                var child = runtime.<String>spawnChildShared(
                                        startup -> (command, childContext) -> {
                                            if (command.equals("hold")) {
                                                holdStarted.countDown();
                                                if (!releaseHold.await(5, TimeUnit.SECONDS)) {
                                                    throw new IllegalStateException(
                                                            "mailbox hold was not released");
                                                }
                                            } else {
                                                boolean permitted = childContext
                                                        .queueCommandAuthority().orElseThrow()
                                                        .permits(ActorRuntime.QueueOperation.SEND, 0);
                                                observed.add(command + ":" + permitted);
                                            }
                                        });
                                queue.set(child.ref());
                                observed.add("spawned");
                            }
                            case "grant" -> {
                                runtime.grantCurrentQueueProducer(queue.get(), 0, producer);
                                observed.add("granted");
                            }
                            case "revoke" -> {
                                runtime.revokeCurrentQueueProducer(queue.get(), 0);
                                observed.add("revoked");
                            }
                            default -> observed.add("unexpected");
                        }
                    });

            try {
                parent.send("spawn");
                assertEquals("spawned", observed.poll(5, TimeUnit.SECONDS));

                parent.send("grant");
                assertEquals("granted", observed.poll(5, TimeUnit.SECONDS));

                // Keep the child in an executing turn while the producer
                // admits a command behind it in the child's mailbox.
                queue.get().send("hold");
                assertTrue(holdStarted.await(5, TimeUnit.SECONDS),
                        "child did not enter the controlled hold");

                producer.send("queued-before-revoke");
                assertEquals("forwarded:queued-before-revoke",
                        producerProgress.poll(5, TimeUnit.SECONDS),
                        "producer did not submit the message before revocation");

                // The parent revokes while the producer command is pending
                // behind the hold. Authority must be checked again when the
                // child eventually dispatches that queued command.
                parent.send("revoke");
                assertEquals("revoked", observed.poll(5, TimeUnit.SECONDS));

                releaseHold.countDown();
                assertEquals("queued-before-revoke:false",
                        observed.poll(5, TimeUnit.SECONDS),
                        "revocation must invalidate queued, not-yet-dispatched mail");

                producer.send("sent-after-revoke");
                assertEquals("forwarded:sent-after-revoke",
                        producerProgress.poll(5, TimeUnit.SECONDS));
                assertEquals("sent-after-revoke:false",
                        observed.poll(5, TimeUnit.SECONDS),
                        "all later commands from the revoked producer must be denied");
            } finally {
                releaseHold.countDown();
            }
        }
    }
}
