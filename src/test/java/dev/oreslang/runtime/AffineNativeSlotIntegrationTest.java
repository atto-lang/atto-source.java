package dev.oreslang.runtime;

import dev.oreslang.compiler.AffineDropPlanV1;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static dev.oreslang.compiler.AffineDropPlanV1.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only native slot execution of strict-source drop plans. NativeList is
 * a stand-in for an owned scalar-array payload; this is not an admitted
 * guest array ABI or a production plan interpreter. Cell close retires the
 * lexical reference, not the arena-lifetime payload.
 */
final class AffineNativeSlotIntegrationTest {
    private record Outcome(List<Long> retired, Long returned) { }

    private static Outcome execute(Plan plan, boolean flag) {
        var charged = new AtomicLong();
        Outcome outcome;
        try (var arena = new ActorNativeArena(65536, () -> {}, bytes -> {
            charged.addAndGet(bytes);
            return () -> charged.addAndGet(-bytes);
        })) {
            Map<Integer, ActorNativeArena.ValueCell> cells = new HashMap<>();
            var live = new HashSet<Integer>();
            var destinations = new HashSet<Integer>();
            var pending = new ArrayList<Instruction>();
            pending.add(plan.entry());
            for (int i = 0; i < pending.size(); i++) {
                assertTrue(i < 4096, "bounded plan traversal");
                var instruction = pending.get(i);
                if (instruction instanceof Transfer transfer) {
                    destinations.add(transfer.to());
                    pending.add(transfer.next());
                } else if (instruction instanceof Drop drop) {
                    pending.add(drop.next());
                } else if (instruction instanceof Branch branch) {
                    pending.add(branch.whenTrue());
                    pending.add(branch.whenFalse());
                }
            }
            for (Parameter parameter : plan.owners()) {
                assertEquals("int", parameter.elementType());
                var payload = arena.nativeList();
                payload.add((long) parameter.slot());
                assertNull(cells.put(parameter.slot(), arena.valueCell(payload, new Object())));
                assertTrue(live.add(parameter.slot()));
            }
            for (int slot : destinations) {
                assertNull(cells.put(slot, arena.valueCell(null, new Object())));
            }
            long allocated = arena.allocatedBytes();
            long committed = arena.committedBytes();
            var retired = new ArrayList<Long>();
            var instruction = plan.entry();
            int fuel = 4096;
            while (!(instruction instanceof Finish)) {
                assertTrue(--fuel > 0);
                if (instruction instanceof Transfer transfer) {
                    assertTrue(live.remove(transfer.from()));
                    assertTrue(live.add(transfer.to()));
                    var source = cells.get(transfer.from());
                    source.moveReferenceTo(cells.get(transfer.to()));
                    assertThrows(IllegalStateException.class, source::get);
                    instruction = transfer.next();
                } else if (instruction instanceof Drop drop) {
                    assertTrue(live.remove(drop.slot()));
                    var cell = cells.get(drop.slot());
                    var payload = assertInstanceOf(ActorNativeArena.NativeList.class, cell.get());
                    retired.add((Long) payload.get(0));
                    cell.close();
                    assertThrows(IllegalStateException.class, cell::get);
                    instruction = drop.next();
                } else if (instruction instanceof Branch branch) {
                    assertEquals("flag", branch.booleanParameter());
                    instruction = flag ? branch.whenTrue() : branch.whenFalse();
                } else {
                    fail("unknown instruction");
                }
                assertEquals(live.size(), arena.nativeReferenceCount());
                assertEquals(allocated, arena.allocatedBytes());
                assertEquals(committed, arena.committedBytes());
            }
            Long returned = null;
            if (plan.returnsOwner()) {
                assertTrue(live.remove(RETURN_SLOT));
                var cell = cells.get(RETURN_SLOT);
                var payload = assertInstanceOf(ActorNativeArena.NativeList.class, cell.get());
                returned = (Long) payload.get(0);
                cell.close();
            }
            assertTrue(live.isEmpty());
            assertEquals(0, arena.nativeReferenceCount());
            assertEquals(plan.owners().size(), retired.size() + (returned == null ? 0 : 1));
            assertEquals(retired.size(), new HashSet<>(retired).size());
            if (returned != null) assertFalse(retired.contains(returned));
            outcome = new Outcome(List.copyOf(retired), returned);
        }
        assertEquals(0L, charged.get(), "all arena charges released at teardown");
        return outcome;
    }

    @Test
    void checkedSourceDropsBothNativeSlotsInReverseOrder() {
        var plan = OresCompiler.elaborateAffineDropsStrictAtto("""
                fnc compute(Array<int> a, Array<int> b, bool flag): void {
                    return;
                }
                """);
        assertEquals(new Outcome(List.of(2L, 1L), null), execute(plan, false));
    }

    @Test
    void checkedSourceTransfersReturnBeforeRetiringRemainingSlot() {
        var plan = OresCompiler.elaborateAffineDropsStrictAtto("""
                fnc compute(Array<int> a, Array<int> b, bool flag): Array<int> {
                    return rt take a;
                }
                """);
        assertEquals(new Outcome(List.of(2L), 1L), execute(plan, true));
    }

    @Test
    void checkedSourceBranchesRetireOnlyTheUnreturnedOwner() {
        var plan = OresCompiler.elaborateAffineDropsStrictAtto("""
                fnc compute(Array<int> a, Array<int> b, bool flag): Array<int> {
                    do if flag then return rt take a; else return rt take b; fi;
                }
                """);
        assertEquals(new Outcome(List.of(2L), 1L), execute(plan, true));
        assertEquals(new Outcome(List.of(1L), 2L), execute(plan, false));
    }
}
