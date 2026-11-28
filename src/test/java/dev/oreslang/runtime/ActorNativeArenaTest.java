package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorNativeArenaTest {
    @Test
    void freshNativeFieldsPreserveUninitializedDistinctFromNull() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(16 * 1024, charged)) {
            Map<String, Object> fields = arena.fields(List.of("left", "right"), marker);
            assertSame(marker, fields.get("left"));
            assertSame(marker, fields.get("right"));

            fields.put("left", null);
            assertNull(fields.get("left"));
            assertSame(marker, fields.get("right"),
                    "writing null to one field must not initialize a sibling slot");
        }
        assertEquals(0, charged.get());
    }

    @Test
    void nativeSlotsPreserveScalarTypesAndRejectManagedPayloadFallback() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(1 << 20, charged)) {
            Map<String, Object> fields = arena.fields(List.of("value"), marker);
            assertTrue(fields instanceof ActorNativeArena.NativeValue);
            assertTrue(((ActorNativeArena.NativeValue) fields).nativeOffset() >= 0);
            for (Object value : List.of(true, (byte) 1, (short) 2, 3, Long.MAX_VALUE,
                    -0.0f, Double.NaN, '\ud800', "\ud800\u0000\udc00", "", new BigInteger("12345678901234567890"),
                    new BigDecimal("1.2300"), OresSymbol.process("ready"), marker)) {
                fields.put("value", value);
                assertEquals(value, fields.get("value"));
                assertEquals(value.getClass(), fields.get("value").getClass());
            }
            fields.put("value", 42L);
            long allocated = arena.allocatedBytes();
            for (int i = 0; i < 10_000; i++) fields.put("value", (long) i);
            assertEquals(allocated, arena.allocatedBytes(), "scalar mutation must not allocate native payloads");
            assertThrows(IllegalArgumentException.class, () -> fields.put("value", new Object()));
            assertEquals(9999L, fields.get("value"), "failed stores cannot corrupt existing data");
            assertThrows(IllegalArgumentException.class, () -> fields.put("unknown", 1));
            fields.put("value", null);
            assertTrue(fields.containsKey("value"));
            assertNull(fields.get("value"));
            assertTrue(charged.get() > 0);
        }
        assertEquals(0, charged.get(), "native bytes must be refunded by explicit close");
    }

    @Test
    void lexicalValueCellsKeepSupportedPayloadsNativeAndRejectForeignAuthority() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(16 * 1024, charged);
             ActorNativeArena foreignArena = arena(16 * 1024, new AtomicLong())) {
            ActorNativeArena.ValueCell cell = arena.valueCell(41L, marker);
            assertTrue(cell.nativeOffset() >= 0);
            assertEquals(41L, cell.get());

            assertTrue(cell.trySet("native-local"));
            assertEquals("native-local", cell.get());

            Map<String, Object> object = arena.fields(List.of("value"), marker);
            object.put("value", 7L);
            assertTrue(cell.trySet(object),
                    "actor-native lexical references must persist as arena-issued offsets");
            assertSame(object, cell.get());
            assertEquals(1, arena.nativeReferenceCount());

            assertFalse(cell.trySet(List.of(1, 2, 3)),
                    "managed-only collection fallback must remain explicit");
            assertSame(object, cell.get(),
                    "unsupported managed fallback must not corrupt the native lexical reference");

            Map<String, Object> foreign = foreignArena.fields(List.of("value"), marker);
            assertThrows(SecurityException.class, () -> cell.trySet(foreign),
                    "a lexical slot must never admit a foreign arena reference");
            assertSame(object, cell.get());

            ActorNativeArena.NativeList nativeList = arena.nativeList();
            nativeList.add(9L);
            assertTrue(cell.trySet(nativeList),
                    "actor-native collection locals must use the same offset reference path");
            assertSame(nativeList, cell.get());
            assertEquals(1, arena.nativeReferenceCount());

            assertTrue(cell.trySet("native-local-again"));
            assertEquals("native-local-again", cell.get());
            assertEquals(0, arena.nativeReferenceCount(),
                    "overwriting the lexical native reference must release registry retention");
        }
        assertEquals(0, charged.get());
    }

    @Test
    void nativeListUsesArenaSlotsAcrossChunksAndReleasesElementReferences() {
        AtomicLong charged = new AtomicLong();
        AtomicLong foreignCharged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(128 * 1024, charged);
             ActorNativeArena foreignArena = arena(16 * 1024, foreignCharged)) {
            ActorNativeArena.NativeList list = arena.nativeList();
            assertEquals(0, list.size());
            assertTrue(list.nativeOffset() >= 0);

            for (int i = 0; i < 40; i++) list.add((long) i);
            assertEquals(40, list.size());
            for (int i = 0; i < 40; i++) assertEquals((long) i, list.get(i));
            assertEquals(17L, list.set(17, 1700L));
            assertEquals(1700L, list.get(17));
            assertThrows(IndexOutOfBoundsException.class, () -> list.get(40));
            assertThrows(UnsupportedOperationException.class, () -> list.add(1, 99L));

            Map<String, Object> child = arena.fields(List.of("value"), marker);
            child.put("value", 7L);
            list.add(child);
            assertEquals(1, arena.nativeReferenceCount());
            assertSame(child, list.get(40));

            Map<String, Object> foreign = foreignArena.fields(List.of("value"), marker);
            assertThrows(SecurityException.class, () -> list.add(foreign));
            assertEquals(41, list.size(), "failed append must not publish a list element");

            Map<String, Object> holder = arena.fields(List.of("items"), marker);
            holder.put("items", list);
            assertEquals(2, arena.nativeReferenceCount(),
                    "child element and list facade are distinct live native references");
            assertSame(list, holder.get("items"));

            list.clear();
            assertEquals(0, list.size());
            assertEquals(1, arena.nativeReferenceCount(),
                    "clear must retire element references while preserving the holder->list reference");
            holder.put("items", null);
            assertEquals(0, arena.nativeReferenceCount());

            list.add("reused-after-clear");
            assertEquals("reused-after-clear", list.get(0));
        }
        assertEquals(0, charged.get());
        assertEquals(0, foreignCharged.get());
    }

    @Test
    void rejectedNativeListAppendsDoNotConsumeArenaQuotaOrPublishSlots() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(4096, charged);
             ActorNativeArena foreign = arena(4096, new AtomicLong())) {
            ActorNativeArena.NativeList list = arena.nativeList();
            Object invalid = new Object();
            Map<String, Object> foreignObject = foreign.fields(List.of("value"), marker);
            long emptyAllocated = arena.allocatedBytes();
            long emptyCommitted = arena.committedBytes();

            // Every rejected first-element append previously reserved an orphan
            // chunk before noticing that the payload could not be native.
            for (int attempt = 0; attempt < 64; attempt++) {
                assertThrows(IllegalArgumentException.class, () -> list.add(invalid));
                assertThrows(SecurityException.class, () -> list.add(foreignObject));
            }
            assertEquals(0, list.size());
            assertEquals(emptyAllocated, arena.allocatedBytes());
            assertEquals(emptyCommitted, arena.committedBytes());

            for (int i = 0; i < 16; i++) list.add((long) i);
            long fullAllocated = arena.allocatedBytes();
            long fullCommitted = arena.committedBytes();
            // Reject at the 16-element chunk boundary before allocating chunk 2.
            for (int attempt = 0; attempt < 64; attempt++) {
                assertThrows(IllegalArgumentException.class, () -> list.add(invalid));
                assertThrows(SecurityException.class, () -> list.add(foreignObject));
            }
            assertEquals(16, list.size());
            assertEquals(15L, list.get(15));
            assertEquals(fullAllocated, arena.allocatedBytes());
            assertEquals(fullCommitted, arena.committedBytes());
            list.add(16L);
            assertEquals(17, list.size());
            assertEquals(16L, list.get(16));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void nativeListPersistentPreflightAdmitsIssuedCapabilitiesAndRejectsAliasBeforeAllocation() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(8192, charged)) {
            ActorNativeArena.NativeList list = arena.nativeList();

            ActorNativeArena.NativeCapability capability = () -> arena;
            arena.issueNativeCapability(capability);
            list.add(capability);
            assertSame(capability, list.get(0));
            assertEquals(1, arena.capabilityCount());
            list.clear();
            assertEquals(0, arena.capabilityCount());

            Map<String, Object> rawBacking = arena.fields(List.of("value"), marker);
            ActorNativeArena.NativeValue backing =
                    (ActorNativeArena.NativeValue) rawBacking;
            long offset = backing.nativeOffset();
            ActorNativeArena.NativeValue alias = new ActorNativeArena.NativeValue() {
                @Override public ActorNativeArena nativeArena() { return arena; }
                @Override public long nativeOffset() { return offset; }
            };
            arena.registerNativeFacade(alias, backing);

            Map<String, Object> holder = arena.fields(List.of("value"), marker);
            holder.put("value", backing);
            long allocated = arena.allocatedBytes();
            long committed = arena.committedBytes();

            SecurityException duplicate = assertThrows(
                    SecurityException.class,
                    () -> list.add(alias));
            assertTrue(
                    duplicate.getMessage().contains("duplicate actor-relative native address"),
                    duplicate.toString());
            assertEquals(0, list.size());
            assertEquals(allocated, arena.allocatedBytes(),
                    "rejected alias must not reserve a new List chunk");
            assertEquals(committed, arena.committedBytes());
        }
        assertEquals(0, charged.get());
    }

    @Test
    void clearingAndRefillingNativeListReusesItsHistoricalChunkCapacity() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(8192, charged)) {
            ActorNativeArena.NativeList list = arena.nativeList();
            for (int i = 0; i < 40; i++) list.add((long) i);
            long allocated = arena.allocatedBytes();
            long committed = arena.committedBytes();
            assertTrue(committed > 0L);
            for (int cycle = 0; cycle < 120; cycle++) {
                list.clear();
                assertEquals(0, list.size());
                for (int i = 0; i < 40; i++) list.add((long) (cycle * 100 + i));
                assertEquals(40, list.size());
                assertEquals((long) (cycle * 100 + 39), list.get(39));
                assertEquals(allocated, arena.allocatedBytes(),
                        "repeated clear/refill must reuse actor-relative List chunk offsets");
                assertEquals(committed, arena.committedBytes(),
                        "cleared native List capacity must not leak additional arena budget");
            }
            list.clear();
            list.add(42L);
            assertEquals(42L, list.get(0));
            assertEquals(allocated, arena.allocatedBytes());
        }
        assertEquals(0, charged.get());
    }

    @Test
    void nativeReferenceRegistryRetainsOnlyCurrentlyReferencedFacades() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(16 * 1024, charged)) {
            Map<String, Object> holder = arena.fields(List.of("left", "right"), marker);
            Map<String, Object> child = arena.fields(List.of("value"), marker);

            assertEquals(0, arena.nativeReferenceCount());
            holder.put("left", child);
            assertEquals(1, arena.nativeReferenceCount());
            holder.put("right", child);
            assertEquals(1, arena.nativeReferenceCount(),
                    "registry cardinality is by native object identity, not slot count");

            holder.put("left", null);
            assertEquals(1, arena.nativeReferenceCount(),
                    "one remaining native slot keeps the facade resolvable");
            assertSame(child, holder.get("right"));

            holder.put("right", null);
            assertEquals(0, arena.nativeReferenceCount(),
                    "last slot overwrite must release the strong reference registry entry");

            // Provenance remains valid through the weak issued ledger while the
            // caller still owns the facade, so it can be stored again safely.
            holder.put("left", child);
            assertEquals(1, arena.nativeReferenceCount());
            assertSame(child, holder.get("left"));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void staleNativeCapabilityCanBeOverwrittenAndRetired() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean revoked = new AtomicBoolean();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(16 * 1024, charged)) {
            Map<String, Object> fields = arena.fields(List.of("request"), marker);
            ActorNativeArena.NativeCapability capability = () -> {
                if (revoked.get()) throw new IllegalStateException("capability is terminal");
                return arena;
            };

            assertThrows(SecurityException.class, () -> fields.put("request", capability),
                    "same-arena claim is not sufficient without trusted issuance");
            arena.issueNativeCapability(capability);
            fields.put("request", capability);
            assertEquals(1, arena.capabilityCount());
            assertSame(capability, fields.get("request"));

            revoked.set(true);
            assertThrows(IllegalStateException.class, () -> fields.get("request"),
                    "normal reads must still revalidate a terminal capability");

            assertSame(capability, fields.put("request", null),
                    "overwrite may retire a stale handle without re-authorizing it");
            assertNull(fields.get("request"));
            assertEquals(0, arena.capabilityCount(),
                    "overwriting the last stale handle must release registry retention");
        }
        assertEquals(0, charged.get());
    }

    @Test
    void nativeCapabilityHandlesAreOpaqueArenaLocalAndReferenceCounted() {
        AtomicLong charged = new AtomicLong();
        AtomicLong foreignCharged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(16 * 1024, charged);
             ActorNativeArena foreignArena = arena(16 * 1024, foreignCharged)) {
            Map<String, Object> fields = arena.fields(List.of("left", "right"), marker);
            ActorNativeArena.NativeCapability capability = () -> arena;
            ActorNativeArena.NativeCapability foreign = () -> foreignArena;

            assertEquals(0, arena.capabilityCount());
            assertThrows(SecurityException.class, () -> fields.put("left", capability),
                    "unissued, host-implemented capability must not mint an actor handle");
            assertThrows(SecurityException.class,
                    () -> arena.canStorePersistentValue(capability, marker),
                    "preflight must reject unissued capabilities, not just slot publication");
            assertEquals(0, arena.capabilityCount());
            arena.issueNativeCapability(capability);
            fields.put("left", capability);
            assertEquals(1, arena.capabilityCount());
            assertSame(capability, fields.get("left"));

            fields.put("right", capability);
            assertEquals(1, arena.capabilityCount(),
                    "two slots may share one opaque capability identity");
            assertSame(capability, fields.get("right"));

            fields.put("left", null);
            assertEquals(1, arena.capabilityCount(),
                    "remaining slot must keep the capability live");
            fields.put("right", null);
            assertEquals(0, arena.capabilityCount(),
                    "last native slot overwrite must retire the handle registry entry");

            assertThrows(SecurityException.class, () -> fields.put("left", foreign));
            assertEquals(0, arena.capabilityCount());
            // Even a malicious host wrapper claiming the correct arena needs
            // an explicit issuance by the trusted core.
            ActorNativeArena.NativeCapability forged = () -> arena;
            assertThrows(SecurityException.class, () -> fields.put("left", forged));
            assertEquals(0, arena.capabilityCount());

            assertThrows(IllegalArgumentException.class,
                    () -> arena.valueCell(capability, marker),
                    "lexical capabilities remain managed until native scope-drop lowering exists");
            assertEquals(0, arena.capabilityCount(),
                    "rejected lexical lowering must not publish a capability handle");
        }
        assertEquals(0, charged.get());
        assertEquals(0, foreignCharged.get());
    }

    @Test
    void capabilityIssuanceUsesObjectIdentityNotForgedEqualsAndCannotExhaustQuota() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(8192, charged)) {
            class EqualCapability implements ActorNativeArena.NativeCapability {
                @Override public ActorNativeArena nativeArena() { return arena; }
                @Override public boolean equals(Object other) { return other instanceof EqualCapability; }
                @Override public int hashCode() { return 1; }
            }
            var issued = new EqualCapability();
            var forged = new EqualCapability();
            Map<String, Object> fields = arena.fields(List.of("handle"), marker);
            arena.issueNativeCapability(issued);
            long allocated = arena.allocatedBytes();
            for (int attempt = 0; attempt < 100; attempt++) {
                assertThrows(SecurityException.class, () -> fields.put("handle", forged),
                        "equal() must not impersonate another issued capability");
                assertEquals(0, arena.capabilityCount());
                assertEquals(allocated, arena.allocatedBytes(),
                        "denied host wrappers cannot consume native quota");
            }
            fields.put("handle", issued);
            assertSame(issued, fields.get("handle"));
            fields.put("handle", null);
            assertEquals(0, arena.capabilityCount());
        }
        assertEquals(0, charged.get());
    }

    @Test
    void referencesStayInTheirArenaAndCyclesDoNotPreventReclamation() {
        AtomicLong charged = new AtomicLong();
        ActorNativeArena first = arena(8192, charged);
        ActorNativeArena second = arena(8192, charged);
        Map<String, Object> left = first.fields(List.of("next"), new Object());
        Map<String, Object> right = first.fields(List.of("next"), new Object());
        left.put("next", right);
        right.put("next", left);
        assertSame(right, left.get("next"));
        assertSame(left, right.get("next"));
        long leftOffset = ((ActorNativeArena.NativeValue) left).nativeOffset();
        long rightOffset = ((ActorNativeArena.NativeValue) right).nativeOffset();
        assertNotEquals(leftOffset, rightOffset, "distinct actor objects need distinct logical addresses");
        assertTrue(leftOffset >= 0 && rightOffset >= 0);
        Map<String, Object> foreign = second.fields(List.of("next"), new Object());
        assertThrows(SecurityException.class, () -> left.put("next", foreign));
        first.close();
        first.close();
        assertThrows(IllegalStateException.class, () -> left.get("next"));
        second.close();
        assertEquals(0, charged.get());
    }

    @Test
    void nativeReferencesRequireArenaIssuedProvenanceNotSelfAssertedOffsets() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(8192, charged)) {
            Map<String, Object> holder = arena.fields(List.of("value"), marker);
            Map<String, Object> backing = arena.fields(List.of("data"), marker);
            ActorNativeArena.NativeValue fields = (ActorNativeArena.NativeValue) backing;
            long issuedAddress = fields.nativeOffset();
            ActorNativeArena.NativeValue forged = new ActorNativeArena.NativeValue() {
                @Override public ActorNativeArena nativeArena() { return arena; }
                @Override public long nativeOffset() { return issuedAddress; }
            };
            assertThrows(SecurityException.class, () -> holder.put("value", forged),
                    "a Java object claiming a legitimate address is not an arena-issued capability");
            assertSame(marker, holder.get("value"),
                    "failed admission must not publish a guest reference");
            holder.put("value", backing);
            assertSame(backing, holder.get("value"));

            // A facade may be registered to a real native allocation, but it
            // must not silently replace an existing object identity at that offset.
            arena.registerNativeFacade(forged, fields);
            assertThrows(SecurityException.class, () -> holder.put("value", forged),
                    "two facades must not alias the same offset with different identities");
            assertSame(backing, holder.get("value"),
                    "address collisions must leave the previously stored reference intact");

            ActorNativeArena.NativeValue unissued = new ActorNativeArena.NativeValue() {
                @Override public ActorNativeArena nativeArena() { return arena; }
                @Override public long nativeOffset() { return issuedAddress + 8; }
            };
            assertThrows(SecurityException.class, () -> arena.registerNativeFacade(unissued, fields),
                    "facade registration must verify its exact issued backing address");
            assertSame(backing, holder.get("value"));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void staleReferenceCanBeClearedButCannotBeReadOrRestored() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(8192, charged)) {
            Map<String, Object> holder = arena.fields(List.of("reference"), marker);
            Map<String, Object> backing = arena.fields(List.of("payload"), marker);
            ActorNativeArena.NativeValue nativeBacking = (ActorNativeArena.NativeValue) backing;
            long original = nativeBacking.nativeOffset();
            AtomicLong visibleOffset = new AtomicLong(original);
            ActorNativeArena.NativeValue facade = new ActorNativeArena.NativeValue() {
                @Override public ActorNativeArena nativeArena() { return arena; }
                @Override public long nativeOffset() { return visibleOffset.get(); }
            };
            arena.registerNativeFacade(facade, nativeBacking);
            holder.put("reference", facade);
            assertSame(facade, holder.get("reference"));
            assertEquals(1, arena.nativeReferenceCount());

            visibleOffset.set(original + 8);
            assertThrows(SecurityException.class, () -> holder.get("reference"));
            assertSame(facade, holder.put("reference", null));
            assertNull(holder.get("reference"));
            assertEquals(0, arena.nativeReferenceCount());

            assertThrows(SecurityException.class, () -> holder.put("reference", facade));
            assertNull(holder.get("reference"));
            assertEquals(0, arena.nativeReferenceCount());
        }
        assertEquals(0, charged.get());
    }

    @Test
    void revokedNativeOffsetDoesNotPreventReferenceRetirement() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(8192, charged)) {
            Map<String, Object> holder = arena.fields(List.of("reference"), marker);
            Map<String, Object> backing = arena.fields(List.of("payload"), marker);
            ActorNativeArena.NativeValue backingNative = (ActorNativeArena.NativeValue) backing;
            AtomicBoolean revoked = new AtomicBoolean();
            ActorNativeArena.NativeValue facade = new ActorNativeArena.NativeValue() {
                @Override public ActorNativeArena nativeArena() { return arena; }
                @Override public long nativeOffset() {
                    if (revoked.get()) throw new IllegalStateException("native reference revoked");
                    return backingNative.nativeOffset();
                }
            };
            arena.registerNativeFacade(facade, backingNative);
            holder.put("reference", facade);
            revoked.set(true);

            assertThrows(IllegalStateException.class, () -> holder.get("reference"));
            assertSame(facade, holder.put("reference", null));
            assertNull(holder.get("reference"));
            assertEquals(0, arena.nativeReferenceCount());
            assertThrows(IllegalStateException.class, () -> holder.put("reference", facade));
            assertNull(holder.get("reference"));
        }
        assertEquals(0, charged.get());
    }

    @Test
    void nativeSpanClearChecksBoundsAndActiveReadBorrowsBeforeScrubbing() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(8192, charged)) {
            var region = arena.region(32);
            region.writeLong(0, -1L);
            region.writeLong(8, -1L);
            region.writeLong(16, -1L);
            var read = region.leaseReadOnly(8, 8);
            assertThrows(IllegalStateException.class, () -> region.clearRange(0, 32),
                    "scrub must never write through a live immutable borrow");
            assertEquals(-1L, region.readLong(8), "failed scrub cannot corrupt data");
            read.close();
            assertThrows(IndexOutOfBoundsException.class, () -> region.clearRange(-1, 8));
            assertThrows(IndexOutOfBoundsException.class, () -> region.clearRange(30, 4));
            assertThrows(IndexOutOfBoundsException.class, () -> region.clearRange(0, -1));
            region.clearRange(8, 8);
            assertEquals(-1L, region.readLong(0));
            assertEquals(0L, region.readLong(8));
            assertEquals(-1L, region.readLong(16));
            region.clearRange(0, 32);
            assertEquals(0L, region.readLong(0));
            assertEquals(0L, region.readLong(16));
            region.close();
            assertThrows(IllegalStateException.class, () -> region.clearRange(0, 8));
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void primitiveRegionsHaveExplicitReleaseBoundsAndBudget() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(128, charged)) {
            var region = arena.region(128);
            region.writeLong(0, Long.MIN_VALUE);
            region.writeDouble(120, 3.5);
            assertEquals(Long.MIN_VALUE, region.readLong(0));
            assertEquals(3.5, region.readDouble(120));
            assertThrows(IndexOutOfBoundsException.class, () -> region.readLong(121));
            assertThrows(IndexOutOfBoundsException.class, () -> region.writeLong(-1, 3));
            assertThrows(IllegalArgumentException.class, () -> arena.region(8));
            region.close();
            region.close();
            assertTrue(region.closed());
            assertEquals(0, charged.get());
            assertThrows(IllegalStateException.class, () -> region.readLong(0));
            // Releasing a dense store makes the same budget reusable.
            arena.region(128).writeLong(0, 7);
        }
        assertEquals(0, charged.get());
    }

    @Test
    void fieldTextUpdatesReuseNativeCapacityAcrossShrinkAndRegrow() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(40, charged)) {
            Map<String, Object> fields = arena.fields(List.of("value"), new Object());
            fields.put("value", "alfa");
            long allocated = arena.allocatedBytes();
            for (int i = 0; i < 1000; i++) {
                fields.put("value", (i & 1) == 0 ? "beta" : "zeta");
                assertEquals((i & 1) == 0 ? "beta" : "zeta", fields.get("value"));
            }
            assertEquals(allocated, arena.allocatedBytes(),
                    "same-size text mutation must not consume arena lifetime budget");
            fields.put("value", new BigInteger("1234"));
            assertEquals(new BigInteger("1234"), fields.get("value"));
            assertEquals(allocated, arena.allocatedBytes(),
                    "same-size native text should be reusable across supported tagged types");

            fields.put("value", "x");
            assertEquals("x", fields.get("value"));
            fields.put("value", "four");
            assertEquals("four", fields.get("value"));
            assertEquals(allocated, arena.allocatedBytes(),
                    "shrink + regrow within original text capacity must stay allocation-free");

            fields.put("value", new BigInteger("12"));
            fields.put("value", new BigInteger("9999"));
            assertEquals(new BigInteger("9999"), fields.get("value"));
            assertEquals(allocated, arena.allocatedBytes(),
                    "text capacity reuse must survive supported tag changes");

            assertThrows(IllegalStateException.class, () -> fields.put("value", "larger"));
            assertEquals(new BigInteger("1234"), fields.get("value"),
                    "failed growth must preserve the old field");
        }
        assertEquals(0, charged.get());
    }

    @Test
    void readOnlyNativeLeasesFreezeWritesUntilReleaseAndRevokeAtActorExit() throws Exception {
        AtomicLong charged = new AtomicLong();
        Thread owner = Thread.currentThread();
        ActorNativeArena arena = new ActorNativeArena(64, () -> {
            if (Thread.currentThread() != owner) {
                throw new SecurityException("foreign native region access");
            }
        }, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
        var region = arena.region(64);
        region.writeLong(8, 77L);
        var first = region.leaseReadOnly(8, 16);
        var second = region.leaseReadOnly(8, 8);
        assertEquals(77L, first.readLong(0));
        assertEquals(77L, second.readLong(0));
        assertEquals(77, first.readUnsignedByte(0));
        assertEquals((byte) 77, first.readByte(0));
        assertEquals(0, first.readUnsignedByte(1));
        assertEquals(0, first.readUnsignedByte(15));
        assertThrows(IndexOutOfBoundsException.class, () -> first.readByte(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> first.readByte(16));
        assertThrows(IndexOutOfBoundsException.class, () -> second.readUnsignedByte(8));
        assertThrows(IndexOutOfBoundsException.class, () -> first.readLong(9));
        assertThrows(IndexOutOfBoundsException.class, () -> region.leaseReadOnly(60, 8));
        assertThrows(IllegalStateException.class, () -> region.writeLong(8, 100L));
        assertThrows(IllegalStateException.class, () -> region.writeDouble(8, 1.0));
        assertThrows(IllegalStateException.class, region::close);
        AtomicReference<Throwable> foreign = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try { first.readLong(0); }
            catch (Throwable failure) { foreign.set(failure); }
        });
        other.start();
        other.join();
        assertInstanceOf(SecurityException.class, foreign.get());
        first.close();
        first.close();
        assertThrows(IllegalStateException.class, () -> first.readLong(0));
        assertThrows(IllegalStateException.class, () -> first.readByte(0));
        assertThrows(IllegalStateException.class, () -> region.writeLong(8, 100L));
        second.close();
        region.writeLong(8, 100L);
        assertEquals(100L, region.readLong(8));
        var outstanding = region.leaseReadOnly(8, 8);
        assertTrue(arena.hasOutstandingReadLeases(),
                "host physical finalization preflight must see the active native lease");
        // A purported quiescence barrier must not deallocate memory while
        // an issued native read lease still retains its backing region.
        assertThrows(IllegalStateException.class, arena::close);
        assertFalse(arena.closed());
        assertEquals(100L, outstanding.readLong(0));
        assertEquals(64L, charged.get());
        outstanding.close();
        assertFalse(arena.hasOutstandingReadLeases(),
                "preflight must clear after the real owner releases the final lease");
        arena.close();
        assertThrows(IllegalStateException.class, () -> outstanding.readLong(0));
        assertThrows(IllegalStateException.class, () -> outstanding.readByte(0));
        assertEquals(0, charged.get());
    }

    @Test
    void foreignCompletionCarrierMayReleaseButNotReadNativeLease() throws Exception {
        AtomicLong charged = new AtomicLong();
        Thread owner = Thread.currentThread();
        ActorNativeArena arena = new ActorNativeArena(128, () -> {
            if (Thread.currentThread() != owner)
                throw new SecurityException("native actor read belongs to owner carrier");
        }, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });

        var region = arena.region(64);
        region.writeLong(0, 5150L);
        var first = region.leaseReadOnly(0, 8);
        var second = region.leaseReadOnly(0, 8);
        assertThrows(IllegalStateException.class, region::close);
        assertThrows(IllegalStateException.class, arena::close);
        assertEquals(64L, charged.get());

        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try { first.readLong(0); }
            catch (Throwable failure) { readFailure.set(failure); }
            try {
                first.close();
                first.close();
            } catch (Throwable failure) { releaseFailure.set(failure); }
        });
        foreign.start();
        foreign.join(10_000);
        assertFalse(foreign.isAlive(), "foreign completion carrier failed to drain lease");
        assertInstanceOf(SecurityException.class, readFailure.get(),
                "foreign carrier must not read native payload");
        assertNull(releaseFailure.get(), "an admitted lease may release on any carrier");

        assertEquals(5150L, second.readLong(0));
        assertThrows(IllegalStateException.class, () -> region.writeLong(0, 6L),
                "second reader must still exclude native mutation");
        assertThrows(IllegalStateException.class, arena::close);
        assertFalse(arena.closed());
        second.close();
        region.writeLong(0, 6L);
        assertEquals(6L, region.readLong(0));
        arena.close();
        assertEquals(0L, charged.get());
        assertThrows(IllegalStateException.class, () -> first.readLong(0));
    }

    @Test
    void actorTaskFramesPersistResumeAuthorityInNativeMemory() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(4096, charged)) {
            ActorNativeArena.TaskFrame frame = arena.taskFrame();
            long address = frame.nativeOffset();
            assertTrue(address >= 0);
            assertEquals(ActorNativeArena.TaskPhase.NEW, frame.phase());
            assertEquals(0, frame.resumeCount());

            frame.beginInitialResume();
            assertEquals(ActorNativeArena.TaskPhase.RUNNING, frame.phase());
            assertEquals(1, frame.resumeCount());
            long awaitTicket = frame.awaiting();
            assertTrue(awaitTicket > 0);
            assertEquals(ActorNativeArena.TaskPhase.AWAITING, frame.phase());
            assertThrows(IllegalStateException.class,
                    () -> frame.beginResume(ActorNativeArena.SuspensionKind.COOPERATE, awaitTicket));
            assertThrows(IllegalStateException.class,
                    () -> frame.beginResume(ActorNativeArena.SuspensionKind.AWAIT, awaitTicket + 1));

            frame.beginResume(ActorNativeArena.SuspensionKind.AWAIT, awaitTicket);
            assertEquals(2, frame.resumeCount());
            long cooperateTicket = frame.cooperating();
            assertTrue(cooperateTicket > awaitTicket);
            assertEquals(ActorNativeArena.TaskPhase.COOPERATING, frame.phase());
            assertThrows(IllegalStateException.class,
                    () -> frame.beginResume(ActorNativeArena.SuspensionKind.COOPERATE, awaitTicket));

            frame.beginResume(ActorNativeArena.SuspensionKind.COOPERATE, cooperateTicket);
            assertEquals(3, frame.resumeCount());
            frame.done();
            assertEquals(ActorNativeArena.TaskPhase.DONE, frame.phase());
            assertThrows(IllegalStateException.class,
                    () -> frame.beginResume(ActorNativeArena.SuspensionKind.COOPERATE, cooperateTicket));

            ActorNativeArena.TaskFrame failed = arena.taskFrame();
            assertNotEquals(address, failed.nativeOffset());
            failed.beginInitialResume();
            long failedTicket = failed.awaiting();
            failed.failed();
            assertThrows(IllegalStateException.class,
                    () -> failed.beginResume(ActorNativeArena.SuspensionKind.AWAIT, failedTicket));
            assertEquals(ActorNativeArena.TaskPhase.FAILED, failed.phase());
        }
        assertEquals(0, charged.get());
    }

    @Test
    void rejectedNativeRegionReservationDoesNotConsumeActorRelativeAddress() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean reject = new AtomicBoolean();
        try (ActorNativeArena arena = new ActorNativeArena(128, () -> {}, bytes -> {
            if (reject.get()) throw new IllegalStateException("synthetic quota denial");
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            try (ActorNativeArena.Region first = arena.region(9)) {
                assertEquals(0L, first.nativeOffset());
            }
            assertEquals(0L, charged.get());
            reject.set(true);
            assertThrows(IllegalStateException.class, () -> arena.region(9));
            assertEquals(0L, charged.get());
            reject.set(false);
            try (ActorNativeArena.Region next = arena.region(9)) {
                // A closed allocation is never recycled; a failed admission
                // must not create an additional phantom logical allocation.
                assertEquals(16L, next.nativeOffset());
                next.writeByte(0, (byte) 42);
                assertEquals((byte) 42, next.readByte(0));
            }
            assertEquals(0L, charged.get());
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void rejectedSlabGrowthPreservesPublishedMetadataAndExistingValues() {
        AtomicLong charged = new AtomicLong();
        AtomicBoolean reject = new AtomicBoolean();
        try (ActorNativeArena arena = new ActorNativeArena(8192, () -> {}, bytes -> {
            if (reject.get()) throw new IllegalStateException("synthetic growth quota denial");
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorNativeArena.ValueCell first = null;
            ActorNativeArena.ValueCell last = null;
            for (int index = 0; index < 170; index++) {
                last = arena.valueCell((long) index, new Object());
                if (first == null) first = last;
            }
            assertEquals(4096, arena.committedBytes());
            assertEquals(4080, arena.allocatedBytes());
            assertEquals(4096, charged.get());

            reject.set(true);
            assertThrows(IllegalStateException.class,
                    () -> arena.valueCell(170L, new Object()));
            assertEquals(4096, arena.committedBytes());
            assertEquals(4080, arena.allocatedBytes());
            assertEquals(4096, charged.get());

            reject.set(false);
            ActorNativeArena.ValueCell resumed = arena.valueCell(170L, new Object());
            assertEquals(0L, first.get());
            assertEquals(169L, last.get());
            assertEquals(170L, resumed.get());
            assertEquals(8192, arena.committedBytes());
            assertEquals(8192, charged.get());
        }
        assertEquals(0, charged.get());
    }

    @Test
    void nativeTaskPhaseRejectsOverflowedOrTruncated64BitCodes() {
        for (long code : new long[] {-1L, 6L, Integer.MAX_VALUE,
                                     0x1_0000_0001L, Long.MAX_VALUE}) {
            assertThrows(IllegalStateException.class,
                    () -> ActorNativeArena.TaskPhase.fromCode(code));
        }
        for (int i = 0; i < ActorNativeArena.TaskPhase.values().length; ++i) {
            assertEquals(ActorNativeArena.TaskPhase.values()[i],
                    ActorNativeArena.TaskPhase.fromCode(i));
        }
    }

    @Test
    void nativeFramePhaseLookupHandlesLongAlternatingAwaitAndCooperateSequences() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = arena(4096, charged)) {
            var frame = arena.taskFrame();
            assertEquals(ActorNativeArena.TaskPhase.NEW, frame.phase());
            frame.beginInitialResume();
            for (int iteration = 0; iteration < 2048; iteration++) {
                boolean awaiting = (iteration & 1) == 0;
                var kind = awaiting ? ActorNativeArena.SuspensionKind.AWAIT
                                    : ActorNativeArena.SuspensionKind.COOPERATE;
                long ticket = awaiting ? frame.awaiting() : frame.cooperating();
                assertEquals(awaiting ? ActorNativeArena.TaskPhase.AWAITING
                                      : ActorNativeArena.TaskPhase.COOPERATING,
                             frame.phase());
                frame.beginResume(kind, ticket);
                assertEquals(ActorNativeArena.TaskPhase.RUNNING, frame.phase());
                assertEquals(iteration + 2L, frame.resumeCount());
            }
            frame.done();
            assertEquals(ActorNativeArena.TaskPhase.DONE, frame.phase());

            var faulted = arena.taskFrame();
            faulted.failed();
            assertEquals(ActorNativeArena.TaskPhase.FAILED, faulted.phase());
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void nativeObjectsRegionsAndLinearPagesShareOneNonRecycledActorOffsetSpace() {
        AtomicLong charged = new AtomicLong();
        Object marker = new Object();
        try (ActorNativeArena arena = arena(128L * 1024, charged)) {
            Map<String, Object> fields = arena.fields(List.of("left", "right"), marker);
            long fieldsBase = ((ActorNativeArena.NativeValue) fields).nativeOffset();

            var region = arena.region(32);
            long regionBase = region.nativeOffset();

            ActorLinearMemory memory = arena.linearMemory(1, 1);
            long pageBase = memory.actorOffset(0);

            assertTrue(fieldsBase >= 0);
            assertTrue(regionBase >= 0);
            assertTrue(pageBase >= 0);
            assertNotEquals(fieldsBase, regionBase);
            assertNotEquals(fieldsBase, pageBase);
            assertNotEquals(regionBase, pageBase);

            // Native object references now carry the canonical actor-relative
            // offset instead of a Java-side reference-table ordinal.
            Map<String, Object> child = arena.fields(List.of("value"), marker);
            fields.put("left", child);
            assertSame(child, fields.get("left"));

            long retired = region.nativeOffset();
            region.close();
            var replacement = arena.region(32);
            assertNotEquals(retired, replacement.nativeOffset(),
                    "actor-relative addresses must not ABA-recycle within one actor lifetime");
        }
        assertEquals(0, charged.get());
    }

    @Test
    void everyActorKindHasDistinctStorageAndFreesItAfterTheLastTurn() throws Exception {
        for (var kind : ActorRuntime.ActorKind.values()) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                var arena = new AtomicReference<ActorNativeArena>();
                var fields = new AtomicReference<Map<String, Object>>();
                var local = new AtomicReference<ActorNativeArena.ValueCell>();
                var failure = new AtomicReference<Throwable>();
                var finished = new CountDownLatch(1);
                var actor = runtime.<Integer>spawn(kind, runtime.policyCeiling(), () -> (message, turn) -> {
                    try {
                        var current = ActorRuntime.currentNativeArena();
                        if (message == 1) {
                            arena.set(current);
                            fields.set(current.fields(List.of("count"), new Object()));
                            fields.get().put("count", 41L);
                            local.set(current.valueCell(41L, new Object()));
                        } else {
                            assertSame(arena.get(), current);
                            fields.get().put("count", (long) fields.get().get("count") + 1);
                            assertEquals(42L, fields.get().get("count"));
                            assertEquals(41L, local.get().get());
                            assertTrue(local.get().trySet(42L));
                            assertEquals(42L, local.get().get());
                            finished.countDown();
                        }
                    } catch (Throwable problem) {
                        failure.set(problem);
                        finished.countDown();
                    }
                });
                actor.send(1);
                actor.send(2);
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                assertThrows(SecurityException.class, () -> fields.get().get("count"));
                assertThrows(SecurityException.class, () -> local.get().get());
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(arena.get().closed());
                assertThrows(IllegalStateException.class, () -> local.get().get());
                assertEquals(0, arena.get().committedBytes());
                assertEquals(0, runtime.actorMemoryBytes());
            }
        }
    }

    @Test
    void injectedOwnerGateRejectsOffTurnNativeStateWithoutMutation() {
        AtomicBoolean admitted = new AtomicBoolean(true);
        AtomicLong charged = new AtomicLong();
        Object uninitialized = new Object();
        // Simulated owner-gate denial. True runtime suspension and scheduling
        // still require separate guest/ActorRuntime execution evidence.
        try (ActorNativeArena arena = new ActorNativeArena(16 * 1024,
                () -> {
                    if (!admitted.get()) {
                        throw new SecurityException("actor execution lane is idle");
                    }
                },
                bytes -> {
                    charged.addAndGet(bytes);
                    return () -> charged.addAndGet(-bytes);
                })) {
            Map<String, Object> state = arena.fields(List.of("count"), uninitialized);
            state.put("count", 13L);
            ActorNativeArena.Region region = arena.region(32);
            region.writeLong(0, 99L);
            long bytesBeforeSuspension = charged.get();

            admitted.set(false);
            assertThrows(SecurityException.class, () -> state.get("count"));
            assertThrows(SecurityException.class, () -> state.put("count", 66L));
            assertThrows(SecurityException.class, () -> region.readLong(0));
            assertThrows(SecurityException.class, () -> region.writeLong(0, 100L));
            assertThrows(SecurityException.class, () -> arena.region(8));
            assertEquals(bytesBeforeSuspension, charged.get(),
                    "denied off-lane operations must not change native memory charge");

            admitted.set(true);
            assertEquals(13L, state.get("count"),
                    "next physical turn must not see off-lane state mutation");
            assertEquals(99L, region.readLong(0));
            region.close();
        }
        assertEquals(0L, charged.get(),
                "physical arena close must refund all native memory");
    }

    private static ActorNativeArena arena(long limit, AtomicLong charged) {
        return new ActorNativeArena(limit, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
    }
}

