package dev.oreslang.runtime;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorStartupPayloadTest {
    private static final ActorSpawnReservation.Charge UNIT = new ActorSpawnReservation.Charge(64, 1, 1, 1);
    private static ActorStartupPayload.Schema schema() {
        return new ActorStartupPayload.Schema(List.of(
            new ActorStartupPayload.Field("name", ActorStartupPayload.Kind.STRING),
            new ActorStartupPayload.Field("seed", ActorStartupPayload.Kind.INT),
            new ActorStartupPayload.Field("enabled", ActorStartupPayload.Kind.BOOL)));
    }
    private static List<ActorStartupPayload.Argument> args(Object name, Object seed, Object enabled) {
        return List.of(new ActorStartupPayload.Argument("seed", seed),
            new ActorStartupPayload.Argument("enabled", enabled),
            new ActorStartupPayload.Argument("name", name));
    }

    @Test void typedNominalScalarSlotIsOneShot() {
        var schema = schema();
        var payload = schema.bind(args("worker-1", 42L, true), 64);
        assertEquals(17, payload.bytes());
        var gate = new ActorSpawnReservation(UNIT);
        assertThrows(IllegalArgumentException.class, () -> gate.reserve(UNIT, schema(), payload));
        assertEquals(new ActorSpawnReservation.Usage(0,0,0,0), gate.usage());
        var p = gate.reserve(UNIT, schema, payload);
        var init = gate.beginInit(p);
        var foreign = new ActorSpawnReservation(UNIT);
        assertThrows(IllegalStateException.class, () -> foreign.takeStartup(init));
        var taken = gate.takeStartup(init);
        assertEquals("worker-1", taken.get(0));
        assertEquals(42L, taken.get(1));
        assertEquals(true, taken.get(2));
        assertThrows(IllegalStateException.class, () -> gate.takeStartup(init));
        assertTrue(gate.completeInit(init, true));
        assertThrows(IllegalStateException.class, () -> gate.takeStartup(init));
        gate.retire(p); gate.releaseAfterQuiescence(p);
    }

    @Test void rejectMutableAliasesInvalidTypesAndBudget() {
        var s = schema();
        assertThrows(IllegalArgumentException.class, () -> s.bind(args(new StringBuilder("mutable"),42L,true),64));
        assertThrows(IllegalArgumentException.class, () -> s.bind(args("name",42,true),64));
        assertThrows(IllegalArgumentException.class, () -> s.bind(args("name",42L,1L),64));
        assertThrows(IllegalArgumentException.class, () -> s.bind(args("name",42L,true),12));
        assertThrows(IllegalArgumentException.class, () -> s.bind(args("\uD800",42L,true),64));
        assertEquals(13, s.bind(args("\uD83D\uDE00",42L,true),64).bytes());
        assertThrows(IllegalArgumentException.class, () -> s.bind(List.of(),64));
        assertThrows(IllegalArgumentException.class, () -> s.bind(List.of(
            new ActorStartupPayload.Argument("seed",42L),
            new ActorStartupPayload.Argument("seed",43L),
            new ActorStartupPayload.Argument("name","x")),64));
        var gate = new ActorSpawnReservation(UNIT);
        assertThrows(IllegalArgumentException.class, () -> gate.reserve(
            new ActorSpawnReservation.Charge(1,1,1,1), s, s.bind(args("name",42L,true),64)));
        assertEquals(new ActorSpawnReservation.Usage(0,0,0,0), gate.usage());
    }

    @Test void retirementAndFailureFenceUntakenPayload() {
        var s = schema(); var payload = s.bind(args("worker",42L,true),64);
        var gate = new ActorSpawnReservation(UNIT);
        var p = gate.reserve(UNIT,s,payload); var init = gate.beginInit(p);
        gate.retire(p);
        assertThrows(IllegalStateException.class, () -> gate.takeStartup(init));
        assertFalse(gate.completeInit(init,true)); gate.releaseAfterQuiescence(p);
        var q = gate.reserve(UNIT,s,payload); var failed = gate.beginInit(q);
        assertFalse(gate.completeInit(failed,false));
        assertThrows(IllegalStateException.class, () -> gate.takeStartup(failed));
        gate.releaseAfterQuiescence(q);
        var r = gate.reserve(UNIT,s,payload);
        gate.retire(r); gate.releaseAfterQuiescence(r);
        assertEquals(new ActorSpawnReservation.Usage(0,0,0,0),gate.usage());
    }
}
