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
        System.out.println("ATTO_TRUSTED_ALLOC_SCOPE_JDK21_PASS");
    }
}
