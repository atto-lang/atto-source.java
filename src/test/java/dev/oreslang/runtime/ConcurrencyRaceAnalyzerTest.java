package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConcurrencyRaceAnalyzerTest {
    @Test
    void detectsUnsynchronizedWriteWriteAndWriteRead() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.write("actor-a", "counter");
        checker.write("actor-b", "counter");
        checker.read("actor-c", "counter");
        assertEquals(2, checker.findings().size());
        assertEquals("write-write", checker.findings().get(0).conflict());
        assertEquals("write-read", checker.findings().get(1).conflict());
    }

    @Test
    void doesNotConfuseSequentialSameActorAccessWithRace() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.read("actor-a", "counter");
        checker.write("actor-a", "counter");
        checker.read("actor-a", "counter");
        assertTrue(checker.findings().isEmpty());
    }

    @Test
    void detectsReadWriteConflictButNotIndependentReads() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.read("actor-a", "counter");
        checker.read("actor-b", "counter");
        assertTrue(checker.findings().isEmpty());
        checker.write("actor-c", "counter");
        assertEquals(2, checker.findings().size());
        assertTrue(checker.findings().stream().allMatch(f -> f.conflict().equals("read-write")));
    }

    @Test
    void messageHappensBeforeEliminatesFalseRace() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.write("actor-a", "counter");
        checker.send("actor-a", "message-1");
        checker.receive("actor-b", "message-1");
        checker.write("actor-b", "counter");
        assertTrue(checker.findings().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> checker.receive("actor-c", "message-1"));
    }

    @Test
    void lockReleaseAcquireEstablishesHappensBefore() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.write("actor-a", "counter");
        checker.release("actor-a", "mutex-1");
        checker.acquire("actor-b", "mutex-1");
        checker.read("actor-b", "counter");
        checker.write("actor-b", "counter");
        assertTrue(checker.findings().isEmpty());
    }

    @Test
    void incorrectlyReusingMessageTokenFailsClosed() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.send("actor-a", "message-1");
        assertThrows(IllegalStateException.class,
                () -> checker.send("actor-b", "message-1"));
    }
}
