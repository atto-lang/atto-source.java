package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.AbstractMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Source/API boundary checks for the current-AsyncNativeIo loopback bridge.
 * Includes exact runtime lifecycle behavior; CI still must execute it on JDK25.
 */
@Timeout(30)
final class ProxyLoopbackBridgeContractTest {
    private record TestAccess(
            ActorRuntime actors,
            AsyncNativeIo nativeIo,
            java.util.concurrent.atomic.AtomicReference<String> permission,
            java.util.concurrent.atomic.AtomicReference<String> publicationPermission)
            implements ProxyLoopbackBridge.RuntimeAccess {
        @Override public void requireNetPermission(String endpoint, String operation) {
            permission.set(endpoint + " " + operation);
        }
        @Override public <T> OresFuture<T> submitNet(
                String endpoint, String operation, java.util.concurrent.Callable<T> work) {
            publicationPermission.set(endpoint + " " + operation);
            return nativeIo.submit(operation, work);
        }
    }

    private static TestAccess access(ActorRuntime runtime, AsyncNativeIo io) {
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        return new TestAccess(
                runtime,
                io,
                new java.util.concurrent.atomic.AtomicReference<>(),
                new java.util.concurrent.atomic.AtomicReference<>());
    }

    private static HttpServer fixedBodyServer(byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                exchange.getResponseHeaders().add("Set-Cookie", "one=1");
                exchange.getResponseHeaders().add("Set-Cookie", "two=2");
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static URI target(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static final class BlockingSink extends OutputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override public void write(int value) throws IOException {
            write(new byte[] { (byte) value }, 0, 1);
        }

        @Override public void write(byte[] source, int offset, int length) {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* model an uncooperative native write */ }
            }
            bytes.write(source, offset, length);
        }

        void unblock() { release.countDown(); }
    }
    @Test
    void bridgeAndReceiptRemainTrustedPackagePrivateRuntimeTypes() throws Exception {
        assertFalse(Modifier.isPublic(ProxyLoopbackBridge.class.getModifiers()));
        assertFalse(Modifier.isProtected(ProxyLoopbackBridge.class.getModifiers()));

        var copy = ProxyLoopbackBridge.class.getDeclaredMethod(
                "copy",
                OresContext.class,
                URI.class,
                String.class,
                OutputStream.class,
                long.class);
        assertTrue(Modifier.isStatic(copy.getModifiers()));
        assertFalse(Modifier.isPublic(copy.getModifiers()));
        assertFalse(Modifier.isProtected(copy.getModifiers()));
        assertEquals(OresFuture.class, copy.getReturnType());

        assertFalse(Modifier.isPublic(ProxyLoopbackBridge.Receipt.class.getModifiers()));
        assertEquals(3, ProxyLoopbackBridge.Receipt.class.getRecordComponents().length);
    }

    // Exercise the real private revocation state machine, not a source-text approximation.
    private static final class RevocationHarness {
        final Object state;
        final java.lang.reflect.Method track;
        final java.lang.reflect.Method request;
        final java.lang.reflect.Method clear;
        final java.lang.reflect.Method requested;
        RevocationHarness() throws Exception {
            var type = Class.forName("dev.oreslang.runtime.ProxyLoopbackBridge$Revocation");
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            state = ctor.newInstance();
            track = type.getDeclaredMethod("track", CompletableFuture.class);
            request = type.getDeclaredMethod("request");
            clear = type.getDeclaredMethod("clear", CompletableFuture.class);
            requested = type.getDeclaredMethod("requested");
            for (var method : List.of(track, request, clear, requested)) method.setAccessible(true);
        }
        void track(CompletableFuture<?> future) throws Exception { track.invoke(state, future); }
        void request() throws Exception { request.invoke(state); }
        void clear(CompletableFuture<?> future) throws Exception { clear.invoke(state, future); }
        boolean requested() throws Exception { return (boolean) requested.invoke(state); }
    }

    @Test
    void cancellationBeforeAcquisitionRevokesAnAttachedFuture() throws Exception {
        var state = new RevocationHarness();
        state.request();
        assertTrue(state.requested());
        var opening = new CompletableFuture<Object>();
        state.track(opening);
        assertTrue(opening.isCancelled(), "late acquisition must not escape earlier cancellation");
        state.clear(opening);
        state.request(); // idempotent repeated cancellation
        assertTrue(state.requested());
    }

    @Test
    void cancellationDuringAcquisitionCancelsPendingOpening() throws Exception {
        var state = new RevocationHarness();
        var opening = new CompletableFuture<Object>();
        state.track(opening);
        state.request();
        assertTrue(opening.isCancelled());
        state.clear(opening);
    }

    @Test
    void cancellationAfterHeadersDoesNotCloseOrOverwriteDeliveredResponse() throws Exception {
        var state = new RevocationHarness();
        var opening = new CompletableFuture<Object>();
        state.track(opening);
        Object response = new Object();
        assertTrue(opening.complete(response));
        state.request();
        assertTrue(state.requested(), "worker must still observe revocation before body copy");
        assertFalse(opening.isCancelled(), "completed acquisition must not be closed from CONTROL");
        assertSame(response, opening.join());
        state.clear(opening);
    }

    @Test
    void concurrentCancelAndAcquisitionPublicationAlwaysRevokes() throws Exception {
        for (int round = 0; round < 100; round++) {
            var state = new RevocationHarness();
            var opening = new CompletableFuture<Object>();
            var start = new CountDownLatch(1);
            var finished = new CountDownLatch(2);
            var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread tracking = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    state.track(opening);
                } catch (Throwable e) { error.compareAndSet(null, e); }
                finally { finished.countDown(); }
            });
            Thread cancelling = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    state.request();
                } catch (Throwable e) { error.compareAndSet(null, e); }
                finally { finished.countDown(); }
            });
            start.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS), "revocation race hung");
            tracking.join();
            cancelling.join();
            assertNull(error.get(), () -> String.valueOf(error.get()));
            assertTrue(state.requested());
            assertTrue(opening.isCancelled(), "race leaked a pending HTTP acquisition");
            state.clear(opening);
        }
    }

    @Test
    void receiptPreservesRepeatedHeadersAndRejectsInvalidBounds() {
        var headers = new java.util.ArrayList<java.util.Map.Entry<String, String>>();
        var mutable = new AbstractMap.SimpleEntry<>("Set-Cookie", "one=1");
        headers.add(mutable);
        headers.add(new AbstractMap.SimpleImmutableEntry<>("Set-Cookie", "two=2"));
        var receipt = new ProxyLoopbackBridge.Receipt(201, headers, 42);
        mutable.setValue("mutated");
        headers.clear();
        assertEquals(2, receipt.headers().size());
        assertEquals("one=1", receipt.headers().get(0).getValue(),
                "receipt must snapshot header entries, not only the outer list");
        assertEquals("two=2", receipt.headers().get(1).getValue());
        assertThrows(UnsupportedOperationException.class,
                () -> receipt.headers().add(java.util.Map.entry("X", "Y")));
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyLoopbackBridge.Receipt(199, List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyLoopbackBridge.Receipt(600, List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyLoopbackBridge.Receipt(200, List.of(), -1));
    }

    @Test
    void bridgeTraversesRealLoopbackAndPreservesStatusRepeatedHeadersAndBodyCount() throws Exception {
        byte[] body = "bridge-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = fixedBodyServer(body);
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2, 1, 2);
        var access = access(runtime, io);
        var sink = new ByteArrayOutputStream();
        var bridge = new java.util.concurrent.atomic.AtomicReference<OresFuture<ProxyLoopbackBridge.Receipt>>();
        var published = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, actorContext) -> {
                bridge.set(ProxyLoopbackBridge.copy(
                        access, target(server), "GET", sink, 1024));
                published.countDown();
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("copy");
            assertTrue(published.await(3, TimeUnit.SECONDS));
            var receipt = bridge.get().get(5, TimeUnit.SECONDS);
            assertEquals(201, receipt.status());
            assertEquals(body.length, receipt.bytesTransferred());
            assertArrayEquals(body, sink.toByteArray());
            assertEquals(2, receipt.headers().stream()
                    .filter(field -> field.getKey().equalsIgnoreCase("Set-Cookie"))
                    .count(), "repeated Set-Cookie values must remain distinct");
            assertEquals("127.0.0.1:" + server.getAddress().getPort() + " proxy.loopback.copy",
                    access.permission().get());
            assertEquals("127.0.0.1:" + server.getAddress().getPort()
                            + " proxy.loopback.copy.physical",
                    access.publicationPermission().get(),
                    "physical publication must re-check the exact endpoint scope");
            assertEquals(0, io.outstandingActorOperations(actor.id()));
            actor.cancel();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
            OresContext.joinActorsAndNativeIo(runtime, io);
        }
    }

    @Test
    void synchronousPublicationDenialSettlesOwnedFutureAndPreservesExactEndpoint() throws Exception {
        var runtime = new ActorRuntime();
        var observed = new java.util.concurrent.atomic.AtomicReference<String>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var attempted = new CountDownLatch(1);

        ProxyLoopbackBridge.RuntimeAccess denied = new ProxyLoopbackBridge.RuntimeAccess() {
            @Override public ActorRuntime actors() { return runtime; }
            @Override public void requireNetPermission(String endpoint, String operation) {
                observed.set(endpoint + " " + operation);
            }
            @Override public <T> OresFuture<T> submitNet(
                    String endpoint, String operation, java.util.concurrent.Callable<T> work) {
                observed.set(endpoint + " " + operation);
                throw new SecurityException("endpoint permission revoked before publication");
            }
        };

        var actor = runtime.<String>spawnShared(() -> (message, actorContext) -> {
            try {
                ProxyLoopbackBridge.copy(
                        denied,
                        URI.create("http://127.0.0.1:12345/"),
                        "GET",
                        OutputStream.nullOutputStream(),
                        1024);
                failure.set(new AssertionError("synchronous publication denial was accepted"));
            } catch (Throwable expected) {
                failure.set(expected);
            } finally {
                attempted.countDown();
            }
        });

        try {
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("copy");
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.get());
            assertEquals(
                    "127.0.0.1:12345 proxy.loopback.copy.physical",
                    observed.get(),
                    "publication denial must receive the exact canonical endpoint");
            actor.cancel();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "settled publication denial must not leave a dangling actor-owned Future");
        } finally {
            runtime.close();
        }
    }

    @Test
    void actorCancellationBeforeAcquisitionRevokesQueuedBridgeBeforeNetworkStart() throws Exception {
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2, 1, 2);
        var access = access(runtime, io);
        var blockerEntered = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var bridge = new java.util.concurrent.atomic.AtomicReference<OresFuture<ProxyLoopbackBridge.Receipt>>();
        var published = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, actorContext) -> {
                if ("block".equals(message)) {
                    io.submit("occupy-native-worker", () -> {
                        blockerEntered.countDown();
                        while (releaseBlocker.getCount() != 0) {
                            try { releaseBlocker.await(20, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException ignored) { }
                        }
                        return null;
                    });
                } else if ("bridge".equals(message)) {
                    bridge.set(ProxyLoopbackBridge.copy(
                            access, URI.create("http://127.0.0.1:9/must-not-open"),
                            "GET", OutputStream.nullOutputStream(), 1024));
                    published.countDown();
                }
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("block");
            assertTrue(blockerEntered.await(3, TimeUnit.SECONDS));
            actor.send("bridge");
            assertTrue(published.await(3, TimeUnit.SECONDS));
            assertEquals(2, io.outstandingActorOperations(actor.id()),
                    "queued bridge must already own a physical operation receipt");
            actor.cancel();
            assertThrows(CancellationException.class,
                    () -> bridge.get().get(3, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone(),
                    "queued physical operation still blocks actor retirement");
            releaseBlocker.countDown();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "revoked queued bridge must retire without opening the network");
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        } finally {
            releaseBlocker.countDown();
            OresContext.joinActorsAndNativeIo(runtime, io);
        }
    }

    @Test
    void actorCancellationDuringHttpAcquisitionCancelsPendingOpening() throws Exception {
        var requestArrived = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requestArrived.countDown();
            try {
                while (releaseResponse.getCount() != 0) {
                    try { releaseResponse.await(20, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { }
                }
                byte[] body = "late".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // Expected when the client cancels the acquisition before headers.
            } finally {
                exchange.close();
            }
        });
        server.start();

        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2, 1, 2);
        var access = access(runtime, io);
        var bridge = new java.util.concurrent.atomic.AtomicReference<OresFuture<ProxyLoopbackBridge.Receipt>>();
        var published = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, actorContext) -> {
                bridge.set(ProxyLoopbackBridge.copy(
                        access, target(server), "GET", OutputStream.nullOutputStream(), 1024));
                published.countDown();
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("copy");
            assertTrue(published.await(3, TimeUnit.SECONDS));
            assertTrue(requestArrived.await(5, TimeUnit.SECONDS),
                    "server must observe request while header acquisition is pending");
            actor.cancel();
            assertThrows(CancellationException.class,
                    () -> bridge.get().get(3, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "cancelling the pending HTTP acquisition must let the physical worker retire");
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            OresContext.joinActorsAndNativeIo(runtime, io);
        }
    }

    @Test
    void bridgePropagatesNativeWorkerRejectionWithoutStartingNetworkWork() throws Exception {
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(1, 1, 1);
        var access = access(runtime, io);
        var blockerEntered = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var bridge = new java.util.concurrent.atomic.AtomicReference<OresFuture<ProxyLoopbackBridge.Receipt>>();
        var published = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, actorContext) -> {
                if ("block".equals(message)) {
                    io.submit("occupy-native-slot", () -> {
                        blockerEntered.countDown();
                        while (releaseBlocker.getCount() != 0) {
                            try { releaseBlocker.await(20, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException ignored) { }
                        }
                        return null;
                    });
                } else if ("bridge".equals(message)) {
                    bridge.set(ProxyLoopbackBridge.copy(
                            access, URI.create("http://127.0.0.1:9/rejected"),
                            "GET", OutputStream.nullOutputStream(), 1024));
                    published.countDown();
                }
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("block");
            assertTrue(blockerEntered.await(3, TimeUnit.SECONDS));
            actor.send("bridge");
            assertTrue(published.await(3, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> bridge.get().get(3, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
            assertEquals("127.0.0.1:9 proxy.loopback.copy", access.permission().get());
            actor.cancel();
            assertFalse(actor.done().isDone(),
                    "the occupying physical worker still belongs to this actor");
            releaseBlocker.countDown();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        } finally {
            releaseBlocker.countDown();
            OresContext.joinActorsAndNativeIo(runtime, io);
        }
    }

    @Test
    void bridgeFailsClosedOnResponseByteBudget() throws Exception {
        HttpServer server = fixedBodyServer("budget-overflow".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2, 1, 2);
        var access = access(runtime, io);
        var bridge = new java.util.concurrent.atomic.AtomicReference<OresFuture<ProxyLoopbackBridge.Receipt>>();
        var published = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, actorContext) -> {
                bridge.set(ProxyLoopbackBridge.copy(
                        access, target(server), "GET", new ByteArrayOutputStream(), 4));
                published.countDown();
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("copy");
            assertTrue(published.await(3, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> bridge.get().get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("byte budget"));
            actor.cancel();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        } finally {
            server.stop(0);
            OresContext.joinActorsAndNativeIo(runtime, io);
        }
    }

    @Test
    void cancellationAfterHeadersCannotForgePhysicalRetirement() throws Exception {
        HttpServer server = fixedBodyServer("body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2, 1, 2);
        var access = access(runtime, io);
        var sink = new BlockingSink();
        var bridge = new java.util.concurrent.atomic.AtomicReference<OresFuture<ProxyLoopbackBridge.Receipt>>();
        var published = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, actorContext) -> {
                bridge.set(ProxyLoopbackBridge.copy(
                        access, target(server), "GET", sink, 1024));
                published.countDown();
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("copy");
            assertTrue(published.await(3, TimeUnit.SECONDS));
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS),
                    "response headers/body must reach the physical copy worker");
            actor.cancel();
            assertThrows(CancellationException.class,
                    () -> bridge.get().get(3, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone(),
                    "cancelled actor cannot reach DONE while body-copy worker is physically blocked");
            assertEquals(1, io.outstandingActorOperations(actor.id()));
            sink.unblock();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        } finally {
            sink.unblock();
            server.stop(0);
            OresContext.joinActorsAndNativeIo(runtime, io);
        }
    }

    @Test
    void contextShutdownQuarantinesUntilBridgeWorkerPhysicallyExits() throws Exception {
        HttpServer server = fixedBodyServer("shutdown".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2, 1, 2);
        var access = access(runtime, io);
        var sink = new BlockingSink();
        var published = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, actorContext) -> {
                ProxyLoopbackBridge.copy(access, target(server), "GET", sink, 1024);
                published.countDown();
            });
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send("copy");
            assertTrue(published.await(3, TimeUnit.SECONDS));
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> OresContext.joinActorsAndNativeIo(runtime, io),
                    "shutdown must fail closed while a physical bridge worker ignores interruption");
            assertFalse(actor.done().isDone(),
                    "failed context retirement must quarantine actor state");
            assertEquals(1, io.outstandingActorOperations(actor.id()));
            sink.unblock();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        } finally {
            sink.unblock();
            server.stop(0);
            try { OresContext.joinActorsAndNativeIo(runtime, io); }
            catch (IllegalStateException ignored) { /* assertion above owns any expected quarantine */ }
        }
    }

    @Test
    void cancelledActorWaitsForNativeWorkerButSiblingCanRetire() throws Exception {
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2, 1, 2);
        var workerEntered = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        var physicallyExited = new CountDownLatch(1);
        var submitted = new java.util.concurrent.atomic.AtomicReference<OresFuture<Integer>>();
        try {
            runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
            io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
            var blocked = runtime.<String>spawnShared(() -> (message, actorContext) -> {
                submitted.set(io.submit("proxy-physical-copy", () -> {
                    workerEntered.countDown();
                    try {
                        while (releaseWorker.getCount() > 0) {
                            try { releaseWorker.await(20, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException ignored) { /* intentionally uncooperative read */ }
                        }
                        return 201;
                    } finally {
                        physicallyExited.countDown();
                    }
                }));
            });
            var sibling = runtime.<String>spawnShared(() -> (message, actorContext) -> { });
            blocked.ready().get(3, TimeUnit.SECONDS);
            sibling.ready().get(3, TimeUnit.SECONDS);
            blocked.send("start-copy");
            assertTrue(workerEntered.await(3, TimeUnit.SECONDS));
            assertEquals(1, io.outstandingActorOperations(blocked.id()));
            blocked.cancel();
            sibling.cancel();
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS),
                    "another actor must be able to retire independently");
            assertFalse(blocked.done().isDone(),
                    "logical cancellation cannot forge the physical worker receipt");
            assertEquals(1, io.outstandingActorOperations(blocked.id()));
            releaseWorker.countDown();
            assertTrue(physicallyExited.await(3, TimeUnit.SECONDS));
            assertTrue(blocked.awaitTermination(3, TimeUnit.SECONDS),
                    "last physical worker receipt must permit DONE");
            assertEquals(0, io.outstandingActorOperations(blocked.id()));
            assertNotNull(submitted.get());
        } finally {
            releaseWorker.countDown();
            OresContext.joinActorsAndNativeIo(runtime, io);
        }
    }

    @Test
    void sourceOrdersAuthorityAndActorOwnershipBeforePhysicalPublication() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/oreslang/runtime/ProxyLoopbackBridge.java"));

        int actorTurn = source.indexOf("ActorRuntime.inActorExecution()");
        int permission = source.indexOf("access.requireNetPermission(");
        int future = source.indexOf("new OresFuture<>(revocation::request)");
        int ownership = source.indexOf("ownCurrentActorFuture(exposed)");
        int submit = source.indexOf("access.submitNet(");
        int joinTransport = source.indexOf("transport.closeAndJoin()");

        assertTrue(actorTurn >= 0);
        assertTrue(permission > actorTurn,
                "scoped NET permission must follow actor/runtime affinity validation");
        assertTrue(future > permission);
        assertTrue(ownership > future);
        assertTrue(submit > ownership,
                "no physical worker may be published before actor Future ownership");
        assertTrue(joinTransport > submit,
                "bridge must join JDK HttpClient termination before physical worker retirement");

        assertFalse(source.contains("ProxyBodyCopy"),
                "stale #1943 body-copy bridge must not return");
        assertFalse(source.contains("newThreadPerTaskExecutor"),
                "bridge must reuse current AsyncNativeIo physical worker accounting");
        assertFalse(source.contains("activeProxyCopies"),
                "stale parallel admission counter must not return");
    }
}
