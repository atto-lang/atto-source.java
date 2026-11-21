package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.MessagePackCollectionCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static dev.oreslang.runtime.MessagePackCollectionCodec.*;
import static org.junit.jupiter.api.Assertions.*;

final class MessagePackActorMailboxIntegrationTest {
    @Test
    void sharedActorReceivesOwnedBinarySnapshotBeforeQueueAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema("test.Shared.Strings.v1", list(string())));
            ArrayList<String> senderOwned = new ArrayList<>(List.of("first"));
            ref.send(senderOwned);
            senderOwned.set(0, "changed");
            Object received = delivered.poll(5, TimeUnit.SECONDS);
            assertEquals(List.of("first"), received);
            assertNotSame(senderOwned, received);
            assertThrows(UnsupportedOperationException.class,
                    () -> ((List<Object>) received).add("mutated"));
        }
    }

    @Test
    void privateActorReceivesNestedTupleAndList() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnPrivateTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema("test.Private.Nested.v1",
                            tuple(bool(), list(string()))));
            ref.send(List.of(true, new ArrayList<>(List.of("a", "b"))));
            Object received = delivered.poll(5, TimeUnit.SECONDS);
            assertEquals(List.of(true, List.of("a", "b")), received);
        }
    }

    @Test
    void invalidMessageFailsBeforeAdmissionAndDoesNotPoisonMailbox() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema("test.Strict.Strings.v1", list(string())));
            assertThrows(IllegalArgumentException.class,
                    () -> ref.send(List.of("ok", new Object())));
            ref.send(List.of("after-error"));
            assertEquals(List.of("after-error"), delivered.poll(5, TimeUnit.SECONDS));
            assertNull(delivered.poll(100, TimeUnit.MILLISECONDS));
            assertThrows(IllegalStateException.class,
                    () -> runtime.bindSerializableMailbox(ref,
                            new MailboxSchema("test.Changed.v1", list(string()))));
        }
    }


    @Test
    void failedBinaryEncodingReleasesAllPrivateScratchBeforeNextAdmission()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnPrivateTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema(
                            "test.Private.Scratch.v1",
                            list(string())));

            long baseline = runtime.actorMemoryBytes();
            String chunk = "x".repeat(8_192);
            List<String> oversized =
                    java.util.Collections.nCopies(200, chunk);

            assertThrows(IllegalArgumentException.class,
                    () -> ref.send(oversized),
                    "oversized frame must fail after exercising bounded writer growth");
            assertEquals(baseline, runtime.actorMemoryBytes(),
                    "failed encoding must release every scratch reservation");

            ref.send(List.of("after-failure"));
            assertEquals(
                    List.of("after-failure"),
                    delivered.poll(5, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.actorMemoryBytes() != baseline
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(baseline, runtime.actorMemoryBytes(),
                    "retained wire/decode reservation must close with the envelope");
        }
    }


    @Test
    void failedBinaryEncodingReleasesAllSharedScratchBeforeNextAdmission()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema(
                            "test.Shared.Scratch.v1",
                            list(string())));

            long baseline = runtime.actorMemoryBytes();
            String chunk = "s".repeat(8_192);
            List<String> oversized =
                    java.util.Collections.nCopies(200, chunk);

            assertThrows(IllegalArgumentException.class,
                    () -> ref.send(oversized));
            assertEquals(baseline, runtime.actorMemoryBytes(),
                    "shared encode failure must release runtime scratch");

            ref.send(List.of("after-shared-failure"));
            assertEquals(
                    List.of("after-shared-failure"),
                    delivered.poll(5, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.actorMemoryBytes() != baseline
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(baseline, runtime.actorMemoryBytes(),
                    "shared retained wire/decode reservation must close");
        }
    }

    @Test
    void concurrentOversizedBinarySendsCannotLeakAggregateScratch()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnPrivateTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema(
                            "test.Private.ConcurrentScratch.v1",
                            list(string())));

            long baseline = runtime.actorMemoryBytes();
            String chunk = "y".repeat(8_192);
            List<String> oversized =
                    java.util.Collections.nCopies(200, chunk);
            var failures =
                    new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
                var start = new java.util.concurrent.CountDownLatch(1);
                var done = new java.util.concurrent.CountDownLatch(8);
                for (int i = 0; i < 8; i++) {
                    pool.execute(() -> {
                        try {
                            assertTrue(start.await(5, TimeUnit.SECONDS));
                            ref.send(oversized);
                            failures.add(new AssertionError(
                                    "oversized binary send unexpectedly succeeded"));
                        } catch (IllegalArgumentException
                                | IllegalStateException expected) {
                            // Either codec byte quota or concurrent actor/runtime
                            // scratch quota may reject first; both must unwind.
                        } catch (Throwable unexpected) {
                            failures.add(unexpected);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(10, TimeUnit.SECONDS));
            }

            assertTrue(failures.isEmpty(), () -> "unexpected sender failure: " + failures);
            assertEquals(baseline, runtime.actorMemoryBytes(),
                    "all concurrent scratch reservations must be conserved");

            ref.send(List.of("still-usable"));
            assertEquals(
                    List.of("still-usable"),
                    delivered.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void suspendedBinaryReceiveRetainsReservationUntilLogicalMailCompletes()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var completion =
                    new java.util.concurrent.atomic.AtomicReference<
                            dev.oreslang.runtime.OresFuture.Callback<Void>>();
            var gate = dev.oreslang.runtime.OresFuture.<Void>fromCallback(
                    completion::set);
            var entered = new java.util.concurrent.CountDownLatch(1);

            var ref = runtime.<Object>spawnPrivateTrusted(
                    context -> (value, turn) -> {
                        entered.countDown();
                        runtime.suspendCurrentUserMailboxUntil(gate);
                    });
            runtime.bindSerializableMailbox(
                    ref,
                    new MailboxSchema(
                            "test.Private.SuspendedBinary.v1",
                            list(string())));

            long baseline = runtime.actorMemoryBytes();
            ref.send(List.of("x".repeat(16_384)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            long retained = runtime.actorMemoryBytes();
            assertTrue(
                    retained > baseline,
                    "decoded/wire reservation must remain charged while activeMail "
                            + "survives a suspended receive");

            completion.get().resolve(null);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.actorMemoryBytes() != baseline
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(
                    baseline,
                    runtime.actorMemoryBytes(),
                    "suspended mail reservation must release exactly once on resume");

            ref.stop();
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void lateSchemaBindingCannotRetroactivelyChangeMailboxMeaning() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> delivered.add(value));
            ref.send(List.of("already-sent"));
            assertEquals(List.of("already-sent"), delivered.poll(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> runtime.bindSerializableMailbox(ref,
                            new MailboxSchema("test.TooLate.v1", list(string()))));
        }
    }
}
