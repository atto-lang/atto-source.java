package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Advisory compiler-issued seeds for the bounded evaluator-local copy cache.
 * No guest values, mutable identity maps or execution authority are retained.
 */
public final class CheckedCopyPlanSeeds {
    private CheckedCopyPlanSeeds() { }

    public record Seed(Ast.ClassDecl root, boolean shallow, DerivedCopyPlanV1.Plan plan,
                       Map<String, Ast.ClassDecl> dependencies) {
        public Seed {
            Objects.requireNonNull(root);
            Objects.requireNonNull(plan);
            // Null is meaningful: builtin names must still be absent.
            dependencies = Collections.unmodifiableMap(new HashMap<>(dependencies));
        }

        public boolean matches(Function<String, Ast.ClassDecl> resolver) {
            try {
                if (resolver.apply(root.name()) != root) return false;
                for (var entry : dependencies.entrySet()) {
                    if (resolver.apply(entry.getKey()) != entry.getValue()) return false;
                }
                return true;
            } catch (IllegalArgumentException ambiguous) {
                return false;
            }
        }
    }

    static final class Builder {
        private final List<Seed> seeds = new ArrayList<>();
        private final int[] weights = new int[2];
        private final int[] counts = new int[2];

        void add(Ast.ClassDecl root, boolean shallow, DerivedCopyPlanV1.Plan plan,
                 Map<String, Ast.ClassDecl> dependencies) {
            for (Seed seed : seeds) {
                if (seed.root() == root && seed.shallow() == shallow) return;
            }
            int mode = shallow ? 0 : 1;
            int cost = DerivedCopyPlanV1.weight(plan);
            if (counts[mode] >= 64 || cost > 32768 - weights[mode]) return;
            seeds.add(new Seed(root, shallow, plan, dependencies));
            counts[mode]++;
            weights[mode] += cost;
        }

        List<Seed> snapshot() { return List.copyOf(seeds); }
    }

    private record Snapshot(long epoch, List<Seed> seeds) { }
    private static final ReferenceQueue<Ast.Program> CLEARED = new ReferenceQueue<>();
    private static final Map<Key, Snapshot> BY_PROGRAM = new HashMap<>();

    private static final class Key extends WeakReference<Ast.Program> {
        private final int hash;
        Key(Ast.Program program, ReferenceQueue<Ast.Program> queue) {
            super(Objects.requireNonNull(program), queue);
            hash = System.identityHashCode(program);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof Key key && get() != null && get() == key.get();
        }
    }

    private static void expunge() {
        Reference<? extends Ast.Program> ref;
        while ((ref = CLEARED.poll()) != null) BY_PROGRAM.remove(ref);
    }

    private static boolean current(Ast.Program program, long epoch) {
        try {
            return epoch > 0 && Ast.CheckedTaskCaptures.requireProgramEpoch(program) == epoch;
        } catch (IllegalStateException unadmitted) {
            return false;
        }
    }

    static synchronized void publish(Ast.Program program, long epoch, List<Seed> seeds) {
        expunge();
        if (!current(program, epoch)) return;
        BY_PROGRAM.put(new Key(program, CLEARED), new Snapshot(epoch, List.copyOf(seeds)));
    }

    public static synchronized List<Seed> forProgram(Ast.Program program, long epoch) {
        expunge();
        if (!current(program, epoch)) return List.of();
        Snapshot snapshot = BY_PROGRAM.get(new Key(program, null));
        return snapshot != null && snapshot.epoch() == epoch ? snapshot.seeds() : List.of();
    }
}
