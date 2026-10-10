package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Typed actor object layouts must remain native-backed across turns. */
final class ActorNativeI64RecordTest {
    @Test
    void sharedActorKeepsSourceFieldLayoutAcrossMailboxTurns() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        AtomicReference<ActorNativeI64Record> record = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    if ("init".equals(mail)) {
                        var object = ctx.actorOwnedMemory().orElseThrow()
                                .newNativeI64Record("Counter", List.of(
                                        new ActorNativeI64Record.Field("base", 40),
                                        new ActorNativeI64Record.Field("delta", 2)));
                        record.set(object);
                        assertEquals(List.of("base", "delta"), object.names());
                        assertEquals(2, object.fieldCount());
                        created.countDown();
                    } else {
                        assertEquals(42, record.get().read("base")
                                + record.get().read("delta"));
                        observed.countDown();
                    }
                } catch (Throwable e) {
                    problem.compareAndSet(null, e);
                    created.countDown();
                    observed.countDown();
                }
            });
            actor.send("init");
            assertTrue(created.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
            actor.send("read");
            assertTrue(observed.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
        }

        assertTrue(record.get().closed());
        assertTrue(record.get().physicallyClosed());
        assertThrows(IllegalStateException.class, () -> record.get().read("base"));
    }

    @Test
    void rejectsInvalidFieldsBeforeNativeAllocationAndBlocksForeignAccess() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        AtomicReference<ActorNativeI64Record> owned = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var memory = ctx.actorOwnedMemory().orElseThrow();
                    long before = memory.usedBytes();
                    assertThrows(IllegalArgumentException.class, () ->
                            memory.newNativeI64Record("Bad", List.of(
                                    new ActorNativeI64Record.Field("x", 1),
                                    new ActorNativeI64Record.Field("x", 2))));
                    assertEquals(before, memory.usedBytes());
                    var record = memory.newNativeI64Record("Data", List.of(
                            new ActorNativeI64Record.Field("size", 21)));
                    assertEquals(before + 8, memory.usedBytes());
                    owned.set(record);
                    assertThrows(IllegalArgumentException.class,
                            () -> record.read("missing"));
                } catch (Throwable e) {
                    problem.compareAndSet(null, e);
                } finally {
                    ready.countDown();
                }
            });
            var other = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    assertThrows(IllegalStateException.class,
                            () -> owned.get().read("size"));
                } catch (Throwable e) {
                    problem.compareAndSet(null, e);
                } finally {
                    checked.countDown();
                }
            });
            owner.send("create");
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
            other.send("inspect");
            assertTrue(checked.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
        }
        assertTrue(owned.get().physicallyClosed());
    }

    @Test
    void nativeFieldLoanSurvivesShutdownWithoutAllowingMemoryResurrection() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        AtomicReference<ActorNativeI64Record> object = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Loan> loan = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var record = ctx.actorOwnedMemory().orElseThrow()
                            .newNativeI64Record("Value", List.of(
                                    new ActorNativeI64Record.Field("result", 42)));
                    object.set(record);
                    loan.set(record.borrow("result"));
                    assertEquals(42, loan.get().read());
                } catch (Throwable e) {
                    problem.set(e);
                } finally {
                    ready.countDown();
                }
            });
            actor.send("create");
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
        }
        assertTrue(object.get().closed());
        assertFalse(object.get().physicallyClosed());
        assertThrows(IllegalStateException.class, () -> loan.get().read());
        loan.get().close();
        assertTrue(object.get().physicallyClosed());
    }

    @Test
    void statelessUntrustedActorCanUseFixedNativeRecord() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(policy -> (mail, ctx) -> {
                var record = ctx.actorOwnedMemory().orElseThrow()
                        .newNativeI64Record("Counter", List.of(
                                new ActorNativeI64Record.Field("base", 40),
                                new ActorNativeI64Record.Field("delta", 2)));
                assertEquals(42, record.read("base") + record.read("delta"));
                record.close();
                assertTrue(record.physicallyClosed());
                ctx.self().stop();
            });
            actor.send("execute");
            assertTrue(actor.awaitTermination(20, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "untrusted actor failed: " + actor.failure());
        }
    }

    @Test
    void mutableActorFieldRequiresOwnerAndUnloanedStorageAcrossTurns() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        var record = new AtomicReference<ActorNativeI64Record>();
        var loan = new AtomicReference<ExplicitNativeI64Region.Loan>();
        var failure = new AtomicReference<Throwable>();
        CountDownLatch initialized = new CountDownLatch(1);
        CountDownLatch mutated = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    if ("init".equals(mail)) {
                        var object = ctx.actorOwnedMemory().orElseThrow()
                                .newNativeI64Record("Counter", List.of(
                                        new ActorNativeI64Record.Field("base", 40),
                                        new ActorNativeI64Record.Field("count", 0, true)));
                        record.set(object);
                        assertFalse(object.mutable("base"));
                        assertTrue(object.mutable("count"));
                        assertThrows(IllegalStateException.class,
                                () -> object.write("base", 100));
                        object.write("count", 2);
                        loan.set(object.borrow("count"));
                        assertThrows(IllegalStateException.class,
                                () -> object.write("count", 3));
                        initialized.countDown();
                    } else {
                        assertEquals(2, loan.get().read());
                        assertThrows(IllegalStateException.class,
                                () -> record.get().write("count", 3));
                        loan.get().close();
                        record.get().write("count", 3);
                        assertEquals(43, record.get().read("base") + record.get().read("count"));
                        mutated.countDown();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    initialized.countDown();
                    mutated.countDown();
                }
            });
            actor.send("init");
            assertTrue(initialized.await(20, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            assertThrows(IllegalStateException.class, () -> record.get().write("count", 8));
            actor.send("mutate");
            assertTrue(mutated.await(20, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
        }
        assertTrue(record.get().physicallyClosed());
        assertThrows(IllegalStateException.class, () -> record.get().write("count", 4));
    }

}
