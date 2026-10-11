package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable state-machine refinement oracle for docs/formal/SerialActorTurn.tla.
 * All logical transitions are deterministic; failing seeds reproduce exactly.
 */
final class SerialActorTurnModelTest {
    private static final class Model {
        Object mail;
        final Map<SerialActorTurnGate.Ticket, Object> pending = new IdentityHashMap<>();
    }
    private record Issued(int gate, SerialActorTurnGate.Ticket ticket) {}

    @Test void randomizedTransitionsPreserveRefinementAndCancellationFences() {
        for (long seed : new long[] {0L, 1L, 5L, 37L, 99991L, 0x5eed_2026L}) {
            Random random = new Random(seed);
            SerialActorTurnGate[] gates = {new SerialActorTurnGate(), new SerialActorTurnGate()};
            Model[] models = {new Model(), new Model()};
            Object[] mail = {new Object(), new Object()};
            ArrayList<Issued> issued = new ArrayList<>();
            for (int step = 0; step < 20_000; step++) {
                int g = random.nextInt(2);
                Model model = models[g];
                SerialActorTurnGate gate = gates[g];
                int action = random.nextInt(100);
                if (action < 42) {
                    Object target = mail[random.nextInt(mail.length)];
                    if (model.mail != null && model.mail != target) {
                        assertThrows(IllegalStateException.class, () -> gate.hold(target),
                                "seed " + seed + ", step " + step);
                    } else {
                        var ticket = gate.hold(target);
                        assertNull(model.pending.put(ticket, target));
                        model.mail = target;
                        issued.add(new Issued(g, ticket));
                    }
                } else if (action < 91) {
                    if (!issued.isEmpty()) {
                        Issued pick = issued.get(random.nextInt(issued.size()));
                        boolean wasPending = model.pending.remove(pick.ticket()) != null;
                        SerialActorTurnGate.Completion expected;
                        if (!wasPending) expected = SerialActorTurnGate.Completion.STALE;
                        else if (model.pending.isEmpty()) {
                            model.mail = null;
                            expected = SerialActorTurnGate.Completion.RELEASED;
                        } else expected = SerialActorTurnGate.Completion.HELD;
                        assertEquals(expected, gate.complete(pick.ticket()),
                                "seed " + seed + ", step " + step);
                    } else assertEquals(SerialActorTurnGate.Completion.STALE, gate.complete(null));
                } else {
                    gate.retire();
                    model.pending.clear();
                    model.mail = null;
                }
                for (int i = 0; i < 2; i++) {
                    assertEquals(!models[i].pending.isEmpty(), gates[i].occupied(),
                            "occupied, seed " + seed + ", step " + step);
                    assertEquals(models[i].pending.size(), gates[i].pendingBarriers(),
                            "barriers, seed " + seed + ", step " + step);
                    assertEquals(models[i].mail == null, models[i].pending.isEmpty(),
                            "single flight, seed " + seed + ", step " + step);
                }
                // Bound history without allowing an old pending ticket to drop
                // out of the oracle's completion pool.
                if (issued.size() > 4_096) {
                    issued.removeIf(p -> !models[p.gate()].pending.containsKey(p.ticket()));
                }
            }
        }
    }
}
