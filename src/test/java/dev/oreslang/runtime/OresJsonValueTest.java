package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class OresJsonValueTest {
    @Test
    void exactNumbersDoNotRoundThroughDouble() {
        BigInteger exact = new BigInteger("9007199254740993");
        OresJsonValue value = OresJsonValue.of(exact);
        assertInstanceOf(OresJsonValue.NumberValue.class, value);
        assertEquals(
                new BigDecimal(exact),
                ((OresJsonValue.NumberValue) value).value());

        assertThrows(IllegalArgumentException.class,
                () -> OresJsonValue.of(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> OresJsonValue.of(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> OresJsonValue.of(
                        new BigDecimal(BigInteger.TEN.pow(
                                OresJsonValue.MAX_NUMBER_PRECISION))));
    }

    @Test
    void stringsRejectUnpairedSurrogatesButPermitOrdinaryControlValuesInMemory() {
        assertThrows(IllegalArgumentException.class,
                () -> OresJsonValue.of("\uD800"));
        assertThrows(IllegalArgumentException.class,
                () -> OresJsonValue.of("\uDC00"));
        assertDoesNotThrow(() -> OresJsonValue.of("ok\nvalue"));
        assertDoesNotThrow(() -> OresJsonValue.of("🚀"));
    }

    @Test
    void arraysDetachFromMutableInputAndRejectNullElements() {
        ArrayList<OresJsonValue> source = new ArrayList<>();
        source.add(OresJsonValue.of("first"));
        OresJsonValue.ArrayValue array =
                (OresJsonValue.ArrayValue) OresJsonValue.array(source);
        source.add(OresJsonValue.of("late"));
        assertEquals(1, array.values().size());

        assertThrows(NullPointerException.class,
                () -> OresJsonValue.array(java.util.Arrays.asList(
                        OresJsonValue.of(true), null)));
    }

    @Test
    void jsonObjectUsesCanonicalStringKeyedJsonDomainMap() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        assertEquals(OresKeyedMap.KeyDomain.STRING, fields.keyDomain());
        assertEquals(OresKeyedMap.ValueDomain.JSON, fields.valueDomain());

        fields.put("name", OresJsonValue.of("Ada"));
        fields.put("active", OresJsonValue.of(true));
        OresJsonValue object = OresJsonValue.object(fields);
        assertInstanceOf(OresJsonValue.ObjectValue.class, object);
        assertEquals(2, fields.size());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void jsonDomainDoesNotBecomeAnyHostObjectEscapeHatch() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        OresKeyedMap raw = fields;

        assertThrows(IllegalArgumentException.class,
                () -> raw.put("bad", new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> raw.put("bad", "not wrapped"));
        assertTrue(fields.isEmpty());

        OresKeyedMap wrong =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        assertThrows(IllegalArgumentException.class,
                () -> OresJsonValue.object(
                        (OresKeyedMap<String, OresJsonValue>) wrong));
    }

    @Test
    void ownedPutAttemptTransfersCommittedPreviousStateExactlyOnce() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        OresJsonValue first = OresJsonValue.of("first");
        OresJsonValue second = OresJsonValue.of("second");

        OresKeyedMap.OwnedPutAttempt<OresJsonValue> inserted =
                fields.attemptOwnedPut("value", first);
        assertTrue(inserted.committed());
        assertFalse(inserted.takePrevious().present());
        assertThrows(IllegalStateException.class, inserted::takePrevious);
        assertThrows(
                IllegalStateException.class,
                inserted::takeUncommittedValue);
        assertDoesNotThrow(inserted::rethrowAfterRecovery);
        assertSame(first, fields.getRequired("value"));

        OresKeyedMap.OwnedPutAttempt<OresJsonValue> replaced =
                fields.attemptOwnedPut("value", second);
        assertTrue(replaced.committed());
        OresKeyedMap.Lookup<OresJsonValue> previous =
                replaced.takePrevious();
        assertTrue(previous.present());
        assertSame(first, previous.value());
        assertThrows(IllegalStateException.class, replaced::takePrevious);
        assertThrows(
                IllegalStateException.class,
                replaced::takeUncommittedValue);
        assertSame(second, fields.getRequired("value"));
    }

    @Test
    void ownedPutAttemptRecoversExactCycleCandidateBeforeFailurePropagates() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        OresJsonValue kept = OresJsonValue.of("kept");
        fields.put("x", kept);

        OresJsonValue self = OresJsonValue.object(fields);
        OresKeyedMap.OwnedPutAttempt<OresJsonValue> rejected =
                fields.attemptOwnedPut("self", self);

        assertFalse(rejected.committed());
        assertThrows(
                IllegalStateException.class,
                rejected::rethrowAfterRecovery,
                "failure must not discard an unrecovered move-only owner");
        assertSame(self, rejected.takeUncommittedValue(),
                "rollback must return the exact offered move-only value");
        assertThrows(
                IllegalStateException.class,
                rejected::takeUncommittedValue,
                "candidate recovery must be one-shot");
        assertThrows(IllegalStateException.class, rejected::takePrevious);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                rejected::rethrowAfterRecovery);
        assertTrue(failure.getMessage().contains("cyclic"),
                failure.getMessage());
        assertFalse(fields.containsKey("self"));
        assertSame(kept, fields.getRequired("x"),
                "rejected insertion must preserve previous live storage");
    }

    @Test
    void ownedPutAttemptRecoversExactPolicyCandidateBeforeFailurePropagates() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresKeyedMap.stringKeys(
                        OresKeyedMap.ValueDomain.JSON,
                        (key, value) -> {
                            throw new IllegalStateException("policy rejected");
                        });
        OresJsonValue offered = OresJsonValue.of("candidate");

        OresKeyedMap.OwnedPutAttempt<OresJsonValue> rejected =
                fields.attemptOwnedPut("value", offered);

        assertFalse(rejected.committed());
        assertThrows(IllegalStateException.class, rejected::rethrowAfterRecovery);
        assertSame(offered, rejected.takeUncommittedValue());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                rejected::rethrowAfterRecovery);
        assertEquals("policy rejected", failure.getMessage());
        assertTrue(fields.isEmpty());
    }

    @Test
    void ownedPutAttemptRecoversExactCandidateWhenKeyAdmissionRejects() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        OresJsonValue offered = OresJsonValue.of("candidate");

        OresKeyedMap.OwnedPutAttempt<OresJsonValue> rejected =
                fields.attemptOwnedPut("\uD800", offered);

        assertFalse(rejected.committed());
        assertThrows(IllegalStateException.class, rejected::rethrowAfterRecovery);
        assertSame(offered, rejected.takeUncommittedValue());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                rejected::rethrowAfterRecovery);
        assertTrue(failure.getMessage().contains("Unicode"),
                failure.getMessage());
        assertTrue(fields.isEmpty());
    }

    @Test
    void directSelfCycleIsRejectedBeforeMutation() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        OresJsonValue self = OresJsonValue.object(fields);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> fields.put("self", self));
        assertTrue(rejected.getMessage().contains("cyclic"),
                rejected.getMessage());
        assertTrue(fields.isEmpty());
    }

    @Test
    void indirectCycleIsRejectedBeforeMutation() {
        OresKeyedMap<String, OresJsonValue> left =
                OresJsonValue.objectMap();
        OresKeyedMap<String, OresJsonValue> right =
                OresJsonValue.objectMap();
        OresJsonValue leftObject = OresJsonValue.object(left);
        OresJsonValue rightObject = OresJsonValue.object(right);

        left.put("right", rightObject);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> right.put("left", leftObject));
        assertTrue(rejected.getMessage().contains("cyclic"),
                rejected.getMessage());
        assertTrue(right.isEmpty());
    }

    @Test
    void concurrentOppositeEdgesCannotRaceIntoCycle() throws Exception {
        OresKeyedMap<String, OresJsonValue> left =
                OresJsonValue.objectMap();
        OresKeyedMap<String, OresJsonValue> right =
                OresJsonValue.objectMap();
        OresJsonValue leftObject = OresJsonValue.object(left);
        OresJsonValue rightObject = OresJsonValue.object(right);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> {
                start.await();
                try {
                    left.put("right", rightObject);
                    committed.incrementAndGet();
                } catch (IllegalArgumentException cycle) {
                    rejected.incrementAndGet();
                }
                return null;
            });
            var b = workers.submit(() -> {
                start.await();
                try {
                    right.put("left", leftObject);
                    committed.incrementAndGet();
                } catch (IllegalArgumentException cycle) {
                    rejected.incrementAndGet();
                }
                return null;
            });
            start.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        assertEquals(1, committed.get(),
                "exactly one directed edge may commit");
        assertEquals(1, rejected.get(),
                "the opposite edge must observe and reject the cycle");
    }

    @Test
    void putAllSnapshotsForeignInputBeforeTakingInternalJsonLocks() {
        OresKeyedMap<String, OresJsonValue> destination =
                OresJsonValue.objectMap();
        OresKeyedMap<String, OresJsonValue> peer =
                OresJsonValue.objectMap();
        var workers = Executors.newSingleThreadExecutor();
        try {
            java.util.Map<String, OresJsonValue> hostile =
                    new java.util.AbstractMap<>() {
                        private final java.util.Set<
                                java.util.Map.Entry<String, OresJsonValue>> entries =
                                java.util.Set.of(
                                        java.util.Map.entry(
                                                "x", OresJsonValue.of(1L)));

                        @Override
                        public int size() {
                            assertFalse(
                                    Thread.holdsLock(destination),
                                    "foreign size() must run before destination monitor");
                            var probe = workers.submit(
                                    () -> peer.put(
                                            "probe", OresJsonValue.of(true)));
                            try {
                                probe.get(2, TimeUnit.SECONDS);
                            } catch (Exception blocked) {
                                throw new AssertionError(
                                        "foreign size() ran while the global JSON graph lock was held",
                                        blocked);
                            }
                            return entries.size();
                        }

                        @Override
                        public java.util.Set<
                                java.util.Map.Entry<String, OresJsonValue>> entrySet() {
                            assertFalse(
                                    Thread.holdsLock(destination),
                                    "foreign entrySet() must run before destination monitor");
                            return entries;
                        }
                    };

            assertDoesNotThrow(() -> destination.putAll(hostile));
            assertTrue(destination.containsKey("x"));
            assertTrue(peer.containsKey("probe"));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invalidJsonBatchIsAtomic() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        fields.put("kept", OresJsonValue.of(1L));

        LinkedHashMap rawBatch = new LinkedHashMap();
        rawBatch.put("valid", OresJsonValue.of(true));
        rawBatch.put("invalid", new Object());

        assertThrows(IllegalArgumentException.class,
                () -> fields.putAll(rawBatch));
        assertFalse(fields.containsKey("valid"));
        assertFalse(fields.containsKey("invalid"));
        assertTrue(fields.containsKey("kept"));
    }

    @Test
    void graphStabilityGuardExcludesJsonRemovalUntilBoundaryCompletes() throws Exception {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        fields.put("x", OresJsonValue.of(1L));

        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        CountDownLatch removalAttempted = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var holder = workers.submit(() ->
                    OresKeyedMap.withJsonGraphLock(() -> {
                        guardEntered.countDown();
                        try {
                            if (!releaseGuard.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test guard release timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return null;
                    }));

            assertTrue(guardEntered.await(10, TimeUnit.SECONDS));
            var remover = workers.submit(() -> {
                removalAttempted.countDown();
                return fields.take("x");
            });
            assertTrue(removalAttempted.await(10, TimeUnit.SECONDS));

            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> remover.get(100, TimeUnit.MILLISECONDS),
                    "JSON removal must wait for the graph-stable boundary");

            releaseGuard.countDown();
            holder.get(10, TimeUnit.SECONDS);
            OresKeyedMap.Lookup<OresJsonValue> removed =
                    remover.get(10, TimeUnit.SECONDS);
            assertTrue(removed.present());
            assertTrue(fields.isEmpty());
        } finally {
            releaseGuard.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void completeBoundaryValidationRejectsDepthGrownThroughNestedAliasMutation() {
        OresKeyedMap<String, OresJsonValue> rootFields =
                OresJsonValue.objectMap();
        OresJsonValue root = OresJsonValue.object(rootFields);

        OresKeyedMap<String, OresJsonValue> current = rootFields;
        for (int depth = 0; depth <= OresJsonValue.MAX_GRAPH_DEPTH; depth++) {
            OresKeyedMap<String, OresJsonValue> child =
                    OresJsonValue.objectMap();
            OresJsonValue childValue = OresJsonValue.object(child);
            current.put("next", childValue);
            current = child;
        }

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> OresJsonValue.validateCompleteTree(root));
        assertTrue(rejected.getMessage().contains("nesting depth"),
                rejected.getMessage());
    }

    @Test
    void nestedObjectsRemainValidWhenAcyclic() {
        OresKeyedMap<String, OresJsonValue> address =
                OresJsonValue.objectMap();
        address.put("city", OresJsonValue.of("Miami"));

        OresKeyedMap<String, OresJsonValue> person =
                OresJsonValue.objectMap();
        person.put("address", OresJsonValue.object(address));
        person.put("tags", OresJsonValue.array(List.of(
                OresJsonValue.of("a"),
                OresJsonValue.of("b"))));

        assertDoesNotThrow(() -> OresJsonValue.object(person));
        assertEquals(2, person.size());
    }
}
