package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimeGarbageCollectorTest {
    @Test
    void processCollectionRequestsJvmGcButDoesNotCleanLiveOwners() {
        AtomicInteger gcRequests = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        Object owner = new Object();

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(gcRequests::incrementAndGet, Duration.ofHours(1))) {
            gc.track(owner, cleanups::incrementAndGet);
            RuntimeGarbageCollector.CollectionReport report = gc.collectProcess();

            assertEquals("process", report.scope());
            assertEquals(1, report.trackedBefore());
            assertEquals(0, report.cleaned());
            assertEquals(1, report.trackedAfter());
            assertTrue(report.jvmGcRequested());
            assertEquals(1, gcRequests.get());
            assertEquals(0, cleanups.get());
            assertNotNull(owner);
        }

        assertEquals(1, cleanups.get(), "context shutdown must close tracked host resources");
    }

    @Test
    void actorCollectionIsDomainLocalAndNeverRequestsJvmGc() throws Exception {
        AtomicInteger gcRequests = new AtomicInteger();
        AtomicReference<RuntimeGarbageCollector.CollectionReport> report = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(gcRequests::incrementAndGet, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                report.set(gc.collectCurrentActor());
                done.countDown();
            });

            ref.send("gc");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertNotNull(report.get());
            assertEquals("actor", report.get().scope());
            assertFalse(report.get().jvmGcRequested());
            assertEquals(0, gcRequests.get());
        }
    }

    @Test
    void actorCollectionOutsideActorIsRejected() {
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            IllegalStateException error = assertThrows(IllegalStateException.class, gc::collectCurrentActor);
            assertTrue(error.getMessage().contains("actor.gc() requires execution inside an actor"));
        }
    }
}
