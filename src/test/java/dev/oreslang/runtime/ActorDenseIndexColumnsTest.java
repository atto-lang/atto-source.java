package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorDenseIndexColumnsTest {
    @Test
    void allLayoutsPreserveCompactIndexWords() throws Exception {
        for (ActorShardColumns.Layout layout : ActorShardColumns.Layout.values()) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<int[]> encoded = new AtomicReference<>();

                ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                        factory -> {
                            ActorDenseIndexColumns shard = new ActorDenseIndexColumns(
                                    layout, 17, 2,
                                    layout == ActorShardColumns.Layout.AOSOA ? 8 : 1);
                            return (message, turn) -> {
                                try {
                                    shard.writeIndex(0, 0, 0);
                                    shard.writeIndex(1, 0, 41);
                                    shard.writeIndex(16, 0,
                                            ManagedReferencePolicy.MAX_DENSE_INDEX32);
                                    shard.writeNull(2, 0);
                                    shard.writeEncodedWord(3, 0, 0x8000_0001);

                                    assertEquals(1, shard.readEncodedWord(0, 0));
                                    assertEquals(0x8000_0001, shard.readEncodedWord(3, 0));
                                    assertEquals(0x8000_0000L,
                                            shard.readIndex(3, 0).orElseThrow());
                                    assertEquals(0, shard.readIndex(0, 0).orElseThrow());
                                    assertEquals(41, shard.readIndex(1, 0).orElseThrow());
                                    assertEquals(ManagedReferencePolicy.MAX_DENSE_INDEX32,
                                            shard.readIndex(16, 0).orElseThrow());
                                    assertEquals(OptionalLong.empty(), shard.readIndex(2, 0));

                                    int[] copy = new int[17];
                                    shard.copyEncodedColumnInto(0, copy, 0);
                                    encoded.set(copy);
                                } catch (Throwable problem) {
                                    failure.set(problem);
                                }
                            };
                        });
                actor.send(1);
                actor.drain().get(5, TimeUnit.SECONDS);
                assertNull(failure.get());
                assertNotNull(encoded.get());
                assertEquals(1, encoded.get()[0]);
                assertEquals(42, encoded.get()[1]);
                assertEquals(0, encoded.get()[2]);
                assertEquals(-1, encoded.get()[16]);
                actor.gracefulStop().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void foreignCloseCannotRevokeSharedOrPrivateIndexBacking() throws Exception {
        for (boolean privateActor : new boolean[] {false, true}) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                AtomicReference<ActorDenseIndexColumns> confined = new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                ActorRuntime.ActorRef<Integer> actor = privateActor
                        ? runtime.spawnPrivate(context -> {
                            ActorDenseIndexColumns shard = new ActorDenseIndexColumns(
                                    ActorShardColumns.Layout.SOA, 8, 1, 1);
                            confined.set(shard);
                            return (message, turn) -> {
                                try {
                                    if (message == 1) shard.writeIndex(0, 0, 17);
                                    else assertEquals(17, shard.readIndex(0, 0).orElseThrow());
                                } catch (Throwable problem) {
                                    failure.compareAndSet(null, problem);
                                }
                            };
                        })
                        : runtime.spawnSharedTrusted(factory -> {
                            ActorDenseIndexColumns shard = new ActorDenseIndexColumns(
                                    ActorShardColumns.Layout.SOA, 8, 1, 1);
                            confined.set(shard);
                            return (message, turn) -> {
                                try {
                                    if (message == 1) shard.writeIndex(0, 0, 17);
                                    else assertEquals(17, shard.readIndex(0, 0).orElseThrow());
                                } catch (Throwable problem) {
                                    failure.compareAndSet(null, problem);
                                }
                            };
                        });

                actor.send(1);
                actor.drain().get(5, TimeUnit.SECONDS);
                assertNull(failure.get());
                assertThrows(SecurityException.class, () -> confined.get().close());

                actor.send(2);
                actor.drain().get(5, TimeUnit.SECONDS);
                assertNull(failure.get(),
                        "foreign close must not revoke or mutate live shard backing");
                actor.gracefulStop().get(5, TimeUnit.SECONDS);
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void sharedActorFinalizationDetachesIndexBacking() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorDenseIndexColumns> confined = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> {
                        ActorDenseIndexColumns shard = new ActorDenseIndexColumns(
                                ActorShardColumns.Layout.SOA, 8, 1, 1);
                        confined.set(shard);
                        return (message, turn) -> shard.writeIndex(0, 0, 7);
                    });
            actor.send(1);
            actor.drain().get(5, TimeUnit.SECONDS);
            assertThrows(SecurityException.class,
                    () -> confined.get().readEncodedWord(0, 0));
            actor.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));

            java.lang.reflect.Field storageField =
                    ActorDenseIndexColumns.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            assertNull(storageField.get(confined.get()));
            assertThrows(SecurityException.class,
                    () -> confined.get().readEncodedWord(0, 0));
        }
    }

    @Test
    void privateActorStoresIndicesInOwnerCheckedDirectMemory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnPrivate(
                    context -> {
                        ActorDenseIndexColumns shard = new ActorDenseIndexColumns(
                                ActorShardColumns.Layout.AOSOA, 65, 2, 8);
                        if (!shard.isPrivateBacked()) {
                            throw new AssertionError("private index shard requires direct backing");
                        }
                        return (message, turn) -> {
                            shard.writeIndex(64, 0, 123456);
                            if (shard.readIndex(64, 0).orElseThrow() != 123456) {
                                throw new AssertionError("private compact index round-trip failed");
                            }
                        };
                    });
            actor.send(1);
            actor.drain().get(5, TimeUnit.SECONDS);
            assertTrue(runtime.privateMemoryBytes() >= 72L * 2L * Integer.BYTES,
                    "AoSoA index storage must charge four bytes per physical cell");
            actor.gracefulStop().get(5, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.privateMemoryBytes());
        }
    }

    @Test
    void hostCannotAllocateOrReadActorIndexStorage() {
        assertThrows(SecurityException.class,
                () -> new ActorDenseIndexColumns(
                        ActorShardColumns.Layout.SOA, 8, 2, 1));
    }
}
