package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native page reuse is not a guest language parity proof; these tests also
 * exercise the actual ActorRuntime mailbox boundary and all four DOP layouts.
 */
final class ActorMessageScratchTest {

    @Test
    @Timeout(20)
    void hundredThousandMessageEpochsReuseBoundedNativeCapacity() {
        AtomicLong charged = new AtomicLong();
        ActorNativeArena arena = new ActorNativeArena(16_384, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        });
        ActorMessageScratch scratch = new ActorMessageScratch(arena, 16_384);
        ActorMessageScratch.Slice old = null;
        try {
            for (int i = 0; i < 100_000; i++) {
                scratch.beginMessage();
                var temporary = scratch.allocate(16);
                assertEquals(0L, temporary.readLong(0), "reused bytes must be zeroed");
                temporary.writeLong(0, i);
                temporary.writeLong(8, ~((long) i));
                assertEquals(i, temporary.readLong(0));
                assertEquals(~((long) i), temporary.readLong(8));
                if (old != null) {
                    ActorMessageScratch.Slice stale = old;
                    assertThrows(IllegalStateException.class, () -> stale.readLong(0));
                }
                old = temporary;
                assertEquals(16, scratch.usedBytes());
                scratch.finishMessage();
                assertEquals(4096, scratch.reservedBytes(), "one native page should service all epochs");
                assertThrows(IllegalStateException.class, () -> temporary.readLong(0));
            }
            assertEquals(4096, charged.get());
        } finally {
            arena.close();
        }
        assertEquals(0, charged.get(), "physical native bytes refunded only at actor end");
    }

    @Test
    void checkedReadBorrowsPinTheEpochAndRejectConcurrentWrites() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(8192, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 8192);
            scratch.beginMessage();
            var first = scratch.allocate(16);
            first.writeLong(0, 0x1122334455667788L);
            var borrow = first.borrowReadOnly();
            assertEquals(0x1122334455667788L, borrow.readLong(0));
            assertThrows(IndexOutOfBoundsException.class, () -> borrow.readLong(9));
            assertThrows(IllegalStateException.class,
                    () -> first.writeLong(0, 7),
                    "native memory cannot be modified under an immutable borrow");
            assertThrows(IllegalStateException.class,
                    scratch::finishMessage,
                    "the entire logical handler remains live while a read borrow exists");
            assertEquals(0x1122334455667788L, borrow.readLong(0),
                    "failed epoch closure cannot invalidate a live borrow");
            borrow.close();
            borrow.close(); // idempotent release must not underflow the pin count
            assertThrows(IllegalStateException.class, () -> borrow.readByte(0));
            first.writeLong(0, 0x6A6B6C6D6E6F7071L);
            scratch.finishMessage();
            assertThrows(IllegalStateException.class, () -> first.readLong(0));
            scratch.beginMessage();
            var second = scratch.allocate(16);
            assertEquals(0L, second.readLong(0),
                    "completion must scrub the old native allocation before reuse");
            assertEquals(4096L, scratch.reservedBytes());
            scratch.finishMessage();
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void readBorrowCannotBeReleasedByForeignActorOwner() throws Exception {
        AtomicLong charged = new AtomicLong();
        AtomicReference<Thread> owner = new AtomicReference<>(Thread.currentThread());
        try (ActorNativeArena arena = new ActorNativeArena(8192, () -> {
            if (Thread.currentThread() != owner.get()) {
                throw new SecurityException("wrong message owner");
            }
        }, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 8192);
            scratch.beginMessage();
            var slice = scratch.allocate(16);
            slice.writeLong(0, 99L);
            var borrow = slice.borrowReadOnly();
            AtomicReference<Throwable> stolenRelease = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try {
                    borrow.close();
                } catch (Throwable error) {
                    stolenRelease.set(error);
                }
            });
            foreign.start();
            foreign.join(3000);
            assertFalse(foreign.isAlive());
            assertInstanceOf(SecurityException.class, stolenRelease.get());
            assertThrows(IllegalStateException.class, scratch::finishMessage,
                    "failed foreign close must not silently unpin the native region");
            assertEquals(99L, borrow.readLong(0));
            borrow.close();
            scratch.finishMessage();
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void burstMessageReturnsExcessNativeQuotaWithoutTouchingPersistentState() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(256 * 1024, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            var persistent = arena.region(8192);
            persistent.writeLong(0, 0x0102030405060708L);
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 256 * 1024);
            scratch.beginMessage();
            ActorMessageScratch.Slice stale = null;
            for (int i = 0; i < 24; i++) {
                stale = scratch.allocate(4096);
                stale.writeLong(0, 10_000L + i);
            }
            assertEquals(24L * 4096, scratch.reservedBytes());
            assertEquals(8192L + scratch.reservedBytes(), charged.get());
            ActorMessageScratch.Slice escaped = stale;
            scratch.finishMessage();
            assertEquals(64 * 1024L, scratch.reservedBytes(),
                    "a single large temporary message must not pin every native chunk");
            assertEquals(8192L + 64 * 1024L, charged.get());
            assertEquals(0x0102030405060708L, persistent.readLong(0));
            assertThrows(IllegalStateException.class, () -> escaped.readLong(0),
                    "a handle to a now-freed excess chunk must fail by message epoch first");
            scratch.beginMessage();
            assertThrows(IllegalStateException.class, () -> scratch.trimIdleTo(0));
            var reused = scratch.allocate(4096);
            assertEquals(0L, reused.readLong(0));
            scratch.finishMessage();
            assertThrows(IllegalArgumentException.class, () -> scratch.trimIdleTo(-1));
            scratch.trimIdleTo(0);
            assertEquals(0L, scratch.reservedBytes());
            assertEquals(8192L, charged.get());
            assertEquals(0x0102030405060708L, persistent.readLong(0));
            persistent.close();
        }
        assertEquals(0L, charged.get(), "all actor-native bytes must be refunded at close");
    }

    @Test
    void oversizedSingleScratchChunkIsReleasedAfterMessageEnds() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(256 * 1024, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 256 * 1024);
            scratch.beginMessage();
            var large = scratch.allocate(100 * 1024);
            large.writeLong(0, 123L);
            assertEquals(100 * 1024L, scratch.reservedBytes());
            scratch.finishMessage();
            assertEquals(0L, scratch.reservedBytes(),
                    "oversized one-time native scratch chunk must not remain permanently charged");
            assertEquals(0L, charged.get());
            assertThrows(IllegalStateException.class, () -> large.readLong(0));
            scratch.beginMessage();
            var small = scratch.allocate(8);
            assertEquals(0L, small.readLong(0));
            assertEquals(4096L, scratch.reservedBytes());
            scratch.finishMessage();
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void nestedLexicalScopesReclaimWithinMessageWithoutInvalidatingOuterValues() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(16384, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 16384);
            scratch.beginMessage();
            var outer = scratch.allocate(16);
            outer.writeLong(0, 111);
            ActorMessageScratch.Slice staleChild;
            ActorMessageScratch.Slice staleGrandchild;
            try (var child = scratch.enterScope()) {
                var inner = scratch.allocate(24);
                inner.writeLong(0, 222);
                staleChild = inner;
                try (var grandchild = scratch.enterScope()) {
                    var leaf = scratch.allocate(16);
                    leaf.writeLong(0, 333);
                    staleGrandchild = leaf;
                    assertEquals(56L, scratch.usedBytes());
                }
                assertThrows(IllegalStateException.class, () -> staleGrandchild.readLong(0));
                assertEquals(222L, staleChild.readLong(0));
                assertEquals(111L, outer.readLong(0));
                assertEquals(40L, scratch.usedBytes(),
                        "closing a lexical child must restore the outer allocation cursor");
                var reused = scratch.allocate(16);
                assertEquals(0L, reused.readLong(0),
                        "reusing a nested-scope span cannot reveal old content");
                reused.writeLong(0, 444);
                assertThrows(IllegalStateException.class,
                        () -> staleGrandchild.writeLong(0, 999),
                        "a revoked grandchild must not overwrite a later sibling allocation");
                assertEquals(444L, reused.readLong(0));
            }
            assertEquals(16L, scratch.usedBytes());
            assertEquals(111L, outer.readLong(0));
            assertThrows(IllegalStateException.class, () -> staleChild.readLong(0));
            try (var sibling = scratch.enterScope()) {
                var newValue = scratch.allocate(24);
                assertEquals(0L, newValue.readLong(0));
                newValue.writeLong(0, 555);
                assertThrows(IllegalStateException.class,
                        () -> staleChild.writeLong(0, 999),
                        "recycled native address must not resurrect a closed scope token");
                assertEquals(555L, newValue.readLong(0));
            }
            assertEquals(16L, scratch.usedBytes());
            assertEquals(4096L, scratch.reservedBytes());
            scratch.finishMessage();
            assertThrows(IllegalStateException.class, () -> outer.readLong(0));
            scratch.beginMessage();
            var next = scratch.allocate(16);
            assertEquals(0L, next.readLong(0));
            assertThrows(IllegalStateException.class, () -> outer.writeLong(0, 7),
                    "reusing the root Scope object must not revive a previous epoch slice");
            scratch.finishMessage();
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void lexicalScopesRejectOutOfOrderCloseAndLiveNativeReadBorrow() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(8192, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 8192);
            scratch.beginMessage();
            var parent = scratch.enterScope();
            var parentSlice = scratch.allocate(8);
            parentSlice.writeLong(0, 77);
            var child = scratch.enterScope();
            var childSlice = scratch.allocate(8);
            childSlice.writeLong(0, 88);
            assertThrows(IllegalStateException.class, parent::close);
            assertThrows(IllegalStateException.class, scratch::finishMessage);
            var read = childSlice.borrowReadOnly();
            assertThrows(IllegalStateException.class, child::close,
                    "nested scope cannot return bytes while its native borrow is live");
            assertEquals(88L, read.readLong(0));
            assertEquals(77L, parentSlice.readLong(0));
            read.close();
            child.close();
            assertThrows(IllegalStateException.class, () -> childSlice.readLong(0));
            assertThrows(IllegalStateException.class, child::close,
                    "scope double-close must not rewind a later allocation");
            assertEquals(77L, parentSlice.readLong(0));
            parent.close();
            assertThrows(IllegalStateException.class, () -> parentSlice.readLong(0));
            scratch.finishMessage();
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void lexicalScratchScopeCannotBeClosedByForeignPhysicalOwner() throws Exception {
        AtomicLong charged = new AtomicLong();
        AtomicReference<Thread> owner = new AtomicReference<>(Thread.currentThread());
        try (ActorNativeArena arena = new ActorNativeArena(8192, () -> {
            if (Thread.currentThread() != owner.get()) {
                throw new SecurityException("foreign physical actor owner");
            }
        }, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 8192);
            scratch.beginMessage();
            var scoped = scratch.enterScope();
            var value = scratch.allocate(8);
            value.writeLong(0, 1234L);
            AtomicReference<Throwable> failedClose = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try { scoped.close(); }
                catch (Throwable error) { failedClose.set(error); }
            });
            foreign.start();
            foreign.join(3000);
            assertFalse(foreign.isAlive());
            assertInstanceOf(SecurityException.class, failedClose.get());
            assertEquals(1234L, value.readLong(0),
                    "foreign close must not advance cursor or revoke the real owner");
            assertThrows(IllegalStateException.class, scratch::finishMessage);
            scoped.close();
            assertThrows(IllegalStateException.class, () -> value.readLong(0));
            scratch.finishMessage();
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void multiBlockScopeRewindClearsOnlyScopedBytesAndPreservesActorPersistentRegion() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(64 * 1024, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            var persistent = arena.region(16);
            persistent.writeLong(0, 0x1122334455667788L);
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 64 * 1024);
            scratch.beginMessage();
            var root = scratch.allocate(8);
            root.writeLong(0, 0x1111);
            ActorMessageScratch.Slice stale;
            try (var scope = scratch.enterScope()) {
                var first = scratch.allocate(4000);
                first.writeLong(0, 0x2222);
                var second = scratch.allocate(4000);
                second.writeLong(0, 0x3333);
                stale = second;
                assertEquals(8008L, scratch.usedBytes());
            }
            assertThrows(IllegalStateException.class, () -> stale.readLong(0));
            assertEquals(8L, scratch.usedBytes());
            assertEquals(0x1111L, root.readLong(0));
            assertEquals(0x1122334455667788L, persistent.readLong(0));
            try (var sibling = scratch.enterScope()) {
                var replacement = scratch.allocate(4000);
                assertEquals(0L, replacement.readLong(0));
                replacement.writeLong(0, 0x4444);
                assertThrows(IllegalStateException.class, () -> stale.writeLong(0, 999));
                assertEquals(0x4444L, replacement.readLong(0));
            }
            scratch.finishMessage();
            assertEquals(0x1122334455667788L, persistent.readLong(0));
            persistent.close();
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void lexicalScopeAutomaticallyRewindsAfterExceptionalUnwind() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(8192, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 8192);
            scratch.beginMessage();
            var root = scratch.allocate(8);
            root.writeLong(0, 999);
            AtomicReference<ActorMessageScratch.Slice> leaked = new AtomicReference<>();
            assertThrows(IllegalArgumentException.class, () -> {
                try (var scope = scratch.enterScope()) {
                    leaked.set(scratch.allocate(128));
                    leaked.get().writeLong(0, 123);
                    throw new IllegalArgumentException("guest computation rejected input");
                }
            });
            assertEquals(8L, scratch.usedBytes());
            assertEquals(999L, root.readLong(0));
            assertThrows(IllegalStateException.class, () -> leaked.get().readLong(0));
            try (var sibling = scratch.enterScope()) {
                assertEquals(0L, scratch.allocate(128).readLong(0));
            }
            scratch.finishMessage();
        }
        assertEquals(0L, charged.get());
    }

    @Test
    void invalidOffsetsAndEpochsFailBeforeNativeAccess() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(8192, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 8192);
            assertThrows(IllegalStateException.class, () -> scratch.allocate(8));
            scratch.beginMessage();
            assertThrows(IllegalStateException.class, scratch::beginMessage);
            assertThrows(IllegalArgumentException.class, () -> scratch.allocate(0));
            assertThrows(IllegalArgumentException.class, () -> scratch.allocate(16, 3));
            assertThrows(IllegalArgumentException.class, () -> scratch.allocate(Integer.MAX_VALUE, 16));
            var first = scratch.allocate(16);
            assertThrows(IndexOutOfBoundsException.class, () -> first.readLong(9));
            assertThrows(IndexOutOfBoundsException.class, () -> first.writeByte(-1, (byte) 1));
            first.writeDouble(0, 5.5);
            assertEquals(5.5, first.readDouble(0));
            scratch.finishMessage();
            assertThrows(IllegalStateException.class, scratch::finishMessage);
            scratch.beginMessage();
            var reused = scratch.allocate(16);
            assertEquals(0, reused.readDouble(0), "old native double bytes must not leak");
            assertThrows(IllegalStateException.class, () -> first.readDouble(0));
            scratch.finishMessage();
        }
        assertEquals(0, charged.get());
    }

    @Test
    void scratchUsesRemainingBudgetWithoutStealingPersistentStorage() {
        AtomicLong charged = new AtomicLong();
        try (ActorNativeArena arena = new ActorNativeArena(8192, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            // Simulate a long-lived actor field/SoA allocation before the
            // first scratch message. We must not require a whole 4096B page.
            var persistent = arena.region(8000);
            persistent.writeLong(0, 0x1122334455667788L);
            ActorMessageScratch scratch = new ActorMessageScratch(arena, 8192);
            scratch.beginMessage();
            var small = scratch.allocate(16);
            small.writeLong(0, 42);
            assertEquals(42L, small.readLong(0));
            assertEquals(192, scratch.reservedBytes());
            assertEquals(0x1122334455667788L, persistent.readLong(0));
            assertThrows(IllegalStateException.class, () -> scratch.allocate(193));
            scratch.finishMessage();
            scratch.beginMessage();
            assertEquals(0, scratch.allocate(16).readLong(0));
            scratch.finishMessage();
            persistent.close();
        }
        assertEquals(0, charged.get());
    }

    @Test
    @Timeout(15)
    void actorRefusesLogicalMessageFinishWhileBorrowedThenSafelyReusesScratch()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch completed = new CountDownLatch(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorMessageScratch.Slice> previous = new AtomicReference<>();
            AtomicLong processed = new AtomicLong();
            var actor = runtime.<Integer>spawnSharedTrusted(ctx -> (message, turn) -> {
                try {
                    var scratch = ActorRuntime.currentMessageScratch();
                    var local = scratch.allocate(8);
                    if (message == 1) {
                        local.writeLong(0, 1234L);
                        var borrow = local.borrowReadOnly();
                        assertThrows(IllegalStateException.class, scratch::finishMessage,
                                "the owner must not end its receive with an issued native lease");
                        assertEquals(1234L, borrow.readLong(0));
                        // Compiler-generated drops must close this before return.
                        borrow.close();
                        previous.set(local);
                    } else {
                        assertThrows(IllegalStateException.class,
                                () -> previous.get().readLong(0),
                                "the prior message's old reference cannot be revived");
                        assertEquals(0L, local.readLong(0));
                    }
                    processed.incrementAndGet();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    completed.countDown();
                }
            });
            actor.send(1);
            actor.send(2);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(2L, processed.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0L, runtime.actorMemoryBytes());
        }
    }

    @Test
    @Timeout(15)
    void actorScratchResetsButEachPersistentDopLayoutSurvivesAcrossMessages() throws Exception {
        for (ActorShardColumns.Layout layout : ActorShardColumns.Layout.values()) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                CountDownLatch completed = new CountDownLatch(2);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<ActorMessageScratch.Slice> previous = new AtomicReference<>();
                AtomicReference<ActorShardColumns> persistent = new AtomicReference<>();
                AtomicLong firstCapacity = new AtomicLong();

                ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {
                    try {
                        ActorMessageScratch scratch = ActorRuntime.currentMessageScratch();
                        assertTrue(scratch.usedBytes() == 0);
                        var tmp = scratch.allocate(16);
                        assertEquals(0L, tmp.readLong(0));
                        tmp.writeLong(0, message);
                        if (message == 1) {
                            var shard = new ActorShardColumns(layout, 32, 4,
                                    layout == ActorShardColumns.Layout.AOSOA ? 8 : 1,
                                    layout == ActorShardColumns.Layout.SOAOS ? 2 : 1);
                            for (int row = 0; row < 32; row++) {
                                shard.write(row, 0, row);
                                shard.write(row, 1, 2 * row);
                                shard.write(row, 2, row + 1);
                                shard.write(row, 3, 7);
                            }
                            persistent.set(shard);
                            previous.set(tmp);
                            firstCapacity.set(scratch.reservedBytes());
                        } else {
                            assertThrows(IllegalStateException.class, () -> previous.get().readLong(0));
                            assertEquals(firstCapacity.get(), scratch.reservedBytes());
                            ActorShardColumns shard = persistent.get();
                            assertNotNull(shard);
                            for (int row = 0; row < 32; row++) {
                                assertEquals(row, shard.read(row, 0));
                                assertEquals(2 * row, shard.read(row, 1));
                                assertEquals(row + 1, shard.read(row, 2));
                                assertEquals(7, shard.read(row, 3));
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        completed.countDown();
                    }
                });
                actor.send(1);
                actor.send(2);
                assertTrue(completed.await(8, TimeUnit.SECONDS), "both unary messages must run");
                assertNull(failure.get(), "layout " + layout + " must preserve persistent state");
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(persistent.get().nativeBackingReleased());
            }
        }
    }

    @Test
    @Timeout(120)
    void hundredThousandUnaryActorMessagesRetainStateAndBoundScratchCapacity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicLong processed = new AtomicLong();
            AtomicLong firstScratchCapacity = new AtomicLong();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<CountDownLatch> batchComplete = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(ctx -> {
                final long[] state = {0};
                return (message, turn) -> {
                    try {
                        ActorMessageScratch scratch = ActorRuntime.currentMessageScratch();
                        if (scratch.usedBytes() != 0) {
                            throw new AssertionError("scratch cursor leaked across messages");
                        }
                        var temp = scratch.allocate(8);
                        if (temp.readLong(0) != 0) {
                            throw new AssertionError("new message observed stale scratch contents");
                        }
                        temp.writeLong(0, message);
                        state[0]++;
                        if (state[0] != message) {
                            throw new AssertionError("unary actor reordered or lost a message");
                        }
                        long capacity = scratch.reservedBytes();
                        if (firstScratchCapacity.compareAndSet(0, capacity)) {
                            assertEquals(4096, capacity);
                        } else {
                            assertEquals(firstScratchCapacity.get(), capacity);
                        }
                        processed.incrementAndGet();
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        CountDownLatch done = batchComplete.get();
                        if (done != null) done.countDown();
                    }
                };
            });

            // Bounded mail in batches: this does not need a 100,000-envelope
            // mailbox and cannot silently mask backpressure/overflow.
            for (int batch = 0; batch < 100; batch++) {
                CountDownLatch done = new CountDownLatch(1000);
                batchComplete.set(done);
                for (int index = 1; index <= 1000; index++) {
                    actor.send(batch * 1000 + index);
                }
                assertTrue(done.await(20, TimeUnit.SECONDS),
                        "one thousand admitted actor messages must finish");
                assertNull(failure.get(), "native scratch invariant failed during actor execution");
            }
            assertEquals(100_000, processed.get());
            assertEquals(4096, firstScratchCapacity.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorMemoryBytes());
        }
    }

    @Test
    @Timeout(20)
    void actorOwnedTaskScopeRejectsTaskInjectionFromExternalHostThread()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch published = new CountDownLatch(1);
            AtomicReference<TaskScope> leakedScope = new AtomicReference<>();
            var actor = runtime.<Integer>spawnSharedTrusted(ctx -> (message, turn) -> {
                leakedScope.set(runtime.newActorTaskScope());
                published.countDown();
            });
            actor.send(1);
            assertTrue(published.await(8, TimeUnit.SECONDS));
            TaskScope scope = leakedScope.get();
            assertNotNull(scope);
            assertThrows(SecurityException.class,
                    () -> scope.startTask(resume -> OresScheduler.done(123)),
                    "host code outside the owning actor must not inject Tasks");
            actor.stop();
            assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS),
                    "failed injection must not strand the real actor-owned scope");
            assertEquals(0L, runtime.actorMemoryBytes());
        }
    }

    @Test
    @Timeout(25)
    void actorOwnedTaskScopePinsNativeHeapUntilChildPhysicallyRetires() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childEntered = new CountDownLatch(1);
            CountDownLatch releaseChild = new CountDownLatch(1);
            AtomicReference<TaskScope> ownedScope = new AtomicReference<>();
            var actor = runtime.<Integer>spawnSharedTrusted(ctx -> (message, turn) -> {
                TaskScope scope = runtime.newActorTaskScope();
                ownedScope.set(scope);
                scope.startTask(resume -> {
                    childEntered.countDown();
                    if (!releaseChild.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("child scope carrier unexpectedly timed out");
                    }
                    return OresScheduler.done(message);
                });
            });
            try {
                actor.send(1);
                assertTrue(childEntered.await(8, TimeUnit.SECONDS));
                actor.stop();
                assertFalse(actor.done().isDone(),
                        "actor.done must wait for source TaskScope physical descendants");
                assertFalse(actor.awaitTermination(75, TimeUnit.MILLISECONDS));
                assertFalse(ownedScope.get().getDone().isDone(),
                        "scope cannot join when child is still on its guest carrier");
            } finally {
                releaseChild.countDown();
            }
            assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS));
            assertTrue(ownedScope.get().getDone().isDone());
            assertEquals(0L, runtime.actorMemoryBytes(),
                    "actor heap must be refunded only after scoped child retirement");
        }
    }

    @Test
    @Timeout(25)
    void actorStopNeverWaitsForBlockedScopedTaskFutureSubscriber() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<Integer> producer = new OresFuture<>();
            CountDownLatch waiting = new CountDownLatch(1);
            CountDownLatch subscriberEntered = new CountDownLatch(1);
            CountDownLatch releaseSubscriber = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnSharedTrusted(ctx -> (message, turn) -> {
                TaskScope scope = runtime.newActorTaskScope();
                var child = scope.startTask(resume -> {
                    waiting.countDown();
                    return OresScheduler.await(producer);
                });
                child.result().whenCompleteRuntime((value, failure) -> {
                    subscriberEntered.countDown();
                    try {
                        releaseSubscriber.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            });
            Thread stopThread = new Thread(actor::stop, "scoped-actor-stop-probe");
            try {
                actor.send(1);
                assertTrue(waiting.await(8, TimeUnit.SECONDS));
                stopThread.start();
                stopThread.join(1000);
                assertFalse(stopThread.isAlive(),
                        "actor.stop must return even if a child Future subscriber blocks");
                assertTrue(subscriberEntered.await(5, TimeUnit.SECONDS));
            } finally {
                releaseSubscriber.countDown();
                if (stopThread.isAlive()) stopThread.join(5000);
            }
            assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS));
            assertEquals(0L, runtime.actorMemoryBytes());
            assertFalse(producer.isDone());
        }
    }

    @Test
    @Timeout(25)
    void actorStopCancelsSuspendedTaskScopeChildWithoutCancellingSharedProducer()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<Integer> producer = new OresFuture<>();
            CountDownLatch childEntered = new CountDownLatch(1);
            AtomicReference<TaskScope> ownedScope = new AtomicReference<>();
            var actor = runtime.<Integer>spawnSharedTrusted(ctx -> (message, turn) -> {
                TaskScope scope = runtime.newActorTaskScope();
                ownedScope.set(scope);
                scope.startTask(resume -> {
                    childEntered.countDown();
                    return OresScheduler.await(producer);
                });
            });
            actor.send(1);
            assertTrue(childEntered.await(8, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS));
            assertTrue(ownedScope.get().getDone().isDone());
            assertFalse(producer.isDone(),
                    "closing a child TaskScope must never cancel its shared producer");
            assertEquals(0, producer.pendingRuntimeWaiterCount());
            assertEquals(0L, runtime.actorMemoryBytes());
        }
    }

    @Test
    @Timeout(25)
    void stoppedActorDoesNotReleaseMemoryWhileSourceTaskStillRunsOnItsCarrier()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch sourceEntered = new CountDownLatch(1);
            CountDownLatch releaseSource = new CountDownLatch(1);
            AtomicReference<Throwable> guestFailure = new AtomicReference<>();
            var actor = runtime.<Integer>spawnSharedTrusted(ctx -> (message, turn) -> {
                runtime.startActorTask(resume -> {
                    sourceEntered.countDown();
                    if (!releaseSource.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out before source Task was released");
                    }
                    return OresScheduler.done(1);
                });
            });
            try {
                actor.send(1);
                assertTrue(sourceEntered.await(8, TimeUnit.SECONDS),
                        "source Task must be physically executing before stop");
                actor.stop();
                assertFalse(actor.done().isDone(),
                        "actor done cannot publish while the source carrier still executes");
                assertFalse(actor.awaitTermination(75, TimeUnit.MILLISECONDS),
                        "physical actor completion cannot precede carrier retirement");
            } finally {
                releaseSource.countDown();
            }
            assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
            assertEquals(0L, runtime.actorMemoryBytes(),
                    "native memory refunds must follow source Task carrier retirement");
            assertNull(guestFailure.get());
        }
    }

    @Test
    @Timeout(25)
    void stoppingActorWithSuspendedSourceTaskCancelsAwaitAndCompletesPhysicalRetirement()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<Integer> producer = new OresFuture<>();
            CountDownLatch taskSuspended = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var actor = runtime.<Integer>spawnSharedTrusted(ctx -> (message, turn) -> {
                runtime.startActorTask(resume -> {
                    taskSuspended.countDown();
                    return OresScheduler.await(producer);
                });
            });
            actor.send(1);
            assertTrue(taskSuspended.await(8, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(8, TimeUnit.SECONDS),
                    "stopped suspended Task must not strand actor physical retirement");
            assertTrue(actor.done().isDone());
            assertFalse(producer.isDone(),
                    "cancelling a waiting source Task must not cancel its producer");
            assertEquals(0, producer.pendingRuntimeWaiterCount(),
                    "scheduler close must detach the abandoned async continuation");
            assertEquals(0L, runtime.actorMemoryBytes());
        }
    }

    @Test
    @Timeout(15)
    void suspendedSourceReceiveKeepsScratchUntilFutureCompletion() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresFuture<Void> gate = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorMessageScratch.Slice> firstSlice = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {
                try {
                    ActorMessageScratch scratch = ActorRuntime.currentMessageScratch();
                    if (message == 1) {
                        firstSlice.set(scratch.allocate(8));
                        firstSlice.get().writeLong(0, 1234);
                        runtime.suspendCurrentUserMailboxUntil(gate);
                        suspended.countDown();
                    } else {
                        assertThrows(IllegalStateException.class, () -> firstSlice.get().readLong(0));
                        assertEquals(4096, scratch.reservedBytes());
                        finished.countDown();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    suspended.countDown();
                    finished.countDown();
                }
            });
            actor.send(1);
            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.send(2);
            assertFalse(finished.await(75, TimeUnit.MILLISECONDS),
                    "later mail cannot execute while the source receive is suspended");
            assertTrue(gate.completeFromRuntime(null));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
