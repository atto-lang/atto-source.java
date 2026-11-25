package dev.oreslang.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent structural/ownership verification of the restricted affine
 * instruction tree. It does not attest source correspondence, authorize
 * native handles or certify the destructor implementation.
 */
final class AffineDropPlanVerifierV1 {
    private static final int MAX_SLOTS = 256;
    private static final int MAX_VISITS = 4096;

    private record Edge(AffineDropPlanV1.Instruction instruction,
                        ArrayList<Integer> live, BitSet issued, boolean returned) { }

    private AffineDropPlanVerifierV1() { }

    static void verify(AffineDropPlanV1.Plan plan) {
        if (plan == null) throw invalid("missing plan");
        if ((long) plan.owners().size() + plan.booleanParameters().size() > MAX_SLOTS) {
            throw invalid("parameter budget exceeded");
        }
        Set<String> names = new HashSet<>();
        Set<String> flags = new HashSet<>();
        var live = new ArrayList<Integer>();
        var issued = new BitSet(MAX_SLOTS + 1);
        for (String flag : plan.booleanParameters()) {
            if (flag == null || flag.isBlank() || !names.add(flag)) {
                throw invalid("invalid or duplicate boolean parameter");
            }
            flags.add(flag);
        }
        for (var owner : plan.owners()) {
            if (owner == null || owner.name() == null || owner.name().isBlank()
                    || !names.add(owner.name()) || !Set.of("int", "i32").contains(owner.elementType())) {
                throw invalid("invalid or duplicate affine parameter");
            }
            requireSlot(owner.slot());
            if (issued.get(owner.slot())) throw invalid("duplicate owner slot");
            issued.set(owner.slot());
            live.add(owner.slot());
        }

        var pending = new ArrayDeque<Edge>();
        pending.push(new Edge(plan.entry(), live, issued, false));
        int visits = 0;
        while (!pending.isEmpty()) {
            Edge edge = pending.pop();
            var instruction = edge.instruction();
            live = edge.live();
            issued = edge.issued();
            boolean returned = edge.returned();
            if (++visits > MAX_VISITS) throw invalid("instruction visit budget exceeded");
            if (instruction instanceof AffineDropPlanV1.Transfer transfer) {
                if (returned) throw invalid("transfer after return publication");
                requireSlot(transfer.from());
                if (!live.remove(Integer.valueOf(transfer.from()))) {
                    throw invalid("transfer from dead slot");
                }
                if (transfer.to() == AffineDropPlanV1.RETURN_SLOT) {
                    if (!plan.returnsOwner()) throw invalid("return transfer in void plan");
                    returned = true;
                } else {
                    requireSlot(transfer.to());
                    if (issued.get(transfer.to())) throw invalid("slot reused on one path");
                    issued.set(transfer.to());
                    live.add(transfer.to());
                }
                pending.push(new Edge(transfer.next(), live, issued, returned));
            } else if (instruction instanceof AffineDropPlanV1.Drop drop) {
                requireSlot(drop.slot());
                if (live.isEmpty() || live.getLast() != drop.slot()) {
                    throw invalid("drop of dead slot or out-of-order destruction");
                }
                live.removeLast();
                pending.push(new Edge(drop.next(), live, issued, returned));
            } else if (instruction instanceof AffineDropPlanV1.Branch branch) {
                if (returned || !flags.contains(branch.booleanParameter())) {
                    throw invalid("invalid branch or branch after return publication");
                }
                pending.push(new Edge(branch.whenFalse(), new ArrayList<>(live),
                        (BitSet) issued.clone(), false));
                pending.push(new Edge(branch.whenTrue(), live, issued, false));
            } else if (instruction instanceof AffineDropPlanV1.Finish) {
                if (!live.isEmpty()) throw invalid("live owner at finish");
                if (returned != plan.returnsOwner()) throw invalid("missing owned return");
            } else {
                throw invalid("missing or unrecognized instruction");
            }
        }
    }

    private static void requireSlot(int slot) {
        if (slot < 1 || slot > MAX_SLOTS) throw invalid("invalid affine slot");
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("AFFINE_DROP_VERIFY_V1: " + reason);
    }
}
