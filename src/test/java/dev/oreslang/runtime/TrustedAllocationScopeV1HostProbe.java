package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Self-contained JDK21 runtime admission probe using real ActorLinearArena. */
public final class TrustedAllocationScopeV1HostProbe {
    private TrustedAllocationScopeV1HostProbe() { }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    private static void rejects(Runnable fn, String reason) {
        try { fn.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("expected fail-closed rejection: " + reason);
    }

    private static void normalAndEarlyDrop() {
        try (ActorLinearArena arena = new ActorLinearArena(128, 8)) {
            List<Integer> order = new ArrayList<>();
            TrustedAllocationScopeV1.Owner first;
            try (var scope = TrustedAllocationScopeV1.trusted_open(arena)) {
                first = scope.allocate_word(() -> order.add(1));
                var second = scope.allocate_word(() -> order.add(2));
                var third = scope.allocate_word(() -> order.add(3));
                try (var writer = first.borrow_write()) {
                    writer.write_word(42);
                    check(writer.read_word() == 42, "native write/read failed");
                }
                try (var reader = first.borrow_read()) {
                    check(reader.read_word() == 42, "native read failed");
                    rejects(() -> reader.write_word(99), "read-only borrow");
                }
                scope.drop(second);
                rejects(() -> scope.drop(second), "double destructor");
                rejects(second::borrow_read, "retired owner");
                check(order.equals(List.of(2)), "early drop out of order");
                check(!first.is_retired() && !third.is_retired(), "other owners revoked");
            }
            check(order.equals(List.of(2, 3, 1)), "reverse acquisition cleanup");
            check(first.is_retired() && arena.liveAllocations() == 0, "native owners leaked");
        }
    }

    private static void loansAndConfinement() throws Exception {
        try (ActorLinearArena arena = new ActorLinearArena(64, 4)) {
            var scope = TrustedAllocationScopeV1.trusted_open(arena);
            var owner = scope.allocate_word(() -> {});
            var reader = owner.borrow_read();
            var secondReader = owner.borrow_read();
            rejects(owner::borrow_write, "writer blocked by readers");
            rejects(() -> scope.drop(owner), "cannot drop borrowed memory");
            rejects(scope::close, "cannot close scope with active loans");
            reader.close();
            secondReader.close();
            rejects(reader::read_word, "stale read guard");
            rejects(reader::close, "double read release");
            try (var writer = owner.borrow_write()) {
                rejects(owner::borrow_read, "read blocked by writer");
                rejects(owner::borrow_write, "duplicate writer");
                writer.write_word(919);
            }
            AtomicReference<Throwable> seen = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try { owner.borrow_read(); }
                catch (Throwable ex) { seen.set(ex); }
            });
            foreign.start();
            foreign.join();
            check(seen.get() instanceof IllegalStateException, "cross-carrier access admitted");
            scope.close();
            rejects(owner::borrow_read, "scope completed");
            rejects(scope::close, "duplicate scope cleanup");
            check(arena.liveAllocations() == 0, "borrowed native arena leaked");
        }
    }

    private static void unwindAndPoison() {
        try (ActorLinearArena arena = new ActorLinearArena(64, 4)) {
            List<Integer> drops = new ArrayList<>();
            try {
                try (var scope = TrustedAllocationScopeV1.trusted_open(arena)) {
                    scope.allocate_word(() -> drops.add(1));
                    scope.allocate_word(() -> drops.add(2));
                    throw new IllegalArgumentException("simulated guest exception");
                }
            } catch (IllegalArgumentException expected) {
                check("simulated guest exception".equals(expected.getMessage()), "wrong unwind error");
            }
            check(drops.equals(List.of(2, 1)) && arena.liveAllocations() == 0,
                    "exceptional unwind did not clean in reverse order");
            try {
                try (var scope = TrustedAllocationScopeV1.trusted_open(arena)) {
                    scope.allocate_word(() -> drops.add(3));
                    scope.allocate_word(() -> { drops.add(4); throw new IllegalStateException("drop failed"); });
                    scope.allocate_word(() -> drops.add(5));
                }
                throw new AssertionError("destructor error was lost");
            } catch (IllegalStateException expected) {
                check("drop failed".equals(expected.getMessage()), "wrong destructor error");
            }
            check(drops.equals(List.of(2, 1, 5, 4, 3)), "failing destructor interrupted cleanup");
            check(arena.liveAllocations() == 1, "poisoned owner was silently recycled");
        }
    }

    private static void destructorReentrancyAndQuarantine() {
        try (ActorLinearArena arena = new ActorLinearArena(64, 4)) {
            List<Integer> observed = new ArrayList<>();
            var scope = TrustedAllocationScopeV1.trusted_open(arena);
            var normal = scope.allocate_word(() -> observed.add(1));
            var holder = new TrustedAllocationScopeV1.Owner[1];
            holder[0] = scope.allocate_word(() -> {
                rejects(() -> scope.allocate_word(() -> {}), "no allocation in destructor");
                rejects(() -> scope.drop(normal), "no reentrant owner drop");
                rejects(holder[0]::borrow_read, "no borrow during destructor");
                observed.add(2);
            });
            scope.close();
            check(observed.equals(List.of(2, 1)), "destructor dispatch order or reentry gate");
            check(arena.liveAllocations() == 0, "reentrancy test left physical bytes live");
        }
        try (ActorLinearArena arena = new ActorLinearArena(64, 4)) {
            var scope = TrustedAllocationScopeV1.trusted_open(arena);
            var poisoned = scope.allocate_word(() -> {
                throw new IllegalStateException("destructor failed");
            });
            rejects(() -> scope.drop(poisoned), "failed destructor must propagate");
            check(arena.liveAllocations() == 1, "poisoned bytes recycled");
            rejects(() -> scope.drop(poisoned), "poisoned owner cannot be dropped twice");
            rejects(() -> poisoned.borrow_write(), "poisoned owner cannot be borrowed");
            rejects(scope::close, "poisoned owner must not be marked retired");
            check(arena.liveAllocations() == 1, "unacknowledged deallocation");
        }
    }


    private static void lexicalCleanupAndBoundedOwnerMetadata() {
        try (ActorLinearArena arena = new ActorLinearArena(128, 8)) {
            List<Integer> order = new ArrayList<>();
            var scope = TrustedAllocationScopeV1.trusted_open(arena);
            var outer = scope.allocate_word(() -> order.add(1));
            var outerMark = scope.begin_lexical_scope();
            var innerA = scope.allocate_word(() -> order.add(2));
            var nestedMark = scope.begin_lexical_scope();
            var innerB = scope.allocate_word(() -> order.add(3));
            var guard = innerB.borrow_read();
            rejects(() -> scope.end_lexical_scope(nestedMark),
                    "cannot finish lexical scope while borrowed");
            rejects(() -> scope.end_lexical_scope(outerMark),
                    "out-of-order nested lexical scope");
            check(order.isEmpty(), "borrow preflight ran a destructor");
            guard.close();
            scope.end_lexical_scope(nestedMark);
            rejects(() -> scope.end_lexical_scope(nestedMark), "checkpoint replay");
            rejects(innerB::borrow_read, "stale owner after lexical cleanup");
            check(order.equals(List.of(3)), "inner scope order");
            scope.drop(innerA);
            scope.end_lexical_scope(outerMark);
            check(order.equals(List.of(3, 2)), "early drop was duplicated");
            check(scope.tracked_owner_count() == 1, "early drop metadata leaked");
            for (int i = 0; i < 4000; i++) {
                var next = scope.allocate_word(() -> {});
                scope.drop(next);
                check(scope.tracked_owner_count() == 1, "tracking grows across early drops");
            }
            check(!outer.is_retired(), "outer owner retired by inner cleanup");
            scope.close();
            check(order.equals(List.of(3, 2, 1)), "outer destructor missing");
            check(arena.liveAllocations() == 0, "native cleanup left live allocations");
        }
        try (ActorLinearArena arena = new ActorLinearArena(64, 4)) {
            List<Integer> order = new ArrayList<>();
            var scope = TrustedAllocationScopeV1.trusted_open(arena);
            scope.allocate_word(() -> order.add(1));
            var mark = scope.begin_lexical_scope();
            try {
                scope.allocate_word(() -> order.add(2));
                scope.allocate_word(() -> order.add(3));
                throw new IllegalArgumentException("guest early exit");
            } catch (IllegalArgumentException expected) {
                scope.end_lexical_scope(mark);
            }
            check(order.equals(List.of(3, 2)), "exceptional lexical unwind failed");
            scope.close();
            check(order.equals(List.of(3, 2, 1)), "outer frame cleanup after unwind");
            check(arena.liveAllocations() == 0, "exceptional lexical cleanup leaked");
        }
    }
    private static void lexicalFailureStillDropsOtherOwners() {
        try (ActorLinearArena arena = new ActorLinearArena(64, 4)) {
            var observed = new ArrayList<Integer>();
            var scope = TrustedAllocationScopeV1.trusted_open(arena);
            var mark = scope.begin_lexical_scope();
            scope.allocate_word(() -> observed.add(1));
            scope.allocate_word(() -> {
                observed.add(2);
                throw new IllegalStateException("lexical destructor failed");
            });
            scope.allocate_word(() -> observed.add(3));
            rejects(() -> scope.end_lexical_scope(mark),
                    "lexical destructor error must surface");
            check(observed.equals(List.of(3, 2, 1)),
                    "lexical failure must not suppress unaffected destructors");
            rejects(() -> scope.end_lexical_scope(mark),
                    "failed lexical checkpoint must still be one-shot");
            check(arena.liveAllocations() == 1,
                    "failed destructor must poison and quarantine real allocation");
            rejects(scope::close, "poisoned owner must be reported at final scope exit");
        }
    }

    private static void admissionAndForeignOwner() {
        try (ActorLinearArena arena = new ActorLinearArena(8, 1)) {
            var first = TrustedAllocationScopeV1.trusted_open(arena);
            var second = TrustedAllocationScopeV1.trusted_open(arena);
            var owner = first.allocate_word(() -> {});
            rejects(() -> second.drop(owner), "foreign scope must not drop owner");
            rejects(() -> second.allocate_word(() -> {}), "no extra native capacity");
            second.close();
            first.close();
            check(arena.liveAllocations() == 0, "failed admission leaked bytes");
        }
    }

    public static void main(String[] ignored) throws Exception {
        normalAndEarlyDrop();
        loansAndConfinement();
        unwindAndPoison();
        admissionAndForeignOwner();
        destructorReentrancyAndQuarantine();
        lexicalCleanupAndBoundedOwnerMetadata();
        lexicalFailureStillDropsOtherOwners();
        System.out.println("ATTO_TRUSTED_ALLOC_SCOPE_JDK21_PASS");
    }
}
