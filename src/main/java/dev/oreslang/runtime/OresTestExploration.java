package dev.oreslang.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.SplittableRandom;

/**
 * Bounded reference exploration and concrete CriticalGate stress.
 *
 * IMPORTANT: These checks cover VM CriticalGate admission, not arbitrary
 * Oreslang guest source, shadow-memory races, or exhaustive guest schedules.
 * The CLI must surface the limited coverage explicitly.
 */
public final class OresTestExploration {
    public record Report(long exploredStates, long rejectedCycles, long stressCases,
                         int strandedWaiters, int jvmDeadlockedThreads,
                         long inputFingerprint) {
        public boolean ok() {
            return (exploredStates == 0 || rejectedCycles > 0)
                    && strandedWaiters == 0 && jvmDeadlockedThreads == 0;
        }
    }

    private record State(int gate0, int gate1, int wait0, int wait1) {
        int owner(int gate) { return gate == 0 ? gate0 : gate1; }
        int waitFor(int task) { return task == 0 ? wait0 : wait1; }
        State owner(int gate, int task) {
            return gate == 0 ? new State(task, gate1, wait0, wait1)
                             : new State(gate0, task, wait0, wait1);
        }
        State waitFor(int task, int gate) {
            return task == 0 ? new State(gate0, gate1, gate, wait1)
                             : new State(gate0, gate1, wait0, gate);
        }
    }

    private OresTestExploration() { }

    /**
     * Systematically enumerate the finite two-task/two-gate reference model.
     * The graph is finite: four fields in {-1,0,1}. This detects a regression
     * in the reference ordering rules and includes true two-gate cycles.
     */
    public static Report run(long seed, int stressCases, boolean exploreModel) {
        if (stressCases < 0 || stressCases > 100_000) {
            throw new IllegalArgumentException("stress case count outside 0..100000");
        }
        long visited = 0;
        long cycleRejections = 0;
        if (exploreModel) {
            ArrayDeque<State> work = new ArrayDeque<>();
            HashSet<State> seen = new HashSet<>();
            State initial = new State(-1, -1, -1, -1);
            work.add(initial);
            seen.add(initial);
            while (!work.isEmpty()) {
                State current = work.removeFirst();
                visited++;
                for (int task = 0; task < 2; task++) {
                    for (int gate = 0; gate < 2; gate++) {
                        int owner = current.owner(gate);
                        if (current.waitFor(task) == -1 && owner == -1) {
                            enqueue(seen, work, current.owner(gate, task));
                        } else if (current.waitFor(task) == -1 && owner != task) {
                            // With two Tasks, a cycle is closed when the
                            // gate owner is waiting on a gate held by task.
                            int ownerWait = current.waitFor(owner);
                            if (ownerWait >= 0 && current.owner(ownerWait) == task) {
                                cycleRejections++;
                            } else {
                                enqueue(seen, work, current.waitFor(task, gate));
                            }
                        }
                    }
                    if (current.waitFor(task) >= 0) {
                        enqueue(seen, work, current.waitFor(task, -1));
                    }
                    for (int gate = 0; gate < 2; gate++) {
                        if (current.owner(gate) != task) continue;
                        State next = current.owner(gate, -1);
                        int waiter = 1 - task;
                        if (next.waitFor(waiter) == gate) {
                            next = next.waitFor(waiter, -1).owner(gate, waiter);
                        }
                        enqueue(seen, work, next);
                    }
                }
            }
            if (seen.size() != visited || visited <= 1 || cycleRejections == 0) {
                throw new IllegalStateException("bounded critical model did not exercise cycle rejection");
            }
        }

        // Exercise *actual* CriticalGate code, including cancellation and
        // direct FIFO handoff, with independently reproducible input seeds.
        SplittableRandom rng = new SplittableRandom(seed);
        long inputFingerprint = 0xcbf29ce484222325L;
        for (int n = 0; n < stressCases; n++) {
            CriticalGate gate = new CriticalGate();
            CriticalGate.Ticket first = gate.newTicket();
            if (!first.enterAsync().isDone()) throw new AssertionError("initial admission");
            int count = 1 + rng.nextInt(12);
            inputFingerprint = mix(inputFingerprint, n);
            inputFingerprint = mix(inputFingerprint, count);
            List<CriticalGate.Ticket> tickets = new ArrayList<>();
            List<OresFuture<Void>> futures = new ArrayList<>();
            boolean[] cancelled = new boolean[count];
            try {
                for (int i = 0; i < count; i++) {
                    CriticalGate.Ticket ticket = gate.newTicket();
                    tickets.add(ticket);
                    futures.add(ticket.enterAsync());
                }
                for (int i = 0; i < count; i++) {
                    boolean attemptCancellation = rng.nextBoolean();
                    inputFingerprint = mix(inputFingerprint, attemptCancellation ? 1 : 0);
                    if (attemptCancellation) {
                        cancelled[i] = futures.get(i).cancel(false);
                        if (!cancelled[i]) throw new AssertionError("cancel lost while owner held");
                    }
                }
                first.close();
                for (int i = 0; i < count; i++) {
                    CriticalGate.Ticket ticket = tickets.get(i);
                    if (ticket.isHeld() == cancelled[i]) {
                        throw new AssertionError("FIFO or cancelled admission, seed=" + seed
                                + ", case=" + n + ", index=" + i);
                    }
                    ticket.close();
                }
                if (gate.held() || gate.queuedCount() != 0) {
                    throw new AssertionError("critical gate not drained, seed=" + seed + ", case=" + n);
                }
            } finally {
                first.close();
                for (CriticalGate.Ticket ticket : tickets) ticket.close();
            }
        }

        // A separate adversarial real-thread interleaving, not a sequential
        // model: cancellation races physical permit handoff. The seed fixes
        // the test-input sequence, NOT the operating system's thread schedule.
        for (int n = 0; n < Math.min(stressCases, 256); n++) {
            checkConcurrentCancelHandoff(seed, n);
        }

        int remaining = CriticalGate.waitingLogicalDomains();
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        long[] stuck = mxBean.findDeadlockedThreads();
        return new Report(visited, cycleRejections, stressCases, remaining,
                stuck == null ? 0 : stuck.length, inputFingerprint);
    }

    private static void checkConcurrentCancelHandoff(long seed, int iteration) {
        CriticalGate gate = new CriticalGate();
        CriticalGate.Ticket owner = gate.newTicket();
        CriticalGate.Ticket contender = gate.newTicket();
        CriticalGate.Ticket follower = gate.newTicket();
        owner.enterAsync();
        OresFuture<Void> queued = contender.enterAsync();
        follower.enterAsync();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> cancellationFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                start.await();
                queued.cancel(false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancellationFailure.set(interrupted);
            } catch (RuntimeException | Error error) {
                cancellationFailure.set(error);
            }
        });
        try {
            start.countDown();
            owner.close();
            canceller.join(5_000);
            if (canceller.isAlive()) {
                canceller.interrupt();
                throw new AssertionError("cancellation-vs-handoff stuck: seed="
                        + seed + " iteration=" + iteration);
            }
            if (cancellationFailure.get() != null) {
                throw new AssertionError("canceller task failed: seed="
                        + seed + " iteration=" + iteration, cancellationFailure.get());
            }
            if (queued.isCancelled() && contender.isHeld()) {
                throw new AssertionError("cancelled ticket retained gate: seed="
                        + seed + " iteration=" + iteration);
            }
            if (contender.isHeld()) contender.close();
            if (!follower.isHeld()) {
                throw new AssertionError("handoff lost successor: seed="
                        + seed + " iteration=" + iteration);
            }
            follower.close();
            if (gate.held() || gate.queuedCount() != 0) {
                throw new AssertionError("permit or waiter stranded: seed="
                        + seed + " iteration=" + iteration);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while probing critical race", interrupted);
        } finally {
            owner.close();
            contender.close();
            follower.close();
        }
    }

    private static long mix(long accumulator, long value) {
        return (accumulator ^ value) * 0x100000001b3L;
    }

    private static void enqueue(HashSet<State> seen, ArrayDeque<State> work, State value) {
        if (seen.add(value)) work.addLast(value);
    }
}
