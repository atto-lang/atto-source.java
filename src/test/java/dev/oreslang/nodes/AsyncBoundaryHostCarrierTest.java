package dev.oreslang.nodes;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncBoundaryHostCarrierTest {
    @Test
    void hostileCollectionsAreRejectedWithoutCallbacks() {
        AtomicInteger callbacks = new AtomicInteger();

        final class HostileList extends ArrayList<Object> {
            @Override public int size() {
                callbacks.incrementAndGet();
                throw new AssertionError("host List.size invoked");
            }
            @Override public java.util.Iterator<Object> iterator() {
                callbacks.incrementAndGet();
                throw new AssertionError("host List.iterator invoked");
            }
        }

        final class HostileSet extends LinkedHashSet<Object> {
            @Override public int size() {
                callbacks.incrementAndGet();
                throw new AssertionError("host Set.size invoked");
            }
            @Override public java.util.Iterator<Object> iterator() {
                callbacks.incrementAndGet();
                throw new AssertionError("host Set.iterator invoked");
            }
        }

        final class HostileMap extends LinkedHashMap<Object, Object> {
            @Override public int size() {
                callbacks.incrementAndGet();
                throw new AssertionError("host Map.size invoked");
            }
            @Override public Set<Map.Entry<Object, Object>> entrySet() {
                callbacks.incrementAndGet();
                throw new AssertionError("host Map.entrySet invoked");
            }
        }

        HostileList list = new HostileList();
        list.add("x");
        HostileSet set = new HostileSet();
        set.add("x");
        HostileMap map = new HostileMap();
        map.put("k", "v");

        for (Object hostile : List.of(list, set, map)) {
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresEvalRootNode.requireTrustedAsyncCollectionCarrier(hostile));
            assertTrue(rejected.getMessage().contains("async task boundary"),
                    rejected.getMessage());
            assertEquals(0, callbacks.get());
        }
    }

    @Test
    void exactJdkGuestCarriersRemainAllowed() {
        assertDoesNotThrow(() ->
                OresEvalRootNode.requireTrustedAsyncCollectionCarrier(
                        new ArrayList<>(List.of(1, 2))));
        assertDoesNotThrow(() ->
                OresEvalRootNode.requireTrustedAsyncCollectionCarrier(
                        new LinkedHashSet<>(List.of(1, 2))));
        assertDoesNotThrow(() ->
                OresEvalRootNode.requireTrustedAsyncCollectionCarrier(
                        new LinkedHashMap<>(Map.of("x", 1))));
        assertDoesNotThrow(() ->
                OresEvalRootNode.requireTrustedAsyncCollectionCarrier(
                        java.util.Collections.nCopies(4, "x")));
    }

    @Test
    void subclassedBigIntegerIsNotAnAsyncScalar() {
        final class HostileBigInteger extends BigInteger {
            HostileBigInteger() {
                super("7");
            }
        }

        assertTrue(OresEvalRootNode.isTrustedAsyncScalar(BigInteger.valueOf(7)));
        assertFalse(OresEvalRootNode.isTrustedAsyncScalar(new HostileBigInteger()));
    }

    @Test
    void foreignHostEnumsAreNotAsyncScalars() {
        assertFalse(OresEvalRootNode.isTrustedAsyncScalar(java.time.DayOfWeek.MONDAY));
    }
}
