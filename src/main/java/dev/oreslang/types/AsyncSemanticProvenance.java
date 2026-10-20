package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Exact-call semantic proof for async lowering. */
public final class AsyncSemanticProvenance {
    public record Admission(String target, Types.Type result) {
        public Admission {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(result, "result");
        }
    }

    private final IdentityHashMap<Ast.CallExpr, Admission> byCall =
            new IdentityHashMap<>();
    private boolean sealed;

    AsyncSemanticProvenance() { }

    void record(Ast.CallExpr call, String target, Types.Type result) {
        if (sealed) throw new IllegalStateException("async semantic provenance is sealed");
        Objects.requireNonNull(call, "call");
        Admission admission = new Admission(target, result);
        if (byCall.putIfAbsent(call, admission) != null) {
            throw new IllegalStateException("duplicate async semantic proof for one call");
        }
    }

    AsyncSemanticProvenance seal() {
        sealed = true;
        return this;
    }

    static AsyncSemanticProvenance empty() {
        return new AsyncSemanticProvenance().seal();
    }

    public Admission admissionFor(Ast.CallExpr call) {
        return byCall.get(call);
    }

    public boolean contains(Ast.CallExpr call) {
        return byCall.containsKey(call);
    }

    public int callCount() { return byCall.size(); }
    public boolean sealed() { return sealed; }

    java.util.Set<Ast.CallExpr> calls() {
        return java.util.Collections.newSetFromMap(
                new IdentityHashMap<>(byCall.keySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                key -> key,
                                ignored -> Boolean.TRUE,
                                (a, b) -> a,
                                IdentityHashMap::new))));
    }
}
