package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionPermitTest {

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
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        for (int key : keys) set.add(key);
        return new IntAccessSet(set);
    }

    @Test
    void splitConsumesParentAndRejoinConsumesCompleteSiblingSet() {
        ExecutionPermit<Region, IntAccessSet> root =
                ExecutionPermit.mintRoot(new Region("r"), 7, access(1, 2, 3, 4));

        List<ExecutionPermit<Region, IntAccessSet>> children =
                root.split(List.of(access(1, 2), access(3, 4)));

        assertFalse(root.isLive());
        assertTrue(children.get(0).isLive());
        assertTrue(children.get(1).isLive());

        ExecutionPermit<Region, IntAccessSet> rejoined =
                ExecutionPermit.rejoin(children);

        assertTrue(rejoined.isLive());
        assertEquals(7, rejoined.generation());
        assertEquals(access(1, 2, 3, 4), rejoined.accessSet());
        assertFalse(children.get(0).isLive());
        assertFalse(children.get(1).isLive());
    }

    @Test
    void overlappingOrIncompleteSplitFailsWithoutConsumingParent() {
        ExecutionPermit<Region, IntAccessSet> root =
                ExecutionPermit.mintRoot(new Region("r"), 1, access(1, 2, 3));

        assertThrows(IllegalArgumentException.class,
                () -> root.split(List.of(access(1, 2), access(2, 3))));
        assertTrue(root.isLive());

        assertThrows(IllegalArgumentException.class,
                () -> root.split(List.of(access(1), access(2))));
        assertTrue(root.isLive());
    }

    @Test
    void rejoinRejectsIncompleteDuplicateAndMixedLineageSets() {
        ExecutionPermit<Region, IntAccessSet> leftRoot =
                ExecutionPermit.mintRoot(new Region("left"), 1, access(1, 2));
        ExecutionPermit<Region, IntAccessSet> rightRoot =
                ExecutionPermit.mintRoot(new Region("right"), 1, access(1, 2));

        List<ExecutionPermit<Region, IntAccessSet>> left =
                leftRoot.split(List.of(access(1), access(2)));
        List<ExecutionPermit<Region, IntAccessSet>> right =
                rightRoot.split(List.of(access(1), access(2)));

        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPermit.rejoin(List.of(left.getFirst())));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPermit.rejoin(List.of(left.getFirst(), left.getFirst())));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPermit.rejoin(List.of(left.getFirst(), right.get(1))));

        assertTrue(left.getFirst().isLive());
        assertTrue(left.get(1).isLive());
        assertTrue(right.getFirst().isLive());
        assertTrue(right.get(1).isLive());
    }

    @Test
    void nestedSplitRejoinPreservesOuterLineage() {
        ExecutionPermit<Region, IntAccessSet> root =
                ExecutionPermit.mintRoot(new Region("r"), 3, access(1, 2, 3, 4));

        List<ExecutionPermit<Region, IntAccessSet>> outer =
                root.split(List.of(access(1, 2), access(3, 4)));
        List<ExecutionPermit<Region, IntAccessSet>> nested =
                outer.getFirst().split(List.of(access(1), access(2)));

        ExecutionPermit<Region, IntAccessSet> restoredFirst =
                ExecutionPermit.rejoin(nested);
        ExecutionPermit<Region, IntAccessSet> restoredRoot =
                ExecutionPermit.rejoin(List.of(restoredFirst, outer.get(1)));

        assertTrue(restoredRoot.isLive());
        assertEquals(access(1, 2, 3, 4), restoredRoot.accessSet());
    }

    @Test
    void generationRotationInvalidatesPriorCapabilityAndRequiresRoot() {
        ExecutionPermit<Region, IntAccessSet> root =
                ExecutionPermit.mintRoot(new Region("r"), 4, access(1, 2));

        List<ExecutionPermit<Region, IntAccessSet>> children =
                root.split(List.of(access(1), access(2)));
        assertThrows(IllegalStateException.class,
                () -> children.getFirst().rotateGeneration(5, access(1)));

        ExecutionPermit<Region, IntAccessSet> restored =
                ExecutionPermit.rejoin(children);
        ExecutionPermit<Region, IntAccessSet> next =
                restored.rotateGeneration(5, access(1, 2));

        assertFalse(restored.isLive());
        assertTrue(next.isLive());
        assertEquals(5, next.generation());
        assertThrows(IllegalStateException.class,
                () -> restored.split(List.of(access(1), access(2))));
    }

    @Test
    void concurrentRejoinVersusNestedSplitHasOneWinnerAndNoPartialConsumption()
            throws Exception {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 500; round++) {
                ExecutionPermit<Region, IntAccessSet> root =
                        ExecutionPermit.mintRoot(
                                new Region("r-" + round),
                                1,
                                access(1, 2, 3, 4));
                List<ExecutionPermit<Region, IntAccessSet>> outer =
                        root.split(List.of(access(1, 2), access(3, 4)));
                ExecutionPermit<Region, IntAccessSet> first = outer.getFirst();
                ExecutionPermit<Region, IntAccessSet> second = outer.get(1);

                java.util.concurrent.CountDownLatch start =
                        new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<
                        ExecutionPermit<Region, IntAccessSet>> rejoined =
                        new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<
                        List<ExecutionPermit<Region, IntAccessSet>>> nested =
                        new java.util.concurrent.atomic.AtomicReference<>();

                java.util.concurrent.Future<?> rejoinAttempt = pool.submit(() -> {
                    await(start);
                    try {
                        rejoined.set(ExecutionPermit.rejoin(List.of(first, second)));
                    } catch (IllegalStateException expectedLostRace) {
                        // Nested split consumed first.
                    }
                });
                java.util.concurrent.Future<?> splitAttempt = pool.submit(() -> {
                    await(start);
                    try {
                        nested.set(first.split(List.of(access(1), access(2))));
                    } catch (IllegalStateException expectedLostRace) {
                        // Outer rejoin consumed first.
                    }
                });

                start.countDown();
                rejoinAttempt.get(5, TimeUnit.SECONDS);
                splitAttempt.get(5, TimeUnit.SECONDS);

                assertNotEquals(rejoined.get() == null, nested.get() == null,
                        "exactly one linear permit transition must win");

                if (nested.get() != null) {
                    assertTrue(second.isLive(),
                            "failed outer rejoin must not partially consume its sibling");
                    ExecutionPermit<Region, IntAccessSet> restoredFirst =
                            ExecutionPermit.rejoin(nested.get());
                    ExecutionPermit<Region, IntAccessSet> restoredRoot =
                            ExecutionPermit.rejoin(List.of(restoredFirst, second));
                    assertTrue(restoredRoot.isLive());
                } else {
                    assertNotNull(rejoined.get());
                    assertTrue(rejoined.get().isLive());
                }
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

}
