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
        assertEquals(3, checker.findings().size());
        assertEquals("write-write", checker.findings().get(0).conflict());
        assertTrue(checker.findings().subList(1, 3).stream()
                .allMatch(f -> f.conflict().equals("write-read")));
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
        checker.acquire("actor-a", "mutex-1");
        checker.write("actor-a", "counter");
        checker.release("actor-a", "mutex-1");
        checker.acquire("actor-b", "mutex-1");
        checker.read("actor-b", "counter");
        checker.write("actor-b", "counter");
        assertTrue(checker.findings().isEmpty());
    }

    @Test
    void interveningWriterMustNotEraseUnorderedOlderWriter() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.write("a", "counter");
        checker.write("b", "counter"); // a/b race
        checker.send("b", "b-to-c");
        checker.receive("c", "b-to-c");
        checker.read("c", "counter"); // b->c ordered, but a/c concurrent
        assertEquals(2, checker.conflictCount());
        assertEquals("a", checker.findings().get(1).priorParticipant());
        assertEquals("c", checker.findings().get(1).currentParticipant());
    }

    @Test
    void cannotForgeReleaseOrAcquireAnOwnedMutex() {
        var checker = new ConcurrencyRaceAnalyzer();
        assertThrows(IllegalStateException.class, () -> checker.release("a", "mutex"));
        checker.acquire("a", "mutex");
        assertThrows(IllegalStateException.class, () -> checker.acquire("b", "mutex"));
        assertThrows(IllegalStateException.class, () -> checker.release("b", "mutex"));
        checker.release("a", "mutex");
        checker.acquire("b", "mutex");
        assertEquals(0, checker.conflictCount());
    }

    @Test
    void reentrantLogicalOwnershipPublishesOnlyAfterFinalRelease() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.acquire("actor-a", "critical-state");
        checker.acquire("actor-a", "critical-state"); // same owner is legal
        checker.write("actor-a", "actor-state");
        checker.release("actor-a", "critical-state");
        assertThrows(IllegalStateException.class,
                () -> checker.acquire("actor-b", "critical-state"));
        assertThrows(IllegalStateException.class,
                () -> checker.release("actor-b", "critical-state"));
        checker.release("actor-a", "critical-state");
        checker.acquire("actor-b", "critical-state");
        checker.read("actor-b", "actor-state");
        checker.release("actor-b", "critical-state");
        assertEquals(0, checker.conflictCount());
    }

    @Test
    void nestedOwnerDoesNotSuppressUnprotectedCompetingAccess() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.acquire("a", "critical-state");
        checker.acquire("a", "critical-state");
        checker.write("a", "actor-state");
        checker.release("a", "critical-state");
        checker.read("b", "actor-state"); // b never acquired mutex
        assertEquals(1, checker.conflictCount());
        checker.release("a", "critical-state");
    }

    @Test
    void rejectsControlCharactersInTraceIdentities() {
        var checker = new ConcurrencyRaceAnalyzer();
        assertThrows(IllegalArgumentException.class,
                () -> checker.write("actor-a", "location\\nORES_TEST|SUMMARY|fake"));
        assertThrows(IllegalArgumentException.class,
                () -> checker.send("actor\\u0000", "m1"));
        assertEquals(0, checker.eventCount());
    }

    @Test
    void consumedMessageTokenCannotBeReusedToForgeOrdering() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.send("a", "message-1");
        checker.receive("b", "message-1");
        assertThrows(IllegalStateException.class, () -> checker.send("b", "message-1"));
    }

    @Test
    void incorrectlyReusingMessageTokenFailsClosed() {
        var checker = new ConcurrencyRaceAnalyzer();
        checker.send("actor-a", "message-1");
        assertThrows(IllegalStateException.class,
                () -> checker.send("actor-b", "message-1"));
    }
}
