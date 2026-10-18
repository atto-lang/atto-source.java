package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HttpResponseDraftTest {
    @Test void defaultsAndContentTypeFollowFetchResponseStyle() throws Exception {
        HttpResponseDraft empty = HttpResponseDraft.empty();
        assertEquals(200, empty.status());
        assertTrue(empty.ok());
        assertFalse(empty.hasBody());
        HttpResponseDraft body = HttpResponseDraft.text("hello from Oreslang");
        assertEquals(200, body.status());
        assertEquals("Content-Type", body.headers().getFirst().getKey());
        assertEquals("text/plain; charset=utf-8", body.headers().getFirst().getValue());
        assertEquals(19, body.bufferedLength());
        body.claim();
        assertEquals("hello from Oreslang", new String(body.openBody().readAllBytes(), StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, body::claim);
    }

    @Test void userHeadersPreserveMultipleSetCookieValues() {
        var fields = List.of(Map.entry("Set-Cookie", "session=a; HttpOnly"),
                Map.entry("set-cookie", "theme=light"),
                Map.entry("Content-Type", "text/html"));
        HttpResponseDraft response = HttpResponseDraft.text("ok", 201, fields);
        assertEquals(3, response.headers().size());
        assertEquals(fields, response.headers());
        assertTrue(response.ok());
    }

    @Test void forbiddenBodyEvenWhenEmpty() {
        for (int code : List.of(204, 205, 304)) {
            assertThrows(IllegalArgumentException.class, () -> HttpResponseDraft.text("", code, List.of()));
            assertThrows(IllegalArgumentException.class, () -> HttpResponseDraft.binary(new byte[0], code, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> HttpResponseDraft.streaming(java.io.InputStream::nullInputStream, code, List.of()));
            assertEquals(code, HttpResponseDraft.empty(code, List.of()).status());
        }
        for (int code : List.of(0, 101, 199, 600)) {
            assertThrows(IllegalArgumentException.class, () -> HttpResponseDraft.empty(code, List.of()));
        }
    }

    @Test void transportHeadersAndInjectionRejectedBeforeHandoff() {
        for (String name : List.of("Content-Length", "Transfer-Encoding", "CONNECTION",
                "Trailer", "TE", "Host", ":status", "Bad Name")) {
            assertThrows(IllegalArgumentException.class,
                    () -> HttpResponseDraft.empty(200, List.of(Map.entry(name, "value"))), name);
        }
        for (String value : List.of("a\r\nb", "a\0b", "a\tb", "a\u0100b")) {
            assertThrows(IllegalArgumentException.class,
                    () -> HttpResponseDraft.empty(200, List.of(Map.entry("X-Test", value))));
        }
        var large = new ArrayList<Map.Entry<String, String>>();
        for (int i = 0; i < 101; i++) large.add(Map.entry("X-Test", "v"));
        assertThrows(IllegalArgumentException.class, () -> HttpResponseDraft.empty(200, large));
        assertThrows(IllegalArgumentException.class,
                () -> HttpResponseDraft.empty(200, List.of(Map.entry("X-Test", "x".repeat(32768)))));
        assertThrows(UnsupportedOperationException.class, HttpResponseDraft::withTrailers);
    }

    @Test void buffersAreDefensivelyCopiedAndHeadersFrozen() throws Exception {
        byte[] input = {1, 2, 3};
        var mutable = new ArrayList<Map.Entry<String, String>>();
        mutable.add(Map.entry("X-Ready", "yes"));
        HttpResponseDraft response = HttpResponseDraft.binary(input, 200, mutable);
        input[0] = 99;
        mutable.clear();
        assertEquals(1, response.headers().size());
        assertThrows(UnsupportedOperationException.class,
                () -> response.headers().add(Map.entry("X-Test", "no")));
        response.claim();
        assertArrayEquals(new byte[]{1, 2, 3}, response.openBody().readAllBytes());
    }

    @Test void actorMailboxCannotFreezeAnHttpResponseDraft() {
        HttpResponseDraft draft = HttpResponseDraft.text("hello");
        assertThrows(SecurityException.class, () -> ActorRuntime.freeze(draft));
        assertThrows(SecurityException.class, () -> ActorRuntime.freeze(List.of(List.of(draft))));
    }

    @Test void abortAndWriterCloseStreamExactlyOnceUnderContention() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            AtomicInteger underlyingCloses = new AtomicInteger();
            HttpResponseDraft response = HttpResponseDraft.streaming(() -> new ByteArrayInputStream(
                    "owned".getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws java.io.IOException {
                    underlyingCloses.incrementAndGet();
                    super.close();
                }
            }, 200, List.of());
            response.claim();
            var input = response.openBody();
            var ready = new CountDownLatch(8);
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(8)) {
                var jobs = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(3, TimeUnit.SECONDS))
                        throw new IllegalStateException("stream close start barrier timed out");
                    input.close();
                    return null;
                }));
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                start.countDown();
                for (var job : jobs) job.get(3, TimeUnit.SECONDS);
            }
            assertEquals(1, underlyingCloses.get(), "close iteration " + attempt);
            assertThrows(IllegalStateException.class, response::openBody);
        }
    }

    @Test @org.junit.jupiter.api.Timeout(20)
    void blockingHostSuppliersRespectBoundedOpenerLifetime() throws Exception {
        HttpResponseBodyOpenGateCheck.main(new String[0]);
    }

    @Test void streamProducerIsLazyAndSingleUse() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        HttpResponseDraft response = HttpResponseDraft.streaming(() -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream("streamed".getBytes(StandardCharsets.UTF_8));
        }, 200, List.of(Map.entry("Content-Type", "text/plain")));
        assertEquals(0, opened.get());
        assertThrows(IllegalStateException.class, response::openBody);
        response.claim();
        assertEquals(0, opened.get());
        try (var stream = response.openBody()) {
            assertEquals("streamed", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(1, opened.get());
        assertThrows(IllegalStateException.class, response::claim);
        assertThrows(IllegalStateException.class, response::openBody);
    }
}
