package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresKeyedMapCommitAwareTest {
    @Test
    void successfulAttemptTransfersCandidateAndReportsPreviousState() {
        OresKeyedMap<String, String> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.STRING);

        String first = new String("first");
        OresKeyedMap.PutAttempt<String> inserted =
                map.tryPutOwned("key", first);

        assertTrue(inserted.committed());
        assertFalse(inserted.takePrevious().present());
        assertSame(first, map.getRequired("key"));
        assertThrows(IllegalStateException.class, inserted::takeUncommittedValue);
        assertDoesNotThrow(inserted::rethrowAfterRecovery);

        String second = new String("second");
        OresKeyedMap.PutAttempt<String> replaced =
                map.tryPutOwned("key", second);

        assertTrue(replaced.committed());
        OresKeyedMap.Lookup<String> previous = replaced.takePrevious();
        assertTrue(previous.present());
        assertSame(first, previous.value());
        assertThrows(IllegalStateException.class, replaced::takePrevious);
        assertSame(second, map.getRequired("key"));
    }

    @Test
    void rejectedDomainAdmissionReturnsTheExactUncommittedCandidate() {
        OresKeyedMap<String, Object> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        Object candidate = new Object();

        OresKeyedMap.PutAttempt<Object> rejected =
                map.tryPutOwned("key", candidate);

        assertFalse(rejected.committed());
        assertThrows(
                IllegalStateException.class,
                rejected::rethrowAfterRecovery,
                "failure propagation must not discard an unrecovered move-only candidate");
        assertSame(candidate, rejected.takeUncommittedValue());
        assertThrows(IllegalStateException.class, rejected::takeUncommittedValue);
        assertTrue(map.isEmpty());
        assertThrows(IllegalStateException.class, rejected::takePrevious);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                rejected::rethrowAfterRecovery);
        assertTrue(failure.getMessage().contains("value domain"),
                failure.getMessage());
    }

    @Test
    void rejectedPolicyAdmissionLeavesExistingMappingAndReturnsCandidate() {
        OresKeyedMap<String, String> map =
                OresKeyedMap.stringKeys(
                        OresKeyedMap.ValueDomain.STRING,
                        (key, value) -> {
                            if (value.equals("reject")) {
                                throw new IllegalArgumentException(
                                        "policy rejected candidate");
                            }
                        });
        String kept = new String("kept");
        map.put("key", kept);

        String candidate = new String("reject");
        OresKeyedMap.PutAttempt<String> rejected =
                map.tryPutOwned("key", candidate);

        assertFalse(rejected.committed());
        assertSame(candidate, rejected.takeUncommittedValue());
        assertSame(kept, map.getRequired("key"));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                rejected::rethrowAfterRecovery);
        assertEquals("policy rejected candidate", failure.getMessage());
    }

    @Test
    void rejectedJsonCycleReturnsCandidateWithoutPublishingTheEdge() {
        OresKeyedMap<String, OresJsonValue> fields =
                OresJsonValue.objectMap();
        OresJsonValue self = OresJsonValue.object(fields);

        OresKeyedMap.PutAttempt<OresJsonValue> rejected =
                fields.tryPutOwned("self", self);

        assertFalse(rejected.committed());
        assertSame(self, rejected.uncommittedValue());
        assertTrue(fields.isEmpty());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                rejected::rethrowIfRejected);
        assertTrue(failure.getMessage().contains("cyclic"),
                failure.getMessage());
    }

    @Test
    void ordinaryPutStillUsesTheSameAdmissionAndCommitContract() {
        OresKeyedMap<String, String> map =
                OresKeyedMap.stringKeys(
                        OresKeyedMap.ValueDomain.STRING,
                        (key, value) -> {
                            if (value.equals("bad")) {
                                throw new IllegalArgumentException("rejected");
                            }
                        });

        assertNull(map.put("key", "one"));
        assertEquals("one", map.put("key", "two"));
        assertEquals("two", map.getRequired("key"));

        assertThrows(
                IllegalArgumentException.class,
                () -> map.put("key", "bad"));
        assertEquals("two", map.getRequired("key"));
    }
}
