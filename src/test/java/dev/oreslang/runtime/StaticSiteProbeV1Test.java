package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Java runtime static descriptor recording, NOT guest log lowering. */
class StaticSiteProbeV1Test {
    private static SourceIdentityV1.Site site() {
        return new SourceIdentityV1.Site(
                SourceIdentityV1.parse(
                        "rid_d5f8e146ba97430dac6bf17430477e2a",
                        SourceIdentityV1.Kind.ROUTINE),
                SourceIdentityV1.parse(
                        "sid_652e1632e7464fdb936fc40fe24f1c7b",
                        SourceIdentityV1.Kind.SITE),
                7);
    }

    @Test
    void disabledIsNoopAndHostCapacityIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> new StaticSiteProbeV1(0));
        assertThrows(IllegalArgumentException.class, () -> new StaticSiteProbeV1(1025));
        var recorder = new StaticSiteProbeV1(2);
        assertFalse(recorder.emit(null, null, null)); // disabled must not touch args
        assertEquals(0, recorder.emitted());
        recorder.setRecording(true);
        assertTrue(recorder.emit(site(), "", ""));
        assertTrue(recorder.emit(site(), "", ""));
        assertTrue(recorder.emit(site(), "", ""));
        assertEquals(3, recorder.emitted());
        assertEquals(1, recorder.dropped());
        assertNotNull(recorder.poll());
        assertNotNull(recorder.poll());
        assertNull(recorder.poll());
        recorder.setRecording(false);
        assertFalse(recorder.emit(site(), "", ""));
        assertEquals(3, recorder.emitted());
    }

    @Test
    void rejectsInvalidW3cContextAndDoesNotPublishTheAttempt() {
        var recorder = new StaticSiteProbeV1(2);
        recorder.setRecording(true);
        assertThrows(NullPointerException.class,
                () -> recorder.emit(null, "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> recorder.emit(site(), "not-a-trace-id", "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> recorder.emit(site(), "00000000000000000000000000000000",
                        "1111111111111111"));
        assertThrows(IllegalArgumentException.class,
                () -> recorder.emit(site(), "11111111111111111111111111111111", ""));
        assertEquals(0, recorder.emitted());
        assertTrue(recorder.emit(site(), "11111111111111111111111111111111",
                "2222222222222222"));
        var event = recorder.poll();
        assertNotNull(event);
        assertEquals("2222222222222222", event.spanId());
    }

    @Test
    void multipleConcurrentActorCarriersRemainBounded() throws InterruptedException {
        var recorder = new StaticSiteProbeV1(32);
        recorder.setRecording(true);
        var descriptor = site();
        var barrier = new CountDownLatch(1);
        var workers = new ArrayList<Thread>();
        for (int i = 0; i < 8; i++) {
            workers.add(new Thread(() -> {
                try {
                    barrier.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < 1500; n++) recorder.emit(descriptor, "", "");
            }));
        }
        for (var worker : workers) worker.start();
        barrier.countDown();
        for (var worker : workers) worker.join();
        assertEquals(12_000, recorder.emitted());
        assertEquals(11_968, recorder.dropped());
        int seen = 0;
        while (recorder.poll() != null) seen++;
        assertEquals(32, seen);
    }
}
