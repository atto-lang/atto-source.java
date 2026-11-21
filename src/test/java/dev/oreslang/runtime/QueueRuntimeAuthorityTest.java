package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-runtime proof obligations. These do not enable queue source lowering
 * or demonstrate compiler-backed Oreslang guest authorization.
 */
@Timeout(30)
final class QueueRuntimeAuthorityTest {
    @Test
    void runtimeSenderAndStructuredParentNotGuestPayloadControlAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var observed = new LinkedBlockingQueue<String>();
            var queue = new AtomicReference<ActorRuntime.ActorRef<String>>();
            var handle = new AtomicReference<ActorRuntime.ActorHandle<String>>();
            var leaked = new AtomicReference<ActorRuntime.QueueCommandAuthority>();
            var producer0 = new AtomicReference<ActorRuntime.ActorRef<String>>();

            ActorRuntime.ActorRef<String> producer = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        if (queue.get() != null) {
                            queue.get().send(message);
                        }
                    });
            producer0.set(producer);
            ActorRuntime.ActorRef<String> outsider = runtime.<String>spawnShared(
                    () -> (message, context) -> queue.get().send(message));

            ActorRuntime.ActorRef<String> parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        if (message.equals("spawn")) {
                            assertTrue(context.queueCommandAuthority().isEmpty(),
                                    "root actors have no structured supervisor grant");
                            ActorRuntime.ActorHandle<String> child =
                                    runtime.<String>spawnChildShared(startup -> {
                                        // Startup carries no current mail and must not
                                        // mint either supervisor or producer authority.
                                        var bootAuthority = startup.queueCommandAuthority()
                                                .orElseThrow();
                                        assertThrows(SecurityException.class,
                                                () -> bootAuthority.permits(
                                                        ActorRuntime.QueueOperation.CLOSE, -1));
                                        return (command, childContext) -> {
                                                var authority = childContext.queueCommandAuthority()
                                                        .orElseThrow();
                                                leaked.set(authority);
                                                try {
                                                    switch (command) {
                                                        case "grant" -> {
                                                            authority.grantProducer(0, producer0.get().id());
                                                            observed.add("granted");
                                                        }
                                                        case "revoke" -> {
                                                            authority.revokeProducer(0);
                                                            observed.add("revoked");
                                                        }
                                                        case "send" -> observed.add(
                                                                "send:" + authority.permits(
                                                                        ActorRuntime.QueueOperation.SEND, 0));
                                                        case "close" -> observed.add(
                                                                "close:" + authority.permits(
                                                                        ActorRuntime.QueueOperation.CLOSE, -1));
                                                        case "forge-grant" -> {
                                                            authority.grantProducer(1, outsider.id());
                                                            observed.add("FAIL: forged grant");
                                                        }
                                                        case "grant-invalid" -> {
                                                            authority.grantProducer(1,
                                                                    ActorRuntime.ActorId.create());
                                                            observed.add("FAIL: stale or unknown producer granted");
                                                        }
                                                        default -> observed.add("unknown");
                                                    }
                                                } catch (SecurityException denial) {
                                                    observed.add("denied");
                                                }
                                            };
                                    });
                            handle.set(child);
                            queue.set(child.ref());
                            observed.add("spawned");
                        } else if (message.equals("grant")) {
                            handle.get().send("grant");
                        } else if (message.equals("grant-invalid")) {
                            handle.get().send("grant-invalid");
                        } else if (message.equals("revoke")) {
                            handle.get().send("revoke");
                        } else if (message.equals("close")) {
                            handle.get().send("close");
                        }
                    });

            parent.send("spawn");
            expect(observed, "spawned");
            assertNotNull(queue.get());
            // The child is a structured descendant of the parent, but
            // only the runtime-provided sender of each active mail is trusted.
            // This test must not infer authority from the payload. A host
            // message has no ActorMail.sender and fails the runtime-bound gate.
            queue.get().send("close");
            expect(observed, "denied");

            outsider.send("forge-grant");
            expect(observed, "denied");
            parent.send("grant-invalid");
            expect(observed, "denied");
            parent.send("grant");
            expect(observed, "granted");
            producer.send("send");
            expect(observed, "send:true");
            outsider.send("send");
            expect(observed, "send:false");
            outsider.send("close");
            expect(observed, "close:false");
            parent.send("close");
            expect(observed, "close:true");
            parent.send("revoke");
            expect(observed, "revoked");
            producer.send("send");
            expect(observed, "send:false");

            assertNotNull(leaked.get());
            assertThrows(SecurityException.class,
                    () -> leaked.get().permits(ActorRuntime.QueueOperation.CLOSE, -1),
                    "saved authority cannot be used by the host or a different actor");
        }
    }

    @Test
    void directParentDelegationUsesLiveActorRefsAndRevokesLinearly() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var results = new LinkedBlockingQueue<String>();
            var queue = new AtomicReference<ActorRuntime.ActorRef<String>>();
            var producer = new AtomicReference<ActorRuntime.ActorRef<String>>();

            producer.set(runtime.<String>spawnShared(
                    () -> (message, context) -> queue.get().send(message)));
            var outsider = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        if (message.equals("grant")) {
                            try {
                                runtime.grantCurrentQueueProducer(
                                        queue.get(), 0, producer.get());
                                results.add("UNSAFE");
                            } catch (SecurityException expected) {
                                results.add("outsider-denied");
                            }
                        }
                    });
            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        switch (message) {
                            case "spawn" -> {
                                var child = runtime.<String>spawnChildShared(
                                        startup -> (value, childContext) -> {
                                            var authority = childContext.queueCommandAuthority()
                                                    .orElseThrow();
                                            results.add("send:" + authority.permits(
                                                    ActorRuntime.QueueOperation.SEND, 0));
                                        });
                                queue.set(child.ref());
                                results.add("spawned");
                            }
                            case "grant" -> {
                                runtime.grantCurrentQueueProducer(
                                        queue.get(), 0, producer.get());
                                results.add("granted");
                            }
                            case "revoke" -> {
                                runtime.revokeCurrentQueueProducer(queue.get(), 0);
                                results.add("revoked");
                            }
                            default -> results.add("unexpected");
                        }
                    });
            parent.send("spawn");
            expect(results, "spawned");
            assertThrows(SecurityException.class,
                    () -> runtime.grantCurrentQueueProducer(
                            queue.get(), 0, producer.get()),
                    "host main cannot grant on behalf of an actor parent");

            outsider.send("grant");
            expect(results, "outsider-denied");
            parent.send("grant");
            expect(results, "granted");
            producer.get().send("probe");
            expect(results, "send:true");
            parent.send("revoke");
            expect(results, "revoked");
            producer.get().send("probe");
            expect(results, "send:false");
        }
    }

    private static void expect(LinkedBlockingQueue<String> observed, String expected)
            throws InterruptedException {
        assertEquals(expected, observed.poll(5, TimeUnit.SECONDS));
    }
}
