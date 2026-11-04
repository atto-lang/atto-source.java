package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
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
        String canonical = normalizeUnitId(unitId);
        expunge();
        IdentityKey key = new IdentityKey(program, CLEARED);
        String previous = UNIT_IDS.putIfAbsent(key, canonical);
        if (previous != null && !previous.equals(canonical)) {
            throw new IllegalArgumentException(
                    "parsed program already has a different macro source-unit identity");
        }
        return program;
    }

    static String normalizeUnitId(String unitId) {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("invalid macro source-unit identity");
        }
        if (unitId.equals(MEMORY_UNIT)) return MEMORY_UNIT;

        // A path with many x/../ segments must not exhaust CPU or memory
        // while collapsing to an apparently short logical identifier.
        if (unitId.length() > MAX_UNIT_ID_CHARS * 4) {
            throw new IllegalArgumentException(
                    "macro source-unit identity input exceeds budget");
        }
        String logical = unitId.replace('\\', '/');
        if (logical.startsWith("/")
                || logical.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException(
                    "macro source-unit identity must be a logical relative path");
        }

        // Reject Windows drive-relative C:foo, NTFS alternate-stream syntax,
        // control characters and malformed UTF-16 before computing hygiene IDs.
        for (int i = 0; i < logical.length(); i++) {
            char current = logical.charAt(i);
            if (current == ':' || Character.isISOControl(current)
                    || Character.getType(current) == Character.LINE_SEPARATOR
                    || Character.getType(current) == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(
                        "invalid character in logical macro source-unit identity");
            }
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= logical.length()
                        || !Character.isLowSurrogate(logical.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            "invalid UTF-16 in logical macro source-unit identity");
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        "invalid UTF-16 in logical macro source-unit identity");
            }
        }

        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : logical.split("/+")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "macro source-unit identity cannot escape its logical root");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }

        String canonical = String.join("/", segments);
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
