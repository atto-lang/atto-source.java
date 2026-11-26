package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real guest A cannot receive data until real guest B signals the external peer. */
final class SingleCarrierNioContinuationGuestTest {
    private record Marker(OresScheduler scheduler, Object domain, long dispatch) {}
    private static final class Capture extends ByteArrayOutputStream {
        private final CompletableFuture<Marker> armed = new CompletableFuture<>();
        private final CompletableFuture<Marker> resumed = new CompletableFuture<>();
        private final CompletableFuture<Marker> signal = new CompletableFuture<>();
        private void observe() {
            String value = toString(StandardCharsets.UTF_8);
            Marker m = new Marker(OresScheduler.current(),
                    OresScheduler.currentTaskDomain(), OresScheduler.currentDispatchId());
            if (value.contains("ARMED|") && !armed.isDone()) armed.complete(m);
            if (value.contains("SIGNAL|") && !signal.isDone()) signal.complete(m);
            if (value.contains("RESUMED|") && !resumed.isDone()) resumed.complete(m);
        }
        @Override public synchronized void write(int b) {
            super.write(b);
            observe();
        }
        @Override public synchronized void write(byte[] b, int off, int len) {
            super.write(b, off, len);
            observe();
        }
    }

    private static Socket connect(int port) throws Exception {
        for (int i = 0; i < 500; i++) {
            try {
                Socket s = new Socket("127.0.0.1", port);
                s.setSoTimeout(5000);
                return s;
            } catch (ConnectException notReady) {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("guest listener did not become ready");
    }

    @Test
    @Timeout(30)
    void externalPeerReleasesPendingReadOnlyAfterSecondGuestRuns() throws Exception {
        assertEquals("1", System.getProperty("ores.runtime.control.parallelism"),
                "this lane requires a single configured CONTROL carrier");
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        String source = """
                pub async routine main(): void {
                  val listener = await network.listen_async("127.0.0.1", %d);
                  task scope {
                    val a = await listener.accept_async();
                    let Future<void> child = task {
                      stdio.stdout.write("ARMED|");
                      val data = await a.read_bytes_async(1);
                      stdio.stdout.write("RESUMED|");
                      await a.write_bytes_async(data);
                      await a.close_async();
                      return;
                    };
                    val b = await listener.accept_async();
                    val command = await b.read_bytes_async(1);
                    stdio.stdout.write("SIGNAL|");
                    await b.write_bytes_async(command);
                    await b.close_async();
                  }
                  await listener.close_async();
                  return;
                }
                """.formatted(port);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        Capture capture = new Capture();
        CompletableFuture<OresVM> vmReady = new CompletableFuture<>();
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Thread guest = new Thread(() -> {
            try (Context ctx = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .arguments(OresLanguage.ID, new String[]{"--ores-capabilities=NETWORK"})
                    .out(capture).build()) {
                ctx.initialize(OresLanguage.ID);
                ctx.enter();
                try {
                    vmReady.complete(OresContext.get(null).vm());
                } finally {
                    ctx.leave();
                }
                ctx.eval(Source.newBuilder(OresLanguage.ID, source, "single-carrier-nio.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
                finished.complete(null);
            } catch (Throwable failure) {
                vmReady.completeExceptionally(failure);
                finished.completeExceptionally(failure);
            }
        }, "single-carrier-guest-driver");
        guest.setDaemon(true);
        guest.start();
        OresVM vm = vmReady.get(5, TimeUnit.SECONDS);
        OresScheduler scheduler = vm.rootScheduler();
        assertEquals(1, vm.controlCarrierCount());
        assertEquals(1, scheduler.parallelism());
        try (Socket a = connect(port)) {
            Marker armed = capture.armed.get(5, TimeUnit.SECONDS);
            assertSame(scheduler, armed.scheduler());
            assertNotNull(armed.domain());
            assertTrue(armed.dispatch() > 0);
            // The marker precedes the read with no intervening suspension.
            // A host barrier on the same scheduler cannot execute if A pins
            // its only carrier. The exact A task must still be parked after it.
            assertTrue(scheduler.startSync(() ->
                    scheduler.hasReleasedPendingAwaitForTest(armed.domain()))
                    .get(5, TimeUnit.SECONDS), "A must have released a pending read");
            assertFalse(capture.resumed.isDone());
            try (Socket b = connect(port)) {
                b.getOutputStream().write(7);
                b.getOutputStream().flush();
                assertEquals(7, b.getInputStream().read());
                Marker signal = capture.signal.get(5, TimeUnit.SECONDS);
                assertSame(scheduler, signal.scheduler());
                assertNotSame(armed.domain(), signal.domain());
                assertTrue(signal.dispatch() > 0);
                assertFalse(capture.resumed.isDone(), "A resumed before external release");
                a.getOutputStream().write(42);
                a.getOutputStream().flush();
                assertEquals(42, a.getInputStream().read());
                Marker resumed = capture.resumed.get(5, TimeUnit.SECONDS);
                assertSame(scheduler, resumed.scheduler());
                assertSame(armed.domain(), resumed.domain());
                assertNotEquals(armed.dispatch(), resumed.dispatch());
                assertEquals(-1, a.getInputStream().read());
                assertEquals(-1, b.getInputStream().read());
            }
        }
        finished.get(5, TimeUnit.SECONDS);
        guest.join(5000);
        assertFalse(guest.isAlive(), "guest and VM must retire");
        assertEquals("ARMED|SIGNAL|RESUMED|", capture.toString(StandardCharsets.UTF_8));
    }
}
