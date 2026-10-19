package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Compiler-minted resolved semantic half-proof for detached Task transfers.
 *
 * <p>Entries are created only by {@link TypeChecker} after resolving the exact
 * transfer TypeRef recorded by {@link TaskTransferProvenance} and applying the
 * canonical {@link ThreadSafetyTraits} owned-Send/shared-safety checks.</p>
 */
public final class TaskSemanticAdmission {
    private static final int MAX_BOUNDARIES = 4_096;
    private static final int MAX_ENTRIES_PER_BOUNDARY = 4_096;
    private static final int MAX_TOTAL_ENTRIES = 4_096;
    private static final int MAX_NAME_CHARS = 256;

    public static final class Entry {
        private final String name;
        private final Types.Type type;

        Entry(String name, Types.Type type) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank() || name.length() > MAX_NAME_CHARS) {
                throw new IllegalArgumentException(
                        "invalid Task semantic admission name");
            }
            this.type = Objects.requireNonNull(type, "type");
        }

        public String name() { return name; }
        public Types.Type type() { return type; }
    }

    private final Ast.Program programIdentity;
    private final IdentityHashMap<Object, List<Entry>> byBoundary =
            new IdentityHashMap<>();
    private int totalEntries;
    private boolean sealed;

    TaskSemanticAdmission() { this(null); }

    TaskSemanticAdmission(Ast.Program programIdentity) {
        this.programIdentity = programIdentity;
    }

    Ast.Program programIdentity() { return programIdentity; }

    void record(Object boundary, List<Entry> entries) {
        if (sealed) {
            throw new IllegalStateException(
                    "Task semantic admission is already sealed");
        }
        Objects.requireNonNull(boundary, "boundary");
        List<Entry> copy =
                List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (copy.size() > MAX_ENTRIES_PER_BOUNDARY
                || byBoundary.size() >= MAX_BOUNDARIES
                || copy.size() > MAX_TOTAL_ENTRIES - totalEntries) {
            throw new IllegalStateException(
                    "Task semantic admission capacity exceeded");
        }
        if (byBoundary.containsKey(boundary)) {
            throw new IllegalStateException(
                    "duplicate Task semantic admission for one AST boundary");
        }
        byBoundary.put(boundary, copy);
        totalEntries += copy.size();
    }

    TaskSemanticAdmission seal() {
        sealed = true;
        return this;
    }

    List<Entry> entriesForIdentity(Object boundary) {
        List<Entry> entries = byBoundary.get(boundary);
        return entries == null ? List.of() : entries;
    }

    public List<Entry> entriesFor(Ast.Stmt boundary) {
        return entriesForIdentity(boundary);
    }

    public List<Entry> entriesFor(Ast.Expr boundary) {
        return entriesForIdentity(boundary);
    }

    public int boundaryCount() { return byBoundary.size(); }
    public int entryCount() { return totalEntries; }
    public boolean sealed() { return sealed; }
}
