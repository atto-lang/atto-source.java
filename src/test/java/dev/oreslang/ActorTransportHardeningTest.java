package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresMutex;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorTransportHardeningTest {
    @Test
    void readonlySharedWrapperFreezesBeforePublication() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ArrayList<String> source = new ArrayList<>(List.of("a"));
            ActorRuntime.Shared<Object> shared = runtime.shareReadonly(source);

            source.add("b");

            assertInstanceOf(List.class, shared.value());
            @SuppressWarnings("unchecked")
            List<String> frozen = (List<String>) shared.value();
            assertEquals(List.of("a"), frozen);
            assertThrows(UnsupportedOperationException.class, () -> frozen.add("c"));
        }
    }

    @Test
    void immutableDataSnapshotsRetainNullableElements() {
        java.util.List<Object> sender = new ArrayList<>();
        sender.add(null);
        sender.add(new ArrayList<>(java.util.Arrays.asList(null, "before")));
        @SuppressWarnings("unchecked")
        java.util.List<Object> frozen = (java.util.List<Object>) ActorRuntime.freeze(sender);

        @SuppressWarnings("unchecked")
        java.util.List<Object> nested = (java.util.List<Object>) frozen.get(1);
        sender.set(0, "changed");
        ((java.util.List<Object>) sender.get(1)).set(1, "after");

        assertNull(frozen.get(0));
        assertEquals(java.util.Arrays.asList(null, "before"), nested);
        assertThrows(UnsupportedOperationException.class, () -> frozen.set(0, "modified"));
        assertThrows(UnsupportedOperationException.class, () -> nested.set(1, "modified"));

        @SuppressWarnings("unchecked")
        java.util.List<Object> fromArray =
                (java.util.List<Object>) ActorRuntime.freeze(new Object[]{null, "ok"});
        assertEquals(java.util.Arrays.asList(null, "ok"), fromArray);
        assertThrows(UnsupportedOperationException.class, () -> fromArray.set(0, "changed"));
    }

    @Test
    void sharedMailboxCopiesNullableDataWithoutLeakingSenderAliases() throws Exception {
        java.util.concurrent.atomic.AtomicReference<java.util.List<?>> received =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<java.util.List<?>>spawnShared(() ->
                    (message, context) -> received.set(message));
            java.util.List<Object> source = new ArrayList<>(
                    java.util.Arrays.asList(null, "before"));
            ref.send(source);
            source.set(1, "after");
            ref.drain().get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(java.util.Arrays.asList(null, "before"), received.get());
            assertNotSame(source, received.get());
            @SuppressWarnings("unchecked")
            java.util.List<Object> actual = (java.util.List<Object>) received.get();
            assertThrows(UnsupportedOperationException.class, () -> actual.set(1, "leak"));
            ref.stop();
            ref.getDone().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void actorRefsCannotCrossRuntimeCapabilityBoundaries() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var refA = runtimeA.<Object>spawn(() -> (message, context) -> { });
            var targetB = runtimeB.<Object>spawn(() -> (message, context) -> { });

            IllegalArgumentException wrongDestinationRuntime = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtimeB.send(refA, "wrong-runtime"));
            assertTrue(wrongDestinationRuntime.getMessage().contains("different ActorRuntime"));

            IllegalArgumentException direct = assertThrows(
                    IllegalArgumentException.class,
                    () -> targetB.send(refA));
            assertTrue(direct.getMessage().contains("different ActorRuntime"));

            assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(refA));
            assertThrows(IllegalArgumentException.class, () -> runtimeA.shareReadonly(refA));
        }
    }

    @Test
    void cyclicMessageGraphsAreRejectedBeforeStackOverflow() {
        ArrayList<Object> cycle = new ArrayList<>();
        cycle.add(cycle);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(cycle));

        assertTrue(error.getMessage().contains("cyclic"));
    }

    @Test
    void excessivelyDeepMessageGraphsAreRejected() {
        Object nested = "leaf";
        for (int i = 0; i < 300; i++) nested = List.of(nested);
        Object tooDeep = nested;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooDeep));

        assertTrue(error.getMessage().contains("maximum nesting depth"));
    }

    @Test
    void hostileContainerSizeCannotForceEagerAllocation() {
        List<Object> hostile = new AbstractList<>() {
            @Override
            public Object get(int index) {
                throw new AssertionError("oversized container must be rejected before iteration");
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(hostile));

        assertTrue(error.getMessage().contains("maximum node count"));
    }

    @Test
    void oversizedMessageGraphsAreRejected() {
        List<Integer> tooManyNodes = Collections.nCopies(100_001, 1);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooManyNodes));

        assertTrue(error.getMessage().contains("maximum node count"));
    }

    @Test
    void sharedMutexCannotUseGenericFreezeTransport() {
        var shared = OresMutex.shared(new int[]{0});

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(shared));

        assertTrue(error.getMessage().contains("live actor/shared capabilities"));
    }
    @Test
    void actorMailboxesRejectExecutableAndRawObjectReferencesTransitively() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var privateRef = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            var sharedRef = runtime.<Object>spawnShared(() -> (message, context) -> { });

            Runnable executable = () -> { };
            Object rawObject = new Object();

            assertThrows(IllegalArgumentException.class, () -> privateRef.send(executable));
            assertThrows(IllegalArgumentException.class, () -> sharedRef.send(executable));
            assertThrows(IllegalArgumentException.class, () -> privateRef.send(List.of(executable)));
            assertThrows(IllegalArgumentException.class, () -> sharedRef.send(List.of(executable)));

            assertThrows(IllegalArgumentException.class, () -> privateRef.send(rawObject));
            assertThrows(IllegalArgumentException.class, () -> sharedRef.send(rawObject));
            assertThrows(IllegalArgumentException.class, () -> privateRef.send(List.of(rawObject)));
            assertThrows(IllegalArgumentException.class, () -> sharedRef.send(List.of(rawObject)));
        }
    }

    @Test
    void changingListSizeCannotForceEagerGigabyteAllocation() {
        List<Object> hostile = new AbstractList<>() {
            private int sizeReads;
            @Override public int size() {
                // Graph validation sees a single element; snapshot allocation
                // sees a maliciously inflated capacity.
                return ++sizeReads == 1 ? 1 : Integer.MAX_VALUE;
            }
            @Override public Object get(int index) {
                if (index != 0) throw new IndexOutOfBoundsException(index);
                return "safe";
            }
            @Override public java.util.Iterator<Object> iterator() {
                return List.<Object>of("safe").iterator();
            }
        };
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(hostile));
        assertTrue(rejected.getMessage().contains("maximum node count"),
                rejected.getMessage());
    }

    @Test
    void changingListSizeCannotForcePrivateMailboxAllocation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.atomic.AtomicInteger observed =
                    new java.util.concurrent.atomic.AtomicInteger();
            var receiver = runtime.<List<?>>spawnPrivate(() ->
                    (message, context) -> observed.incrementAndGet());
            List<Object> hostile = new AbstractList<>() {
                private int sizeReads;
                @Override public int size() {
                    // Validate and both preflight estimates see a small list;
                    // isolateCopy() gets an oversized allocation request.
                    return ++sizeReads <= 3 ? 1 : Integer.MAX_VALUE;
                }
                @Override public Object get(int index) {
                    if (index != 0) throw new IndexOutOfBoundsException(index);
                    return "safe";
                }
                @Override public java.util.Iterator<Object> iterator() {
                    return List.<Object>of("safe").iterator();
                }
            };
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> receiver.send(hostile));
            assertTrue(rejected.getMessage().contains("maximum node count"),
                    rejected.getMessage());
            assertEquals(0, observed.get(), "oversized DATA must not be admitted");
            receiver.send(List.of("recovered"));
            receiver.drain().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, observed.get(), "failed copy must release mailbox capacity");
            receiver.stop();
            receiver.getDone().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void changingIteratorCannotExhaustDataFreezePrecheck() {
        java.util.concurrent.atomic.AtomicInteger walks = new java.util.concurrent.atomic.AtomicInteger();
        List<Object> hostile = listWhoseSecondTraversalExpands(walks);
        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(hostile));
        assertTrue(rejection.getMessage().contains("maximum node count"),
                rejection.getMessage());
        assertEquals(2, walks.get(),
                "capability precheck must stop oversized traversal before snapshot allocation");
    }

    @Test
    void changingIteratorCannotExhaustUntrustedDataAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                dev.oreslang.runtime.IsolatePolicy.developer())) {
            java.util.concurrent.atomic.AtomicInteger walks = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
            var untrusted = runtime.<Object>spawnUntrusted(context ->
                    (message, turn) -> received.incrementAndGet());

            IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                    () -> untrusted.send(listWhoseSecondTraversalExpands(walks)));
            assertTrue(rejection.getMessage().contains("maximum node count"),
                    rejection.getMessage());
            assertEquals(2, walks.get(), "untrusted checks must reject the second oversized walk");
            assertEquals(0, received.get(), "rejected DATA must never be queued");

            untrusted.send("recovered");
            untrusted.drain().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, received.get(), "failed admission must return its mailbox slot");
            untrusted.stop();
            untrusted.getDone().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static List<Object> listWhoseSecondTraversalExpands(
            java.util.concurrent.atomic.AtomicInteger walks) {
        return new AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public Object get(int index) {
                if (index != 0) throw new IndexOutOfBoundsException(index);
                return "safe";
            }
            @Override public java.util.Iterator<Object> iterator() {
                // The initial validateMessageGraph() walk is ordinary DATA;
                // only the second capability/ownership walk is oversized.
                int length = walks.incrementAndGet() == 2 ? 100_010 : 1;
                return new java.util.Iterator<>() {
                    private int index;
                    @Override public boolean hasNext() { return index < length; }
                    @Override public Object next() {
                        if (!hasNext()) throw new java.util.NoSuchElementException();
                        index++;
                        return "safe";
                    }
                };
            }
        };
    }

    @Test
    void changingListCannotSmuggleCapabilitiesIntoFrozenData() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Object>spawnShared(() -> (message, context) -> { });
            // Initial graph validation and capability scan see only DATA.
            // The copying traversal sees a forbidden actor capability.
            List<Object> changing = new AbstractList<>() {
                private int reads;
                @Override public int size() { return 1; }
                @Override public Object get(int index) {
                    if (index != 0) throw new IndexOutOfBoundsException(index);
                    return ++reads <= 2 ? "safe" : ref;
                }
            };
            IllegalArgumentException rejection = assertThrows(
                    IllegalArgumentException.class, () -> ActorRuntime.freeze(changing));
            assertTrue(rejection.getMessage().contains("data values only"),
                    rejection.getMessage());
            ref.stop();
        }
    }

    @Test
    void changingSenderGraphCannotSmuggleForeignRefIntoSharedMailbox() throws Exception {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var foreign = right.<Object>spawnShared(() -> (message, context) -> { });
            java.util.concurrent.atomic.AtomicReference<List<?>> received =
                    new java.util.concurrent.atomic.AtomicReference<>();
            var receiver = left.<List<?>>spawnShared(() ->
                    (message, context) -> received.set(message));

            // send() checks a caller-owned container multiple times. Supply
            // ordinary DATA for the five shared pre-copy traversals, then a
            // cross-runtime ActorRef during the immutable envelope snapshot.
            List<Object> changing = new AbstractList<>() {
                private int reads;
                @Override public int size() { return 1; }
                @Override public Object get(int index) {
                    if (index != 0) throw new IndexOutOfBoundsException(index);
                    return ++reads <= 5 ? "safe" : foreign;
                }
            };
            IllegalArgumentException rejection = assertThrows(
                    IllegalArgumentException.class, () -> receiver.send(changing));
            assertTrue(rejection.getMessage().contains("different ActorRuntime"),
                    rejection.getMessage());
            assertNull(received.get(), "rejected DATA must never reach the receiver");
            receiver.send(List.of("accepted"));
            receiver.drain().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of("accepted"), received.get(),
                    "failed admission must release the mailbox slot");
            receiver.stop();
            receiver.getDone().get(5, java.util.concurrent.TimeUnit.SECONDS);
            foreign.stop();
            foreign.getDone().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void changingSenderGraphCannotSmuggleDataOnlyUntrustedCapability() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                dev.oreslang.runtime.IsolatePolicy.developer())) {
            var hidden = runtime.<String>spawnPrivate(() -> (message, context) -> { });
            java.util.concurrent.atomic.AtomicInteger observed =
                    new java.util.concurrent.atomic.AtomicInteger();
            var untrusted = runtime.<Object>spawnUntrusted(context ->
                    (message, turn) -> observed.incrementAndGet());
            List<Object> changing = new AbstractList<>() {
                private int reads;
                @Override public int size() { return 1; }
                @Override public Object get(int index) {
                    if (index != 0) throw new IndexOutOfBoundsException(index);
                    // Six pre-copy traversals see safe DATA; isolation-copy
                    // sees an ActorRef forbidden to the untrusted recipient.
                    return ++reads <= 6 ? "safe" : hidden;
                }
            };
            SecurityException rejection = assertThrows(SecurityException.class,
                    () -> untrusted.send(changing));
            assertTrue(rejection.getMessage().contains("data-only"),
                    rejection.getMessage());
            assertEquals(0, observed.get());
            untrusted.send(List.of("recovered"));
            untrusted.drain().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, observed.get());
            untrusted.stop();
            untrusted.getDone().get(5, java.util.concurrent.TimeUnit.SECONDS);
            hidden.stop();
            hidden.getDone().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void actorStartupFailureDoesNotLeaveUsableRefOrActorQuota() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.CountDownLatch attempted = new java.util.concurrent.CountDownLatch(1);

            var ref = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                attempted.countDown();
                throw new IllegalStateException("startup failed");
            });

            ref.send("trigger");
            assertTrue(attempted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(ref.isAlive());
            assertEquals(0, runtime.actorCount());
            assertTrue(ref.failure().isPresent());
            assertEquals("startup failed", ref.failure().orElseThrow().getMessage());
            assertThrows(ActorRuntime.ActorTerminatedException.class, () -> ref.send("after-failure"));
        }
    }


}
