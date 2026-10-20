package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class UnaryTcpTlsRuntimeAuthorityTest {
    private static UnaryTcpSessionAdapter.Limits limits() {
        return new UnaryTcpSessionAdapter.Limits(
                1, 1, 1024, 1, 1024, 1024, 1000, 1000, 1000);
    }

    private static UnaryTcpSessionAdapter.Dispatcher dispatcher() {
        return request -> new UnaryTcpSessionAdapter.DispatchHandle() {
            @Override public void whenComplete(
                    java.util.function.BiConsumer<
                            ? super UnaryTcpSessionAdapter.Reply,
                            ? super Throwable> completion) {
                // Listener-authority tests never admit a connection.
            }

            @Override public boolean cancel() {
                return true;
            }
        };
    }

    private static UnaryTcpMutualTlsAuthenticator.CertificateAuthorizer authorizer() {
        return certificate -> "test:authorized";
    }

    @Test
    void deniedScopedPortCannotCreateEndpoint() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:8443");
        AtomicInteger endpointCreations = new AtomicInteger();

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> UnaryTcpTlsRuntime.bind(
                        permissions::requireNetworkBind,
                        () -> {
                            endpointCreations.incrementAndGet();
                            return null;
                        },
                        "127.0.0.1",
                        8444,
                        8,
                        limits(),
                        authorizer(),
                        dispatcher()));

        assertTrue(denied.getMessage().contains("net bind"));
        assertEquals(0, endpointCreations.get(),
                "permission denial must happen before SSL socket creation");
    }

    @Test
    void exactPortZeroGrantCannotCreateEphemeralEndpoint() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:0");
        AtomicInteger endpointCreations = new AtomicInteger();

        assertThrows(
                SecurityException.class,
                () -> UnaryTcpTlsRuntime.bind(
                        permissions::requireNetworkBind,
                        () -> {
                            endpointCreations.incrementAndGet();
                            return null;
                        },
                        "127.0.0.1",
                        0,
                        8,
                        limits(),
                        authorizer(),
                        dispatcher()));

        assertEquals(0, endpointCreations.get(),
                "host:0 is not authority for an OS-selected listener port");
    }

    @Test
    void hostWideAuthorityCanCreateOneEphemeralTlsListener() throws Exception {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1");
        AtomicInteger endpointCreations = new AtomicInteger();
        SSLContext tls = SSLContext.getDefault();

        try (UnaryTcpTlsListener listener = UnaryTcpTlsRuntime.bind(
                permissions::requireNetworkBind,
                () -> {
                    endpointCreations.incrementAndGet();
                    return (SSLServerSocket)
                            tls.getServerSocketFactory().createServerSocket();
                },
                "127.0.0.1",
                0,
                8,
                limits(),
                authorizer(),
                dispatcher())) {
            assertNotNull(listener);
            assertEquals(1, endpointCreations.get());
            assertEquals(1, listener.availableConnectionSlots());
            assertEquals(0, listener.activeConnections());
        }
    }

    @Test
    void bindFailureClosesFreshTlsEndpoint() throws Exception {
        SSLContext tls = SSLContext.getDefault();
        AtomicReference<SSLServerSocket> created = new AtomicReference<>();

        try (ServerSocket blocker = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            int occupiedPort = blocker.getLocalPort();
            RuntimePermissions permissions = RuntimePermissions.denyAll()
                    .withAllowed(
                            RuntimePermissions.Permission.NET,
                            "127.0.0.1:" + occupiedPort);

            assertThrows(
                    java.io.IOException.class,
                    () -> UnaryTcpTlsRuntime.bind(
                            permissions::requireNetworkBind,
                            () -> {
                                SSLServerSocket endpoint = (SSLServerSocket)
                                        tls.getServerSocketFactory()
                                                .createServerSocket();
                                created.set(endpoint);
                                return endpoint;
                            },
                            "127.0.0.1",
                            occupiedPort,
                            8,
                            limits(),
                            authorizer(),
                            dispatcher()));

            assertNotNull(created.get());
            assertTrue(created.get().isClosed(),
                    "failed bind must close the freshly created TLS endpoint");
        }
    }

    @Test
    void invalidEndpointTouchesNeitherAuthorityNorFactory() {
        AtomicInteger authorityChecks = new AtomicInteger();
        AtomicInteger endpointCreations = new AtomicInteger();

        assertThrows(
                IllegalArgumentException.class,
                () -> UnaryTcpTlsRuntime.bind(
                        (host, port, api) -> authorityChecks.incrementAndGet(),
                        () -> {
                            endpointCreations.incrementAndGet();
                            return null;
                        },
                        " ",
                        0,
                        8,
                        limits(),
                        authorizer(),
                        dispatcher()));

        assertEquals(0, authorityChecks.get());
        assertEquals(0, endpointCreations.get());
    }

    @Test
    void actorTurnCannotReachTlsBindDependenciesOrSocketCreation() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    UnaryTcpTlsRuntime.bind(
                            (OresContext) null,
                            (SSLContext) null,
                            "127.0.0.1", 0, 1,
                            null, null, null);
                    failure.set(new AssertionError(
                            "actor turn reached unary TLS bind path"));
                } catch (Throwable terminal) {
                    failure.set(terminal);
                } finally {
                    observed.countDown();
                }
            });

            actor.send("bind");
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            SecurityException denied = assertInstanceOf(
                    SecurityException.class, failure.get());
            assertTrue(denied.getMessage().contains("supervisor"));
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }
}
