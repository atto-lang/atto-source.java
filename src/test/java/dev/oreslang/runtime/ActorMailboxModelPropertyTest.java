package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Model-based proof of actor mailbox semantics.
 *
 * <p>A seeded random program drives one target actor through send, trySend,
 * sendAsync and cancellation while its single turn is plugged, so a reference
 * model can predict every admission decision exactly. After the plug is
 * released the delivered sequence must equal the model's: admitted mail in
 * admission order, then surviving async producers in registration order.
 * A cancelled producer's mail is never delivered. Every delivered payload is
 * a detached copy that later sender mutation cannot reach.</p>
 *
 * <p>The concurrent phase races host threads and sender actors into a small
 * mailbox and checks per-sender FIFO, exactly-once delivery and no gaps.</p>
 */
@Timeout(120)
final class ActorMailboxModelPropertyTest {

    private static final int PLUG = -1;

    private static IsolatePolicy mailboxPolicy(int messages) {
        IsolatePolicy base = IsolatePolicy.developer();
        return new IsolatePolicy(base.capabilities(), base.maxHeapBytes(), messages,
                base.maxWallTime(), base.adversarial());
    }

    /** Mail is {sender, seq, payload}; payload encodes (sender, seq). */
    private static List<Object> mail(int sender, int seq, List<Integer> payload) {
        List<Object> envelope = new ArrayList<>(3);
        envelope.add(sender);
        envelope.add(seq);
        envelope.add(payload);
        return envelope;
    }

    private static List<Integer> payloadFor(int sender, int seq) {
        List<Integer> payload = new ArrayList<>();
        payload.add(sender);
        payload.add(seq);
        payload.add(sender * 31 + seq);
        return payload;
    }

    private record Delivered(int sender, int seq) { }

    /** Target actor that records deliveries and blocks its turn on PLUG mail. */
    private static final class Target {
        final List<Delivered> delivered = new CopyOnWriteArrayList<>();
        final List<String> violations = new CopyOnWriteArrayList<>();
        final Set<Object> sentPayloads = Collections.newSetFromMap(new IdentityHashMap<>());
        final AtomicReference<CountDownLatch> entered = new AtomicReference<>();
        final AtomicReference<CountDownLatch> release = new AtomicReference<>();

        ActorRuntime.Behavior<List<Object>> behavior() {
            return (message, context) -> {
                int sender = (Integer) message.get(0);
                int seq = (Integer) message.get(1);
                if (sender == PLUG) {
                    entered.get().countDown();
                    if (!release.get().await(30, TimeUnit.SECONDS)) {
                        violations.add("plug was never released");
                    }
                    return;
                }
                Object payload = message.get(2);
                synchronized (sentPayloads) {
                    if (sentPayloads.contains(payload)) {
                        violations.add("payload aliased the sender's object: " + sender + "/" + seq);
                    }
                }
                if (!payloadFor(sender, seq).equals(payload)) {
                    violations.add("payload changed after send: " + sender + "/" + seq + " -> " + payload);
                }
                delivered.add(new Delivered(sender, seq));
            };
        }

        /** Sends a plug and waits until the target's turn is blocked on it. */
        void plug(ActorRuntime.ActorRef<List<Object>> ref) throws Exception {
            entered.set(new CountDownLatch(1));
            release.set(new CountDownLatch(1));
            ref.sendAsync(mail(PLUG, 0, List.of())).get(10, TimeUnit.SECONDS);
            assertTrue(entered.get().await(10, TimeUnit.SECONDS), "plug turn entered");
        }

        void unplug() {
            CountDownLatch latch = release.get();
            if (latch != null) latch.countDown();
        }

        /** Sends caller-owned payload and remembers its identity for the aliasing check. */
        List<Object> track(int sender, int seq) {
            List<Integer> payload = payloadFor(sender, seq);
            synchronized (sentPayloads) {
                sentPayloads.add(payload);
            }
            return mail(sender, seq, payload);
        }

        /** Mutates every caller-owned payload after admission. */
        void scribble() {
            synchronized (sentPayloads) {
                for (Object payload : sentPayloads) {
                    @SuppressWarnings("unchecked")
                    List<Integer> list = (List<Integer>) payload;
                    list.set(2, -999);
                }
            }
        }
    }

    private enum Op { SEND, TRY, ASYNC, CANCEL }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 7, 42, 99, 1234, 8675309, 31337, 271828})
    void pluggedTargetMatchesTheReferenceModel(long seed) throws Exception {
        Random random = new Random(seed);
        int capacity = 1 + random.nextInt(6);
        boolean shared = random.nextBoolean();
        Target target = new Target();
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy policy = mailboxPolicy(capacity);
            ActorRuntime.ActorRef<List<Object>> ref = shared
                    ? runtime.spawnShared(policy, target::behavior)
                    : runtime.spawnPrivate(policy, target::behavior);
            int seq = 0;
            for (int round = 0; round < 12; round++) {
                target.plug(ref);
                // Reference model: the plugged turn holds no slot; the queue is empty.
                int queued = 0;
                List<Delivered> admitted = new ArrayList<>();
                List<Map.Entry<Delivered, OresFuture<Void>>> pending = new ArrayList<>();
                int ops = random.nextInt(3 * capacity + 2);
                for (int i = 0; i < ops; i++) {
                    Op op = Op.values()[random.nextInt(Op.values().length)];
                    Delivered next = new Delivered(0, seq);
                    String where = "seed " + seed + " round " + round + " op " + i + " " + op
                            + " (capacity " + capacity + ", queued " + queued + ")";
                    switch (op) {
                        case SEND -> {
                            List<Object> message = target.track(0, seq++);
                            if (queued < capacity) {
                                ref.send(message);
                                queued++;
                                admitted.add(next);
                            } else {
                                assertThrows(ActorRuntime.ActorMailboxFullException.class,
                                        () -> ref.send(message), where);
                            }
                        }
                        case TRY -> {
                            boolean accepted = ref.trySend(target.track(0, seq++));
                            assertEquals(queued < capacity, accepted, where);
                            if (accepted) {
                                queued++;
                                admitted.add(next);
                            }
                        }
                        case ASYNC -> {
                            OresFuture<Void> future = ref.sendAsync(target.track(0, seq++));
                            if (queued < capacity) {
                                assertTrue(future.isDone(), where + ": room means immediate admission");
                                future.get(1, TimeUnit.SECONDS);
                                queued++;
                                admitted.add(next);
                            } else {
                                assertFalse(future.isDone(), where + ": full means a pending producer");
                                pending.add(Map.entry(next, future));
                            }
                        }
                        case CANCEL -> {
                            if (pending.isEmpty()) break;
                            int victim = random.nextInt(pending.size());
                            assertTrue(pending.remove(victim).getValue().cancel(false),
                                    where + ": cancelling an unadmitted producer succeeds");
                        }
                    }
                }
                // Sender-side mutation after admission must not reach the actor.
                target.scribble();
                target.unplug();
                for (Map.Entry<Delivered, OresFuture<Void>> producer : pending) {
                    producer.getValue().get(10, TimeUnit.SECONDS);
                    admitted.add(producer.getKey());
                }
                ref.drain().get(10, TimeUnit.SECONDS);
                List<Delivered> delivered = new ArrayList<>(target.delivered);
                target.delivered.clear();
                assertEquals(admitted, delivered,
                        "seed " + seed + " round " + round + ": delivery order differs from the model");
                assertEquals(List.of(), target.violations, "seed " + seed + " round " + round);
            }
            ref.stop();
            ref.getDone().get(10, TimeUnit.SECONDS);
        } finally {
            target.unplug();
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {5, 17, 4242})
    void racingSendersKeepPerSenderFifoExactlyOnce(long seed) throws Exception {
        Random random = new Random(seed);
        int capacity = 1 + random.nextInt(3);
        int hostSenders = 3;
        int actorSenders = 3;
        int perSender = 300;
        boolean shared = random.nextBoolean();
        Map<Integer, Integer> lastSeq = new ConcurrentHashMap<>();
        List<String> violations = new CopyOnWriteArrayList<>();
        AtomicInteger deliveries = new AtomicInteger();
        Set<Object> sentPayloads = Collections.newSetFromMap(new IdentityHashMap<>());

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.Behavior<List<Object>> sink = (message, context) -> {
                int sender = (Integer) message.get(0);
                int seq = (Integer) message.get(1);
                int previous = lastSeq.getOrDefault(sender, -1);
                if (seq != previous + 1) {
                    violations.add("sender " + sender + ": got " + seq + " after " + previous);
                }
                lastSeq.put(sender, seq);
                synchronized (sentPayloads) {
                    if (sentPayloads.contains(message.get(2))) {
                        violations.add("aliased payload " + sender + "/" + seq);
                    }
                }
                if (!payloadFor(sender, seq).equals(message.get(2))) {
                    violations.add("payload changed " + sender + "/" + seq);
                }
                // Short random work so producers really meet a full mailbox.
                if (((sender * 7 + seq) & 7) == 0) Thread.onSpinWait();
                deliveries.incrementAndGet();
            };
            IsolatePolicy policy = mailboxPolicy(capacity);
            ActorRuntime.ActorRef<List<Object>> target = shared
                    ? runtime.spawnShared(policy, () -> sink)
                    : runtime.spawnPrivate(policy, () -> sink);

            // Sender actors cannot block: on a full mailbox they re-queue a tick to themselves.
            List<ActorRuntime.ActorRef<Integer>> actors = new ArrayList<>();
            CountDownLatch actorsDone = new CountDownLatch(actorSenders);
            AtomicInteger fullRetries = new AtomicInteger();
            for (int a = 0; a < actorSenders; a++) {
                int sender = 100 + a;
                int[] next = {0};
                actors.add(runtime.<Integer>spawnShared(() -> (tick, context) -> {
                    List<Integer> payload = payloadFor(sender, next[0]);
                    synchronized (sentPayloads) {
                        sentPayloads.add(payload);
                    }
                    if (context.runtime().trySend(target, mail(sender, next[0], payload))) {
                        payload.set(2, -1); // mutate after admission
                        if (++next[0] == perSender) {
                            actorsDone.countDown();
                            return;
                        }
                    } else {
                        fullRetries.incrementAndGet();
                    }
                    context.self().send(0);
                }));
            }
            for (ActorRuntime.ActorRef<Integer> actor : actors) actor.send(0);

            List<Thread> hosts = new ArrayList<>();
            List<Throwable> hostFailures = new CopyOnWriteArrayList<>();
            for (int h = 0; h < hostSenders; h++) {
                int sender = h;
                Thread thread = new Thread(() -> {
                    try {
                        for (int s = 0; s < perSender; s++) {
                            List<Integer> payload = payloadFor(sender, s);
                            synchronized (sentPayloads) {
                                sentPayloads.add(payload);
                            }
                            List<Object> message = mail(sender, s, payload);
                            if ((s & 1) == 0) {
                                target.sendAsync(message).get(30, TimeUnit.SECONDS);
                            } else {
                                while (!target.trySend(message)) Thread.onSpinWait();
                            }
                            payload.set(2, -1);
                        }
                    } catch (Throwable failure) {
                        hostFailures.add(failure);
                    }
                }, "mailbox-host-sender-" + h);
                hosts.add(thread);
                thread.start();
            }
            for (Thread thread : hosts) thread.join(60_000);
            assertTrue(actorsDone.await(60, TimeUnit.SECONDS), "sender actors finished");
            assertEquals(List.of(), hostFailures);
            target.drain().get(30, TimeUnit.SECONDS);

            assertEquals(List.of(), violations, "seed " + seed);
            int senders = hostSenders + actorSenders;
            assertEquals(senders * perSender, deliveries.get(), "exactly-once delivery count");
            assertEquals(senders, lastSeq.size());
            lastSeq.values().forEach(last -> assertEquals(perSender - 1, last, "no gaps"));
            assertTrue(fullRetries.get() > 0 || capacity > 1,
                    "a one-slot mailbox must push back on actor senders");
            target.stop();
            target.getDone().get(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest(name = "shared={0}")
    @ValueSource(booleans = {false, true})
    void theDeliveredMessageIsADetachedSnapshot(boolean shared) throws Exception {
        AtomicReference<Object> seen = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.Behavior<List<Object>> behavior = (message, context) -> {
                seen.set(message);
                delivered.countDown();
            };
            ActorRuntime.ActorRef<List<Object>> ref = shared
                    ? runtime.spawnShared(() -> behavior)
                    : runtime.spawnPrivate(() -> behavior);
            List<Integer> payload = payloadFor(9, 9);
            List<Object> message = mail(9, 9, payload);
            ref.send(message);
            payload.set(0, 12345);
            message.set(1, 54321);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            @SuppressWarnings("unchecked")
            List<Object> got = (List<Object>) seen.get();
            assertNotSame(message, got);
            assertEquals(mail(9, 9, payloadFor(9, 9)), got, "sender mutation after send is invisible");
        }
    }
}
