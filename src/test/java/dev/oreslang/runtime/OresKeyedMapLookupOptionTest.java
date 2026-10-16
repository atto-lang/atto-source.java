package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class OresKeyedMapLookupOptionTest {
    private enum StoredOption {
        NONE, SOME_FALSE, SOME_ZERO, SOME_EMPTY
    }

    @Test
    void lookupSeparatesMissingFromStoredOptionLikeValues() {
        OresKeyedMap<String, Object> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.ANY);

        var missing = map.lookup("pending");
        assertFalse(missing.present());
        assertNull(missing.value());

        map.put("pending", StoredOption.NONE);
        var storedNone = map.lookup("pending");
        assertTrue(storedNone.present());
        assertSame(StoredOption.NONE, storedNone.value());

        map.put("pending", StoredOption.SOME_FALSE);
        assertEquals(StoredOption.SOME_FALSE, map.lookup("pending").value());

        map.put("pending", StoredOption.SOME_ZERO);
        assertEquals(StoredOption.SOME_ZERO, map.lookup("pending").value());

        map.put("pending", StoredOption.SOME_EMPTY);
        assertEquals(StoredOption.SOME_EMPTY, map.lookup("pending").value());
    }

    @Test
    void takeIsAtomicAndDistinguishesMissingFromTransferredValue() {
        OresKeyedMap<String, Object> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.ANY);
        map.put("pending", StoredOption.NONE);

        var first = map.take("pending");
        assertTrue(first.present());
        assertSame(StoredOption.NONE, first.value());
        assertFalse(map.containsKey("pending"));

        var second = map.take("pending");
        assertFalse(second.present());
        assertNull(second.value());
    }

    @Test
    void lookupAndTakeRejectWrongKeyDomainBeforeTouchingStorage() {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.put("ready", 1);

        assertThrows(IllegalArgumentException.class,
                () -> map.lookup(OresSymbol.process("ready")));
        assertThrows(IllegalArgumentException.class,
                () -> map.take(OresSymbol.process("ready")));

        assertEquals(1, map.lookup("ready").value());
        assertEquals(1, map.size());
    }

    @Test
    void takeRefundsAggregateKeyBudgetExactlyOnce() {
        OresKeyedMap<String, Integer> map = OresKeyedMap.stringKeys();
        String prefix = "x".repeat(OresKeyedMap.MAX_TEXT_KEY_BYTES - 2);
        for (int i = 0; i < 63; i++) {
            map.put(prefix + String.format("%02d", i), i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> map.put(prefix + "63", 63));

        var removed = map.take(prefix + "00");
        assertTrue(removed.present());
        assertEquals(0, removed.value());

        map.put(prefix + "63", 63);
        assertEquals(63, map.size());

        var alreadyMissing = map.take(prefix + "00");
        assertFalse(alreadyMissing.present());
        assertEquals(63, map.size(),
                "taking an absent key must not refund quota twice");
    }

    @Test
    void concurrentLookupNeverReturnsPresentWithNull() throws Exception {
        OresKeyedMap<String, Integer> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.put("status", 1);

        var workers = Executors.newFixedThreadPool(2);
        try {
            var writer = workers.submit(() -> {
                for (int i = 0; i < 10_000; i++) {
                    map.take("status");
                    map.put("status", 1);
                }
            });
            var reader = workers.submit(() -> {
                for (int i = 0; i < 10_000; i++) {
                    var result = map.lookup("status");
                    if (result.present()) {
                        assertEquals(1, result.value());
                    } else {
                        assertNull(result.value());
                    }
                }
            });
            writer.get(15, TimeUnit.SECONDS);
            reader.get(15, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void keyAwareValidatorRunsBeforeMutationAndBatchCommit() {
        OresKeyedMap<String, Object> map = OresKeyedMap.stringKeys(
                OresKeyedMap.ValueDomain.ANY,
                (key, value) -> {
                    if (key.equals("count") && !(value instanceof Long)) {
                        throw new IllegalArgumentException("count must be int");
                    }
                });
        map.put("count", 1L);
        map.put("extra", "open");
        assertThrows(IllegalArgumentException.class,
                () -> map.put("count", "wrong"));
        assertEquals(1L, map.getRequired("count"));

        java.util.LinkedHashMap<String, Object> batch =
                new java.util.LinkedHashMap<>();
        batch.put("safe", Boolean.TRUE);
        batch.put("count", "wrong");
        assertThrows(IllegalArgumentException.class, () -> map.putAll(batch));
        assertFalse(map.containsKey("safe"),
                "entry validation failure must keep putAll transactional");
        assertEquals(1L, map.getRequired("count"));
    }

    @Test
    void lookupRecordRejectsImpossibleStates() {
        assertThrows(IllegalArgumentException.class,
                () -> new OresKeyedMap.Lookup<>(true, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OresKeyedMap.Lookup<>(false, 1));
    }
}
