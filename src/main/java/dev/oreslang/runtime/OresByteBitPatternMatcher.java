package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reference execution of compiler-validated, bounded byte/bit sequence
 * patterns over an immutable Bytes owner. This is shared by the Java guest
 * evaluator and focused host verification. It is NOT a substitute for strict
 * guest AOT lowering, lifetime analysis, or a backend-generated decision tree.
 */
public final class OresByteBitPatternMatcher {
    private OresByteBitPatternMatcher() {}

    /**
     * Null means mismatch; an empty map means match with no captures.
     * A forged named-tail AST is rejected *before* reading the subject.
     * Scalar bindings are created only after a complete prefix match.
     */
    public static Map<String, Object> captures(Ast.SequencePattern pattern, Object value) {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.tail() != null && !pattern.tail().equals("_")) {
            throw new IllegalArgumentException(
                    "named byte/bit tail requires checked loan and guard-failure cleanup");
        }
        if (!(value instanceof OresBytes owner)) return null;
        final int prefix = pattern.elements().size();
        final int size = owner.size();
        final long sourceUnits = pattern.unit() == Ast.SequenceUnit.BYTE
                ? size : (long) size * 8;
        if (prefix > sourceUnits || (pattern.tail() == null && prefix != sourceUnits))
            return null;

        // Check the entire literal decision plan before allocating *any*
        // scalar capture record. A failed arm after an early binding must not
        // create throwaway heap objects under heavy actor mailbox traffic.
        for (int i = 0; i < prefix; i++) {
            Ast.SequenceElement element = pattern.elements().get(i);
            if (element.literal() == null) continue;
            int scalar = pattern.unit() == Ast.SequenceUnit.BYTE
                    ? owner.byteAt(i)
                    : ((owner.byteAt(i >>> 3) >>> (7 - (i & 7))) & 1);
            if (element.literal() != scalar) return null;
        }

        // No source binders: exact and ignored-tail patterns succeed with
        // the same shared singleton map, without any capture allocation.
        LinkedHashMap<String, Object> matches = null;
        for (int i = 0; i < prefix; i++) {
            String name = pattern.elements().get(i).binding();
            if (name == null || name.equals("_")) continue;
            if (matches == null) matches = new LinkedHashMap<>();
            int scalar = pattern.unit() == Ast.SequenceUnit.BYTE
                    ? owner.byteAt(i)
                    : ((owner.byteAt(i >>> 3) >>> (7 - (i & 7))) & 1);
            matches.put(name, pattern.unit() == Ast.SequenceUnit.BYTE
                    ? (Object) (long) scalar : (Object) (scalar != 0));
        }
        return matches == null ? Map.of() : matches;
    }
}
