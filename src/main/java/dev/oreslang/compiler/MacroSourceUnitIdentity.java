package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Compiler-only canonical source-unit identity attached to parsed programs.
 *
 * <p>The runtime AST/ABI remains unchanged. Incremental compilation attaches its
 * normalized unit id before macro planning so hygiene from different files in
 * the same module cannot collide merely because line/column and expansion
 * ordinals repeat. Direct in-memory parses use the explicit <memory> identity.</p>
 */
public final class MacroSourceUnitIdentity {
    public static final String MEMORY_UNIT = "<memory>";
    static final int MAX_UNIT_ID_CHARS = 4_096;

    private static final ReferenceQueue<Ast.Program> CLEARED = new ReferenceQueue<>();
    private static final Map<IdentityKey, String> UNIT_IDS = new HashMap<>();

    private MacroSourceUnitIdentity() { }

    private static final class IdentityKey extends WeakReference<Ast.Program> {
        private final int hash;

        private IdentityKey(
                Ast.Program program,
                ReferenceQueue<Ast.Program> queue) {
            super(program, queue);
            hash = System.identityHashCode(program);
        }

        @Override public int hashCode() { return hash; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey key)) return false;
            Ast.Program value = get();
            return value != null && value == key.get();
        }
    }

    private static void expunge() {
        Reference<? extends Ast.Program> collected;
        while ((collected = CLEARED.poll()) != null) {
            UNIT_IDS.remove(collected);
        }
    }

    public static synchronized Ast.Program attach(Ast.Program program, String unitId) {
        java.util.Objects.requireNonNull(program, "macro source program");
        String canonical = canonicalize(unitId);
        expunge();
        IdentityKey key = new IdentityKey(program, CLEARED);
        String previous = UNIT_IDS.putIfAbsent(key, canonical);
        if (previous != null && !previous.equals(canonical)) {
            throw new IllegalArgumentException(
                    "parsed program already has a different macro source-unit identity");
        }
        return program;
    }

    private static String canonicalize(String unitId) {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("invalid macro source-unit identity");
        }
        if (unitId.equals(MEMORY_UNIT)) return MEMORY_UNIT;
        final String canonical;
        try {
            canonical = Path.of(unitId).normalize().toString().replace('\\', '/');
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException(
                    "invalid macro source-unit identity", invalid);
        }
        if (canonical.isBlank() || canonical.length() > MAX_UNIT_ID_CHARS) {
            throw new IllegalArgumentException("invalid macro source-unit identity");
        }
        return canonical;
    }

    public static synchronized String unitId(Ast.Program program) {
        java.util.Objects.requireNonNull(program, "macro source program");
        expunge();
        String unitId = UNIT_IDS.get(new IdentityKey(program, null));
        return unitId == null ? MEMORY_UNIT : unitId;
    }
}
