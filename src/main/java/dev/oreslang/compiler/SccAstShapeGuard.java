package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Fail-closed AST immutability preflight for verifier-issued SCC results.
 *
 * AST records copy their list members, but a LiteralExpr or LiteralPattern
 * can still carry ANY Object, including mutable byte arrays, collection
 * instances, and arbitrary user classes. Those values must not survive into
 * a verified result because they can change after the input map snapshot.
 *
 * This intentionally accepts only first-party immutable AST records,
 * immutable scalar literal payloads and AST-owned lists. Unknown node types
 * and mutable literal objects require explicit trusted lowering before SCC
 * admission. It is not a deep clone or a parser authenticity proof.
 */
public final class SccAstShapeGuard {
    private static final int MAX_NODES = 100_000;
    private static final int MAX_DEPTH = 256;

    private SccAstShapeGuard() { }

    public static void requireImmutable(Ast.Program program) {
        if (program == null) throw new IllegalArgumentException("missing AST");
        new Walker().visit(program, 0);
    }

    private static final class Walker {
        private int seenNodes;
        private final IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();

        private static void requireFrozenLiteral(Object value) {
            if (value == null || value instanceof String || value instanceof Boolean
                    || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double
                    || value instanceof Character
                    || value instanceof BigInteger || value instanceof BigDecimal
                    || value instanceof Ast.Imaginary) {
                return;
            }
            throw new IllegalArgumentException(
                    "untrusted or mutable AST literal payload must be lowered before admission");
        }

        private void visit(Object node, int depth) {
            if (node == null) return;
            if (++seenNodes > MAX_NODES || depth > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "AST verification node/depth budget exceeded");
            }
            if (node instanceof String || node instanceof Boolean
                    || node instanceof Byte || node instanceof Short
                    || node instanceof Integer || node instanceof Long
                    || node instanceof Float || node instanceof Double
                    || node instanceof Character
                    || node instanceof BigInteger || node instanceof BigDecimal
                    || node instanceof Enum<?>) {
                return;
            }
            if (node instanceof Ast.LiteralExpr literal) {
                requireFrozenLiteral(literal.value());
                return;
            }
            if (node instanceof Ast.LiteralPattern literal) {
                requireFrozenLiteral(literal.value());
                return;
            }
            if (visiting.put(node, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("cyclic AST value is not immutable");
            }
            try {
                if (node instanceof List<?> list) {
                    for (Object child : list) visit(child, depth + 1);
                    return;
                }
                Class<?> type = node.getClass();
                if (!type.isRecord()
                        || !type.getName().startsWith("dev.oreslang.ast.Ast$")) {
                    throw new IllegalArgumentException(
                            "mutable or unsupported AST payload: " + type.getName());
                }
                for (RecordComponent component : type.getRecordComponents()) {
                    Object child;
                    try {
                        child = component.getAccessor().invoke(node);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        throw new IllegalArgumentException(
                                "could not inspect AST component", e);
                    }
                    visit(child, depth + 1);
                }
            } finally {
                visiting.remove(node);
            }
        }
    }
}
