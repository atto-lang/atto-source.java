package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HttpRequestLocalsTest {
    @Test void differentRequestsNeverShareSlots() {
        var a = new HttpRequestLocals();
        var b = new HttpRequestLocals();
        a.set("trace_id", "request-A");
        b.set("trace_id", "request-B");
        assertEquals("request-A", a.get("trace_id"));
        assertEquals("request-B", b.get("trace_id"));
        assertFalse(b.contains("missing"));
        assertEquals("", b.get("missing"));
        assertTrue(a.contains("trace_id"));
    }

    @Test void overwriteAndCapacityAreBounded() {
        var locals = new HttpRequestLocals();
        for (int i = 0; i < HttpRequestLocals.MAX_ENTRIES; i++) {
            locals.set("name" + i, "a");
        }
        assertEquals(HttpRequestLocals.MAX_ENTRIES, locals.size());
        assertThrows(IllegalStateException.class, () -> locals.set("overflow", "x"));
        locals.set("name0", "updated");
        assertEquals("updated", locals.get("name0"));
    }

    @Test void invalidKeysAndUtf8ByteBudgetsFailWithoutMutatingPreviousValues() {
        var locals = new HttpRequestLocals();
        locals.set("healthy", "ok");
        for (String key : List.of("", "bad space", "1starts_with_digit", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> locals.set(key, "x"));
        }
        assertThrows(NullPointerException.class, () -> locals.set("healthy", null));
        assertThrows(IllegalArgumentException.class,
                () -> locals.set("healthy", "😀".repeat(300)));
        assertEquals("ok", locals.get("healthy"));
        assertEquals(1, locals.size());
        // Eight 1023-byte values plus the initial two bytes fit in 8192;
        // the next seven-byte value crosses the aggregate limit.
        for (int i = 0; i < 8; i++) locals.set("big" + i, "x".repeat(1023));
        assertThrows(IllegalStateException.class,
                () -> locals.set("too_large_total", "1234567"));
        assertEquals(9, locals.size());
    }

    @Test void malformedUtf16AndExactUtf8BoundaryAreRejectedWithoutMutation() {
        var locals = new HttpRequestLocals();
        locals.set("unicode", "prior");
        String[] invalid = {
            String.valueOf((char) 0xD800),
            String.valueOf((char) 0xDC00),
            "a" + (char) 0xD800 + "b",
            String.valueOf((char) 0xD800) + (char) 0xD800
        };
        for (String bad : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> locals.set("unicode", bad));
            assertEquals("prior", locals.get("unicode"));
            assertEquals(1, locals.size());
        }
        // One supplementary scalar is four UTF-8 bytes, even though it is
        // represented as a pair of Java UTF-16 code units.
        String supplementary = new String(Character.toChars(0x1F600));
        locals.set("unicode", supplementary.repeat(256));
        assertEquals(supplementary.repeat(256), locals.get("unicode"));
        assertThrows(IllegalArgumentException.class,
                () -> locals.set("unicode", supplementary.repeat(257)));
        assertEquals(supplementary.repeat(256), locals.get("unicode"));
    }

    @Test void retirementClearsSensitiveDataAndRevokesEveryOperation() {
        var locals = new HttpRequestLocals();
        locals.set("auth", "do-not-retain");
        locals.close();
        assertEquals(0, locals.size());
        assertThrows(IllegalStateException.class, () -> locals.get("auth"));
        assertThrows(IllegalStateException.class, () -> locals.contains("auth"));
        assertThrows(IllegalStateException.class, () -> locals.set("auth", "again"));
        locals.close();
    }

    @Test void requestSnapshotCannotBecomeAnAuthorityTokenOrExtendDeadline() {
        var request = new HttpRequestContext("local-id", 10_000L, 5, "GET", "/hello");
        assertEquals("local-id", request.requestId());
        assertEquals("GET", request.method());
        assertEquals(5_000_000L, request.remainingNanos(10_000L));
        assertEquals(1_000_000L, request.remainingNanos(4_010_000L));
        assertEquals(0L, request.remainingNanos(6_010_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpRequestContext("id", 0, 0, "GET", "/"));
    }

    @Test void independentSlotsRemainIsolatedUnderFifteenCarrierThreads() throws Exception {
        try (var pool = Executors.newFixedThreadPool(15)) {
            List<HttpRequestLocals> requests = new ArrayList<>();
            List<Future<?>> updates = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                HttpRequestLocals context = new HttpRequestLocals();
                requests.add(context);
                int id = i;
                updates.add(pool.submit(() -> {
                    context.set("worker", Integer.toString(id));
                    assertEquals(Integer.toString(id), context.get("worker"));
                }));
            }
            for (Future<?> result : updates) result.get(10, TimeUnit.SECONDS);
            for (int i = 0; i < 15; i++) {
                assertEquals(Integer.toString(i), requests.get(i).get("worker"));
                requests.get(i).close();
            }
        }
    }
}
