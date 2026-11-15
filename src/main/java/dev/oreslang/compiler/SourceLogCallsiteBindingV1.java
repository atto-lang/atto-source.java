package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.SourceIdentityV1;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * First Java-compiler-owned literal log.info AST site admission.
 * The parser produces AST CallExpr nodes; only those very same nodes receive
 * checked site descriptors. No guest log dispatch or Truffle probe injection
 * occurs here until the checked evaluator consumes this table.
 */
public final class SourceLogCallsiteBindingV1 {
    private SourceLogCallsiteBindingV1() { }

    public record Entry(String module, String function, int arity, String message,
                        String routineId, String siteId) { }

    private record Selector(String module, String function, int arity,
                            String message) { }
    private record RoutineSelector(String module, String function, int arity) { }

    /**
     * The only public entrypoint takes source-controlled routine rows, not an
     * arbitrary host-assembled Map that could forge a "verified" function ID.
     * Resolve exact callable identities independently on this same AST before
     * admitting any static log site. The raw identity map is never accepted
     * across this public API boundary.
     */
    public static Map<Ast.CallExpr, SourceIdentityV1.Site> bind(
            Ast.Program program, List<Entry> entries,
            List<SourceRoutineBindingV1.Entry> checkedRoutines) {
        Objects.requireNonNull(program, "Java guest compiler AST");
        Objects.requireNonNull(entries, "source-controlled log site rows");
        Objects.requireNonNull(checkedRoutines, "source-controlled routine rows");
        return bindExact(program, entries,
                SourceRoutineBindingV1.bind(program, checkedRoutines));
    }

    private static Map<Ast.CallExpr, SourceIdentityV1.Site> bindExact(
            Ast.Program program, List<Entry> entries,
            Map<Ast.FunctionDecl, SourceIdentityV1.Id> checkedRoutines) {
        Objects.requireNonNull(program, "checked native Java frontend AST");
        Objects.requireNonNull(entries, "checked source registry");
        Objects.requireNonNull(checkedRoutines, "independently verified routine IDs");
        if (checkedRoutines.isEmpty() || checkedRoutines.size() > 4096) {
            throw new IllegalArgumentException("exact verified function table required");
        }
        if (entries.size() > 4096) {
            throw new IllegalArgumentException("source log-site budget exceeded");
        }
        Map<Selector, SourceIdentityV1.Site> bySelector = new HashMap<>();
        var ids = new HashSet<SourceIdentityV1.Id>();
        var routineIds = new HashMap<RoutineSelector, String>();
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "source site entry");
            requireLiteral(entry.message());
            var rid = SourceIdentityV1.parse(entry.routineId(),
                    SourceIdentityV1.Kind.ROUTINE);
            var sid = SourceIdentityV1.parse(entry.siteId(),
                    SourceIdentityV1.Kind.SITE);
            if (!ids.add(sid)) {
                throw new IllegalArgumentException("duplicate source log-site ID");
            }
            if (entry.module() == null || entry.module().isBlank()
                    || entry.function() == null || entry.function().isBlank()
                    || entry.arity() < 0) {
                throw new IllegalArgumentException("invalid source log selector");
            }
            var functionKey = new RoutineSelector(entry.module(),
                    entry.function(), entry.arity());
            String previous = routineIds.putIfAbsent(functionKey, entry.routineId());
            if (previous != null && !previous.equals(entry.routineId())) {
                throw new IllegalArgumentException("one Java function cannot have multiple routine IDs");
            }
            var selector = new Selector(entry.module(), entry.function(),
                    entry.arity(), entry.message());
            if (bySelector.putIfAbsent(selector,
                    new SourceIdentityV1.Site(rid, sid, bySelector.size())) != null) {
                throw new IllegalArgumentException("ambiguous duplicated log literal in one routine");
            }
        }
        var bindings = new IdentityHashMap<Ast.CallExpr, SourceIdentityV1.Site>();
        int exactCallableCount = 0;
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl fn)) {
                    throw new IllegalArgumentException("Java literal log v1 supports scalar functions only");
                }
                exactCallableCount++;
                if (fn.async() || fn.suspends() || fn.generator()
                        || fn.actorKind() != Ast.ActorKind.NONE
                        || !fn.genericParameters().isEmpty()
                        || fn.parameters().stream().anyMatch(p -> "log".equals(p.name()))) {
                    throw new IllegalArgumentException(
                            "log-site tracing across suspension/actor turns requires proven continuation cleanup");
                }
                // SourceRoutineBindingV1.bind returns a read-only view over
                // an IdentityHashMap. Lookup is reference-identity-based and
                // avoids quadratic scans across many checked functions.
                SourceIdentityV1.Id checkedId = checkedRoutines.get(fn);
                if (checkedId == null || checkedId.kind() != SourceIdentityV1.Kind.ROUTINE) {
                    throw new IllegalArgumentException("missing exact independently checked function identity");
                }
                visit(fn.body(), module.name(), fn, checkedId,
                        bySelector, bindings, new HashSet<>());
            }
        }
        // Reject spurious/foreign routine records, even if the source has
        // valid entries for all its own functions.
        if (checkedRoutines.size() != exactCallableCount) {
            throw new IllegalArgumentException("foreign or stale verified routine AST entry");
        }
        if (!bySelector.isEmpty() || bindings.size() != entries.size()) {
            throw new IllegalArgumentException("stale or unbound source log-site descriptor");
        }
        return Collections.unmodifiableMap(bindings);
    }

    // Cold-path validation is shared only inside the Java frontend.
    // LLVM and Rust retain independent implementations and test suites.
    private static void requireLiteral(String message) {
        SourceTelemetryLiteralV1.requireLogMessage(message);
    }

    private static void requireSpanName(String name) {
        SourceTelemetryLiteralV1.requireSpanName(name);
    }

    private static void visit(
            List<Ast.Stmt> statements, String module, Ast.FunctionDecl fn,
            SourceIdentityV1.Id checkedRoutine,
            Map<Selector, SourceIdentityV1.Site> remaining,
            IdentityHashMap<Ast.CallExpr, SourceIdentityV1.Site> result,
            HashSet<String> liveSpanAliases) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.UsingTelemetrySpanStmt scope) {
                if ("log".equals(scope.binding())
                        || fn.parameters().stream().anyMatch(p -> scope.binding().equals(p.name()))
                        || !liveSpanAliases.add(scope.binding())) {
                    throw new IllegalArgumentException(
                            "telemetry span alias shadows a live owner, parameter or reserved log intrinsic");
                }
                if (!(scope.name() instanceof Ast.LiteralExpr value)
                        || !(value.value() instanceof String name)) {
                    throw new IllegalArgumentException(
                            "native log v1 scoped tracing needs a literal span name");
                }
                requireSpanName(name);
                try {
                    visit(scope.body(), module, fn, checkedRoutine,
                            remaining, result, liveSpanAliases);
                } finally {
                    liveSpanAliases.remove(scope.binding());
                }
            } else if (stmt instanceof Ast.BlockStmt block) {
                visit(block.body(), module, fn, checkedRoutine,
                        remaining, result, liveSpanAliases);
            } else if (stmt instanceof Ast.ExprStmt expression) {
                if (!(expression.expression() instanceof Ast.CallExpr call)) {
                    throw new IllegalArgumentException(
                            "log v1 only admits direct expression call statements");
                }
                if (call.callee() instanceof Ast.MemberExpr member
                        && member.receiver() instanceof Ast.NameExpr named
                        && named.name().equals("log")) {
                    if (!member.member().equals("info") || call.arguments().size() != 1
                            || call.typeArgumentsPresent()
                            || call.origin() != Ast.CallOrigin.DIRECT
                            || !(call.arguments().getFirst() instanceof Ast.LiteralExpr literal)
                            || !(literal.value() instanceof String message)) {
                        throw new IllegalArgumentException(
                                "log.info requires exactly one literal string argument");
                    }
                    requireLiteral(message);
                    var site = remaining.remove(new Selector(
                            module, fn.name(), fn.parameters().size(), message));
                    if (site == null || !site.routineId().equals(checkedRoutine)
                            || result.put(call, site) != null) {
                        throw new IllegalArgumentException(
                                "log site missing, forged routine, or duplicate in checked source registry");
                    }
                } else {
                    throw new IllegalArgumentException(
                            "unverified guest statement in strict literal log v1");
                }
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                // Do not overlook a log.info(...) in a nested return expression
                // where there is no checked static site descriptor.
                if (returned.value() != null
                        && !(returned.value() instanceof Ast.LiteralExpr)
                        && !(returned.value() instanceof Ast.NameExpr)) {
                    throw new IllegalArgumentException(
                            "unchecked return expression tree in strict log v1");
                }
            } else {
                throw new IllegalArgumentException(
                        "unsupported Java guest statement in strict literal log v1");
            }
        }
    }
}
