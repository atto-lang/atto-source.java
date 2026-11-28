package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class VersionedActorCellTest {
    @Test
    void rejectsStaleForeignRetiredAndAbaSnapshots() {
        VersionedActorCell cell = new VersionedActorCell(5);
        var first = cell.snapshot();
        assertEquals(5, first.value());
        assertEquals(VersionedActorCell.Commit.COMMITTED, cell.tryCommit(first, 6));
        assertEquals(VersionedActorCell.Commit.CONFLICT, cell.tryCommit(first, 7));
        var second = cell.snapshot();
        assertEquals(VersionedActorCell.Commit.COMMITTED, cell.tryCommit(second, 5));
        assertEquals(VersionedActorCell.Commit.CONFLICT, cell.tryCommit(first, 8));
        assertEquals(VersionedActorCell.Commit.FOREIGN,
                new VersionedActorCell(5).tryCommit(cell.snapshot(), 9));
        var pending = cell.snapshot();
        cell.retire();
        cell.retire();
        assertEquals(VersionedActorCell.Commit.RETIRED, cell.tryCommit(pending, 10));
        assertThrows(IllegalStateException.class, cell::snapshot);
    }

    @Test
    void twoConcurrentContinuationsCannotBothCommit() throws Exception {
        VersionedActorCell cell = new VersionedActorCell(0);
        var baseline = cell.snapshot();
        var start = new CountDownLatch(1);
        var finish = new CountDownLatch(32);
        var wins = new AtomicInteger();
        for (int i = 0; i < 32; i++) {
            final int value = i + 1;
            Thread.ofPlatform().start(() -> {
                try {
                    if (start.await(3, TimeUnit.SECONDS)
                            && cell.tryCommit(baseline, value)
                                == VersionedActorCell.Commit.COMMITTED) wins.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finish.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(finish.await(5, TimeUnit.SECONDS));
        assertEquals(1, wins.get());
    }

    @Test
    void versionExhaustionFailsClosed() {
        var exhausted = new VersionedActorCell(0, Long.MAX_VALUE);
        assertEquals(VersionedActorCell.Commit.EXHAUSTED,
                exhausted.tryCommit(exhausted.snapshot(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedActorCell(0, -1));
    }
}
