package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresProxyRuntimeTest {

    private static final class Box {
        private int value;
    }

    @Test
    void sharedActorCanReceiveAndMutateSameRuntimeProxy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Box box = new Box();
            OresProxy<Box> proxy = new OresProxy<>(runtime, box);
            CountDownLatch done = new CountDownLatch(1);

            var ref = runtime.<OresProxy<Box>>spawnShared(() -> (message, context) -> {
                message.write(value -> {
                    value.value++;
                    return null;
                });
                done.countDown();
                context.self().stop();
            });

            ref.send(proxy);
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(1, proxy.read(value -> value.value));
        }
    }

    @Test
    void privateActorCannotReceiveProxyCapability() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresProxy<Box> proxy = new OresProxy<>(runtime, new Box());
            var ref = runtime.<OresProxy<Box>>spawnPrivate(() -> (message, context) -> { });

            SecurityException failure = assertThrows(SecurityException.class, () -> ref.send(proxy));
            assertTrue(failure.getMessage().contains("Proxy"));
        }
    }

    @Test
    void proxyCannotCrossActorRuntimeBoundary() {
        try (ActorRuntime first = new ActorRuntime();
             ActorRuntime second = new ActorRuntime()) {
            OresProxy<Box> proxy = new OresProxy<>(first, new Box());
            var ref = second.<OresProxy<Box>>spawnShared(() -> (message, context) -> { });

            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> ref.send(proxy));
            assertTrue(failure.getMessage().contains("different ActorRuntime"));
        }
    }

    @Test
    void actorCannotUseLegacySyncCellMutableSharing() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    cell.update(value -> value + 1);
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    done.countDown();
                    context.self().stop();
                }
            });

            ref.send("go");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
            assertEquals(1, cell.snapshot());
        }
    }

    @Test
    void actorCannotUseLegacySharedMutexMutableSharing() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresMutex.Shared<Box> mutex = OresMutex.shared(new Box());
            AtomicReference<Throwable> observed = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    mutex.tryLock();
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    done.countDown();
                    context.self().stop();
                }
            });

            ref.send("go");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
        }
    }

    @Test
    void actorContentionFailsWithoutParkingCarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresProxy<Box> proxy = new OresProxy<>(runtime, new Box());
            CountDownLatch hostHasWriteLock = new CountDownLatch(1);
            CountDownLatch releaseHost = new CountDownLatch(1);
            CountDownLatch actorObservedContention = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            Thread holder = Thread.ofPlatform().start(() ->
                    proxy.write(box -> {
                        hostHasWriteLock.countDown();
                        try {
                            assertTrue(releaseHost.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            fail(interrupted);
                        }
                        return null;
                    }));

            assertTrue(hostHasWriteLock.await(2, TimeUnit.SECONDS));

            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    proxy.read(box -> box.value);
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    actorObservedContention.countDown();
                    context.self().stop();
                }
            });

            ref.send("go");
            assertTrue(actorObservedContention.await(1, TimeUnit.SECONDS),
                    "actor carrier must not block behind a contended proxy lock");
            assertInstanceOf(OresProxy.ProxyContendedException.class, observed.get());

            releaseHost.countDown();
            holder.join(2_000L);
            assertFalse(holder.isAlive());
        }
    }

    @Test
    void differentProxyNestingAndReadToWriteUpgradeFailClosed() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresProxy<Box> first = new OresProxy<>(runtime, new Box());
            OresProxy<Box> second = new OresProxy<>(runtime, new Box());

            IllegalStateException nested = assertThrows(
                    IllegalStateException.class,
                    () -> first.read(a -> second.read(b -> b.value)));
            assertTrue(nested.getMessage().contains("different rt proxy"));

            IllegalStateException upgrade = assertThrows(
                    IllegalStateException.class,
                    () -> first.read(a -> first.write(b -> {
                        b.value++;
                        return b.value;
                    })));
            assertTrue(upgrade.getMessage().contains("read-to-write"));
        }
    }
}
