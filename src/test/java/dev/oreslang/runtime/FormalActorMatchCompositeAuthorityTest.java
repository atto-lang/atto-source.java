package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded, dependency-free authority-composition model, not a proof that the
 * current Java or LLVM lowering refines this protocol.
 *
 * The compound origin must match BEFORE CONTROL capacity reservation and
 * AGAIN at guest publication. Ticket authority alone is not lexical ARM_RESULT
 * ownership: the exact source task and match frame must also match.
 */
final class FormalActorMatchCompositeAuthorityTest {
    private record Origin(int actor, int incarnation, int receiveLease,
                          int sourceTask, int lexicalFrame) {}

    private static boolean admitted(Origin ticket, Origin destination,
                                    boolean retiredOrCancelled) {
        return !retiredOrCancelled && ticket.equals(destination);
    }

    @Test
    void exhaustiveCrossProductRejectsEveryForeignDimensionBeforeCapacityAndPublication() {
        List<Origin> origins = allOrigins();
        assertEquals(32, origins.size());
        int accepted = 0;
        int rejected = 0;
        for (Origin source : origins) {
            for (Origin destination : origins) {
                boolean expected = source.equals(destination);
                // Model two independent security boundaries: admission to
                // a bounded CONTROL slot and lexical result publication.
                boolean beforeCapacity = admitted(source, destination, false);
                boolean beforePublication = admitted(source, destination, false);
                assertEquals(expected, beforeCapacity);
                assertEquals(expected, beforePublication);
                assertFalse(admitted(source, destination, true),
                        "retirement revokes matching tickets too");
                if (beforeCapacity) accepted++; else rejected++;
            }
        }
        assertEquals(32, accepted);
        assertEquals(992, rejected);
    }

    @Test
    void eachDroppedAuthorityFieldHasItsOwnNegativeMutantWitness() {
        List<Origin> origins = allOrigins();
        List<BiPredicate<Origin, Origin>> mutations = List.of(
                (a, b) -> a.incarnation() == b.incarnation()
                        && a.receiveLease() == b.receiveLease()
                        && a.sourceTask() == b.sourceTask()
                        && a.lexicalFrame() == b.lexicalFrame(),
                (a, b) -> a.actor() == b.actor()
                        && a.receiveLease() == b.receiveLease()
                        && a.sourceTask() == b.sourceTask()
                        && a.lexicalFrame() == b.lexicalFrame(),
                (a, b) -> a.actor() == b.actor()
                        && a.incarnation() == b.incarnation()
                        && a.sourceTask() == b.sourceTask()
                        && a.lexicalFrame() == b.lexicalFrame(),
                (a, b) -> a.actor() == b.actor()
                        && a.incarnation() == b.incarnation()
                        && a.receiveLease() == b.receiveLease()
                        && a.lexicalFrame() == b.lexicalFrame(),
                (a, b) -> a.actor() == b.actor()
                        && a.incarnation() == b.incarnation()
                        && a.receiveLease() == b.receiveLease()
                        && a.sourceTask() == b.sourceTask());
        for (int dimension = 0; dimension < mutations.size(); dimension++) {
            BiPredicate<Origin, Origin> unsafe = mutations.get(dimension);
            boolean found = false;
            for (Origin source : origins) {
                for (Origin destination : origins) {
                    if (!source.equals(destination) && unsafe.test(source, destination)) {
                        assertFalse(admitted(source, destination, false),
                                "unsafe omission of dimension " + dimension);
                        found = true;
                    }
                }
            }
            assertTrue(found, "each dropped field needs a concrete counterexample");
        }

        Origin identical = origins.getFirst();
        // Negative mutation: a check that omits retirement admits a
        // completely matching but already-revoked ticket.
        assertTrue(identical.equals(identical));
        assertFalse(admitted(identical, identical, true));
    }

    private static List<Origin> allOrigins() {
        List<Origin> states = new ArrayList<>();
        for (int actor = 1; actor <= 2; actor++)
            for (int incarnation = 1; incarnation <= 2; incarnation++)
                for (int receive = 1; receive <= 2; receive++)
                    for (int task = 1; task <= 2; task++)
                        for (int frame = 1; frame <= 2; frame++)
                            states.add(new Origin(actor, incarnation, receive, task, frame));
        return List.copyOf(states);
    }
}
