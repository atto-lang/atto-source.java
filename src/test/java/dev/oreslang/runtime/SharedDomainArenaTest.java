package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(30)
final class SharedDomainArenaTest {
    @Test
    void fifoQueuedReaderResumesOnlyOnItsOwnLaterActorTurn() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var domainRef = new AtomicReference<SharedDomainArena>();
        var writerGrant = new AtomicReference<SharedDomainArena.Grant>();
        var readerGrant = new AtomicReference<SharedDomainArena.Grant>();
        var handle = new AtomicReference<SharedDomainArena.Handle>();
        var readerAdmission = new AtomicReference<OresFuture<SharedDomainArena.Admission>>();
        var readerQueued = new AtomicBoolean();
        var writerDone = new CountDownLatch(1);
        var readerRequestDone = new CountDownLatch(1);
        var readerResumeDone = new CountDownLatch(1);
        var observed = new AtomicLong();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var writer = runtime.<String>spawnShared(() -> (message, turn) -> {
                var domain = domainRef.get();
                if ("write".equals(message)) {
                    var admission = domain.write(writerGrant.get())
                            .get(0, TimeUnit.NANOSECONDS);
                    try (var access = domain.enter(writerGrant.get(), admission)) {
                        var h = access.allocate(0x1122334455667788L);
                        handle.set(h);
                        while (!readerQueued.get()) Thread.onSpinWait();
                    }
                    writerDone.countDown();
                    return;
                }
                throw new IllegalStateException("unexpected writer message");
            });
            var reader = runtime.<String>spawnShared(() -> (message, turn) -> {
                var domain = domainRef.get();
                if ("request".equals(message)) {
                    var pending = domain.read(readerGrant.get());
                    readerAdmission.set(pending);
                    assertThrows(TimeoutException.class,
                            () -> pending.get(0, TimeUnit.NANOSECONDS),
                            "reader must queue behind active writer");
                    readerQueued.set(true);
                    readerRequestDone.countDown();
                    return;
                }
                if ("resume".equals(message)) {
                    var admission = readerAdmission.get()
                            .get(0, TimeUnit.NANOSECONDS);
                    try (var access = domain.enter(readerGrant.get(), admission)) {
                        observed.set(access.read(handle.get()));
                    }
                    readerResumeDone.countDown();
                    return;
                }
                throw new IllegalStateException("unexpected reader message");
            });

            writer.ready().get(5, TimeUnit.SECONDS);
            reader.ready().get(5, TimeUnit.SECONDS);

            SharedDomainArena domain = runtime.newSharedDomainArena(8);
            var control = domain.takeControl();
            domainRef.set(domain);
            writerGrant.set(domain.grant(control, writer, true, true));
            readerGrant.set(domain.grant(control, reader, true, false));
            assertEquals(64L, runtime.sharedMemoryBytes());

            writer.send("write");
            while (handle.get() == null) Thread.onSpinWait();
            reader.send("request");
            assertTrue(readerRequestDone.await(5, TimeUnit.SECONDS));
            assertTrue(writerDone.await(5, TimeUnit.SECONDS));

            reader.send("resume");
            assertTrue(readerResumeDone.await(5, TimeUnit.SECONDS));
            assertEquals(0x1122334455667788L, observed.get());

            assertTrue(domain.tryRevoke(control, readerGrant.get()));
            assertTrue(domain.tryRetire(control));
            assertTrue(domain.physicallyClosed());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void accessCannotEscapeEvenToSameActorNextTurn() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var domainRef = new AtomicReference<SharedDomainArena>();
        var grantRef = new AtomicReference<SharedDomainArena.Grant>();
        var leaked = new AtomicReference<SharedDomainArena.Access>();
        var handle = new AtomicReference<SharedDomainArena.Handle>();
        var first = new CountDownLatch(1);
        var second = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var domain = domainRef.get();
                if ("leak".equals(message)) {
                    var admission = domain.write(grantRef.get())
                            .get(0, TimeUnit.NANOSECONDS);
                    var access = domain.enter(grantRef.get(), admission);
                    leaked.set(access);
                    handle.set(access.allocate(7L));
                    first.countDown();
                    return; // intentionally leak across mailbox boundary
                }
                if ("replay".equals(message)) {
                    assertThrows(SecurityException.class,
                            () -> leaked.get().read(handle.get()),
                            "same actor incarnation must not reuse prior-turn byte authority");
                    leaked.get().close();
                    second.countDown();
                    return;
                }
                throw new IllegalStateException("unexpected actor message");
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            var domain = runtime.newSharedDomainArena(2);
            var control = domain.takeControl();
            domainRef.set(domain);
            grantRef.set(domain.grant(control, actor, true, true));

            actor.send("leak");
            assertTrue(first.await(5, TimeUnit.SECONDS));
            actor.send("replay");
            assertTrue(second.await(5, TimeUnit.SECONDS));

            assertTrue(domain.tryRetire(control));
            assertTrue(domain.physicallyClosed());
        }
    }

    @Test
    void lifetimePinDelaysPhysicalRetirementAndHasNoByteAuthority() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var domainRef = new AtomicReference<SharedDomainArena>();
        var grantRef = new AtomicReference<SharedDomainArena.Grant>();
        var pinRef = new AtomicReference<SharedDomainArena.LifetimePin>();
        var pinned = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                pinRef.set(domainRef.get().pin(grantRef.get()));
                pinned.countDown();
            });
            actor.ready().get(5, TimeUnit.SECONDS);

            var domain = runtime.newSharedDomainArena(4);
            var control = domain.takeControl();
            domainRef.set(domain);
            grantRef.set(domain.grant(control, actor, true, false));
            assertEquals(32L, runtime.sharedMemoryBytes());

            actor.send("pin");
            assertTrue(pinned.await(5, TimeUnit.SECONDS));

            assertFalse(domain.tryRetire(control),
                    "lifetime-only pin must delay physical FFM release");
            assertTrue(domain.closed());
            assertFalse(domain.physicallyClosed());

            for (var method : SharedDomainArena.LifetimePin.class.getDeclaredMethods()) {
                assertFalse(method.getName().startsWith("read")
                                || method.getName().startsWith("write")
                                || method.getName().startsWith("allocate")
                                || method.getName().startsWith("drop"),
                        () -> "lifetime pin leaked byte authority: " + method);
            }

            var failure = new AtomicReference<Throwable>();
            Thread completion = new Thread(() -> {
                try {
                    pinRef.get().close();
                    pinRef.get().close();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "shared-domain-completion");
            completion.start();
            completion.join(5000);
            assertFalse(completion.isAlive());
            assertNull(failure.get(), () -> "pin release failed: " + failure.get());
            assertTrue(domain.physicallyClosed());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void publicSurfaceDoesNotExposeFfmSegmentsOrRawDomainStorage() {
        for (var method : SharedDomainArena.class.getMethods()) {
            assertNotEquals("domainId", method.getName(),
                    () -> "public API exposes numeric shared-domain coordinate: " + method);
            assertFalse(method.getReturnType().getName().contains("MemorySegment"),
                    () -> "public method exposes FFM segment: " + method);
            for (var type : method.getParameterTypes()) {
                assertFalse(type.getName().contains("MemorySegment"),
                        () -> "public method accepts FFM segment: " + method);
            }
        }
        for (var method : SharedDomainArena.Grant.class.getMethods()) {
            assertNotEquals("domainId", method.getName(),
                    () -> "grant exposes numeric shared-domain coordinate: " + method);
        }
        for (Field field : SharedDomainArena.Handle.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("MemorySegment"),
                    () -> "handle exposes FFM segment field: " + field);
        }
    }
}
