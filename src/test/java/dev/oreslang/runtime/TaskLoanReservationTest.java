package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TaskLoanReservationTest {

    private record Region(String name) { }

    private record IntAccessSet(Set<Integer> keys)
            implements ExecutionPermit.AccessSet<IntAccessSet> {
        private IntAccessSet {
            keys = Set.copyOf(keys);
        }

        @Override
        public boolean overlaps(IntAccessSet other) {
            for (Integer key : keys) {
                if (other.keys.contains(key)) return true;
            }
            return false;
        }

        @Override
        public IntAccessSet union(IntAccessSet other) {
            java.util.HashSet<Integer> merged = new java.util.HashSet<>(keys);
            merged.addAll(other.keys);
            return new IntAccessSet(merged);
        }

        @Override
        public boolean sameCoverage(IntAccessSet other) {
            return keys.equals(other.keys);
        }
    }

    private static IntAccessSet access(int... keys) {
        java.util.HashSet<Integer> values = new java.util.HashSet<>();
        for (int key : keys) values.add(key);
        return new IntAccessSet(values);
    }

    @Test
    void reservationRequiresExactScopeEpochAndExactChildIdentity() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            OresTaskScope foreignScope = new OresTaskScope(scheduler);
            ExecutionPermit<Region, IntAccessSet> permit =
                    ExecutionPermit.mintRoot(new Region("r"), 7, access(1));
            AtomicInteger neverStarted = new AtomicInteger();
            AtomicInteger physical = new AtomicInteger();
            AtomicReference<ExecutionPermit<Region, IntAccessSet>> observed =
                    new AtomicReference<>();

            TaskLoanReservation<Region, IntAccessSet> reservation =
                    TaskLoanReservation.prepare(
                            scope,
                            permit,
                            new TaskLoanReservation.Retirement<>() {
                                @Override
                                public void retireNeverStarted(
                                        ExecutionPermit<Region, IntAccessSet> actual) {
                                    neverStarted.incrementAndGet();
                                    observed.set(actual);
                                }

                                @Override
                                public void retireAfterPhysicalQuiescence(
                                        ExecutionPermit<Region, IntAccessSet> actual) {
                                    physical.incrementAndGet();
                                    observed.set(actual);
                                }
                            });

            OresFuture<Integer> child = new OresFuture<>();
            OresFuture<Integer> lookalike = new OresFuture<>();

            assertThrows(
                    IllegalArgumentException.class,
                    () -> reservation.bind(foreignScope, child),
                    "foreign scope epoch must not bind a reservation");

            reservation.bind(scope, child);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> reservation.retireAfterPhysicalQuiescence(scope, lookalike),
                    "lookalike Future must not release Task loan authority");
            assertFalse(reservation.isRetired());
            assertEquals(0, physical.get());

            reservation.retireAfterPhysicalQuiescence(scope, child);

            assertTrue(reservation.isRetired());
            assertSame(permit, observed.get());
            assertEquals(0, neverStarted.get());
            assertEquals(1, physical.get());
            assertThrows(
                    IllegalStateException.class,
                    () -> reservation.retireAfterPhysicalQuiescence(scope, child),
                    "retirement must be exactly once");
        }
    }

    @Test
    void neverStartedPathIsDistinctAndExactlyOnce() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            OresTaskScope foreignScope = new OresTaskScope(scheduler);
            ExecutionPermit<Region, IntAccessSet> permit =
                    ExecutionPermit.mintRoot(new Region("r"), 3, access(1));
            AtomicInteger neverStarted = new AtomicInteger();
            AtomicInteger physical = new AtomicInteger();

            TaskLoanReservation<Region, IntAccessSet> reservation =
                    TaskLoanReservation.prepare(
                            scope,
                            permit,
                            new TaskLoanReservation.Retirement<>() {
                                @Override
                                public void retireNeverStarted(
                                        ExecutionPermit<Region, IntAccessSet> actual) {
                                    assertSame(permit, actual);
                                    neverStarted.incrementAndGet();
                                }

                                @Override
                                public void retireAfterPhysicalQuiescence(
                                        ExecutionPermit<Region, IntAccessSet> actual) {
                                    physical.incrementAndGet();
                                }
                            });

            assertThrows(
                    IllegalArgumentException.class,
                    () -> reservation.retireNeverStarted(foreignScope));

            reservation.retireNeverStarted(scope);

            assertTrue(reservation.isRetired());
            assertEquals(1, neverStarted.get());
            assertEquals(0, physical.get());
            assertThrows(
                    IllegalStateException.class,
                    () -> reservation.retireNeverStarted(scope));
            assertThrows(
                    IllegalStateException.class,
                    () -> reservation.bind(scope, new OresFuture<>()));
        }
    }

    @Test
    void stalePermitGenerationFailsClosedBeforeRetirementCallback() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            ExecutionPermit<Region, IntAccessSet> permit =
                    ExecutionPermit.mintRoot(new Region("r"), 11, access(1));
            AtomicInteger physical = new AtomicInteger();

            TaskLoanReservation<Region, IntAccessSet> reservation =
                    TaskLoanReservation.prepare(
                            scope,
                            permit,
                            new TaskLoanReservation.Retirement<>() {
                                @Override
                                public void retireNeverStarted(
                                        ExecutionPermit<Region, IntAccessSet> actual) {
                                    fail("bound reservation must not use never-started path");
                                }

                                @Override
                                public void retireAfterPhysicalQuiescence(
                                        ExecutionPermit<Region, IntAccessSet> actual) {
                                    physical.incrementAndGet();
                                }
                            });
            OresFuture<Integer> child = new OresFuture<>();
            reservation.bind(scope, child);

            ExecutionPermit<Region, IntAccessSet> next =
                    permit.rotateGeneration(12, access(1));
            assertTrue(next.isLive());
            assertFalse(permit.isLive());

            assertThrows(
                    IllegalStateException.class,
                    () -> reservation.retireAfterPhysicalQuiescence(scope, child));
            assertFalse(reservation.isRetired());
            assertEquals(0, physical.get());
        }
    }

    @Test
    void borrowedChildRetiresOnlyAfterTerminalAndPhysicalQuiescence()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            ExecutionPermit<Region, IntAccessSet> permit =
                    ExecutionPermit.mintRoot(new Region("r"), 5, access(1));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch physicallyRetired = new CountDownLatch(1);
            AtomicInteger neverStarted = new AtomicInteger();
            AtomicInteger physical = new AtomicInteger();

            OresFuture<Integer> child = scope.forkBorrowed(
                    resume -> {
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return OresScheduler.done(9);
                    },
                    permit,
                    new TaskLoanReservation.Retirement<>() {
                        @Override
                        public void retireNeverStarted(
                                ExecutionPermit<Region, IntAccessSet> actual) {
                            neverStarted.incrementAndGet();
                        }

                        @Override
                        public void retireAfterPhysicalQuiescence(
                                ExecutionPermit<Region, IntAccessSet> actual) {
                            assertSame(permit, actual);
                            physical.incrementAndGet();
                            physicallyRetired.countDown();
                        }
                    });

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> drained = scope.cancelAndQuiesce();

            assertTrue(child.isCancelled());
            assertEquals(0, physical.get(),
                    "logical cancellation must not retire borrowed memory");
            assertFalse(drained.isDone());

            release.countDown();

            assertTrue(physicallyRetired.await(5, TimeUnit.SECONDS));
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertEquals(0, neverStarted.get());
            assertEquals(1, physical.get());
            assertEquals(0, scope.activeChildren());
        }
    }

    @Test
    void schedulerRejectionUsesOnlyNeverStartedRetirement() {
        OresScheduler scheduler = new OresScheduler(1);
        scheduler.close();

        OresTaskScope scope = new OresTaskScope(scheduler);
        ExecutionPermit<Region, IntAccessSet> permit =
                ExecutionPermit.mintRoot(new Region("r"), 1, access(1));
        AtomicInteger neverStarted = new AtomicInteger();
        AtomicInteger physical = new AtomicInteger();

        assertThrows(
                RejectedExecutionException.class,
                () -> scope.forkBorrowed(
                        resume -> OresScheduler.done(1),
                        permit,
                        new TaskLoanReservation.Retirement<>() {
                            @Override
                            public void retireNeverStarted(
                                    ExecutionPermit<Region, IntAccessSet> actual) {
                                assertSame(permit, actual);
                                neverStarted.incrementAndGet();
                            }

                            @Override
                            public void retireAfterPhysicalQuiescence(
                                    ExecutionPermit<Region, IntAccessSet> actual) {
                                physical.incrementAndGet();
                            }
                        }));

        assertEquals(1, neverStarted.get());
        assertEquals(0, physical.get());
        assertEquals(0, scope.activeChildren());
    }

    @Test
    void retirementFailurePoisonsScopeAndNeverPublishesDrainWitness()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            ExecutionPermit<Region, IntAccessSet> permit =
                    ExecutionPermit.mintRoot(new Region("r"), 2, access(1));
            RuntimeException retirementFailure =
                    new RuntimeException("retirement failed");
            CountDownLatch hookEntered = new CountDownLatch(1);

            OresFuture<Integer> child = scope.forkBorrowed(
                    resume -> OresScheduler.done(4),
                    permit,
                    new TaskLoanReservation.Retirement<>() {
                        @Override
                        public void retireNeverStarted(
                                ExecutionPermit<Region, IntAccessSet> actual) {
                            fail("started child must not use never-started retirement");
                        }

                        @Override
                        public void retireAfterPhysicalQuiescence(
                                ExecutionPermit<Region, IntAccessSet> actual) {
                            hookEntered.countDown();
                            throw retirementFailure;
                        }
                    });

            OresFuture<Void> drained = scope.quiesce();
            assertEquals(4, child.get(5, TimeUnit.SECONDS));
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS));

            assertSame(retirementFailure, scope.retirementFailure());
            assertFalse(scope.isOpen());
            assertFalse(drained.isDone(),
                    "failed memory retirement must never become a catchable drain witness");
            assertEquals(1, scope.activeChildren(),
                    "failed reservation must keep its join slot live");
        }
    }
}
