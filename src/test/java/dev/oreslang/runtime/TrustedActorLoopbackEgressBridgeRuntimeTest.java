package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * REAL ActorRuntime.spawnUntrusted actor-turn integration test.
 * Must execute in source/Graal CI, NOT in the host-only carrier's fake runtime.
 * Java actor identity alone does not prove protected guest isolation.
 */
final class TrustedActorLoopbackEgressBridgeRuntimeTest {
    private static volatile TrustedActorLoopbackEgressBridge bridge;
    private static volatile AtomicLong observedTicket;
    private static volatile CountDownLatch submitted;
    private static volatile CountDownLatch completed;
    private static volatile CountDownLatch spoofDenied;
    private static volatile AtomicReference<Throwable> actorFailure;
    private static volatile AtomicReference<TrustedActorLoopbackEgressBridge.Poll> response;

    @Test void actualUntrustedTurnEnqueuesOnlyItsHostBoundRequest() throws Exception {
        observedTicket = new AtomicLong();
        submitted = new CountDownLatch(1);
        completed = new CountDownLatch(1);
        spoofDenied = new CountDownLatch(1);
        actorFailure = new AtomicReference<>();
        response = new AtomicReference<>();
        var serverError = new AtomicReference<Throwable>();
        try (var server = new ServerSocket(
                0, 4, InetAddress.getByName("127.0.0.1"));
             var runtime = new ActorRuntime()) {
            Thread service = new Thread(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(4000);
                    var reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Throwable failure) { serverError.set(failure); }
            }, "actor-egress-bridge-loopback-server");
            service.setDaemon(true);
            service.start();

            var actor = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                try {
                    if ("start".equals(message)) {
                        long id = bridge.submitGetFromCurrentActor();
                        observedTicket.set(id);
                        // Ordinary direct networking stays DENIED.
                        try {
                            TrustedNetworkIngressGuard.rejectUntrustedDirectNetwork("http.GET");
                            throw new AssertionError("direct UNTRUSTED network was allowed");
                        } catch (SecurityException expected) { }
                        submitted.countDown();
                    } else if ("poll".equals(message)) {
                        var outcome = bridge.pollFromCurrentActor(observedTicket.get());
                        if (outcome.state() != TrustedActorLoopbackEgressBridge.State.PENDING) {
                            response.set(outcome);
                            completed.countDown();
                        }
                    }
                } catch (Throwable error) {
                    actorFailure.set(error);
                    submitted.countDown();
                    completed.countDown();
                }
            });
            var foreign = runtime.<String>spawnUntrusted(context -> (message, turn) -> {
                try {
                    bridge.submitGetFromCurrentActor();
                    actorFailure.set(new AssertionError("foreign actor used bound bridge"));
                } catch (SecurityException expected) {
                    spoofDenied.countDown();
                }
            });
            var gate = new TrustedHierarchicalNetworkGate();
            long clockStart = System.nanoTime();
            java.util.function.LongSupplier clock = () -> System.nanoTime() - clockStart;
            var policy = new TrustedUntrustedNetworkQuota.Policy(20, 2, 1, 1, 20, 2);
            var child = gate.createHostScope(policy, clock);
            var supervisor = gate.createHostScope(policy, clock);
            var tenant = gate.createHostScope(policy, clock);
            try (var executor = Executors.newSingleThreadExecutor()) {
                try {
                    bridge = new TrustedActorLoopbackEgressBridge(
                            runtime, actor, gate, child, supervisor, tenant,
                            server.getLocalPort(), executor);
                    assertThrows(SecurityException.class, () -> bridge.submitGetFromCurrentActor());
                    foreign.send("attempt");
                    assertTrue(spoofDenied.await(3, TimeUnit.SECONDS));
                    actor.send("start");
                    assertTrue(submitted.await(3, TimeUnit.SECONDS));
                    assertTrue(observedTicket.get() > 0);
                    assertEquals(null, actorFailure.get());
                    for (int round = 0; round < 30 && completed.getCount() > 0; round++) {
                        actor.send("poll");
                        completed.await(100, TimeUnit.MILLISECONDS);
                    }
                    assertEquals(null, actorFailure.get());
                    assertTrue(completed.getCount() == 0);
                    assertEquals(TrustedActorLoopbackEgressBridge.State.OK,
                            response.get().state());
                    assertEquals(200, response.get().httpStatus());
                } finally {
                    if (bridge != null) bridge.close();
                    bridge = null;
                    child.close(); supervisor.close(); tenant.close();
                }
            }
            service.join(3000);
            assertEquals(null, serverError.get());
        } finally {
            bridge = null;
            observedTicket = null;
            submitted = null;
            completed = null;
            spoofDenied = null;
            actorFailure = null;
            response = null;
        }
    }
}
