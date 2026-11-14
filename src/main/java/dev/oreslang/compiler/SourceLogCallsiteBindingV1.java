package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.SourceIdentityV1;

import java.nio.charset.StandardCharsets;
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

    public static Map<Ast.CallExpr, SourceIdentityV1.Site> bind(
            Ast.Program program, List<Entry> entries) {
        Objects.requireNonNull(program, "checked native Java frontend AST");
        Objects.requireNonNull(entries, "checked source registry");
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
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl fn)) {
                    throw new IllegalArgumentException("Java literal log v1 supports scalar functions only");
                }
                if (fn.async() || fn.suspends() || fn.generator()
                        || fn.actorKind() != Ast.ActorKind.NONE) {
                    throw new IllegalArgumentException(
                            "log-site tracing across suspension/actor turns requires proven continuation cleanup");
                }
                visit(fn.body(), module.name(), fn, bySelector, bindings);
            }
        }
        if (!bySelector.isEmpty() || bindings.size() != entries.size()) {
            throw new IllegalArgumentException("stale or unbound source log-site descriptor");
        }
        return Collections.unmodifiableMap(bindings);
    }

    private static void requireLiteral(String message) {
        if (message == null || message.isEmpty()
                || message.getBytes(StandardCharsets.UTF_8).length > 96) {
            throw new IllegalArgumentException("log.info needs a bounded static literal");
        }
        for (int i = 0; i < message.length(); ++i) {
            char c = message.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("log.info literal contains control character");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= message.length()
                        || !Character.isLowSurrogate(message.charAt(i + 1))) {
                    throw new IllegalArgumentException("log.info literal has invalid UTF-16 pair");
                }
                ++i;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("log.info literal has unpaired surrogate");
            }
        }
    }

    private static void visit(
            List<Ast.Stmt> statements, String module, Ast.FunctionDecl fn,
            Map<Selector, SourceIdentityV1.Site> remaining,
            IdentityHashMap<Ast.CallExpr, SourceIdentityV1.Site> result) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.UsingTelemetrySpanStmt scope) {
                if (!(scope.name() instanceof Ast.LiteralExpr value)
                        || !(value.value() instanceof String)) {
                    throw new IllegalArgumentException(
                            "native log v1 scoped tracing needs a literal span name");
                }
                visit(scope.body(), module, fn, remaining, result);
            } else if (stmt instanceof Ast.BlockStmt block) {
                visit(block.body(), module, fn, remaining, result);
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
                    if (site == null || result.put(call, site) != null) {
                        throw new IllegalArgumentException(
                                "log site missing or duplicated in checked source registry");
                    }
                } else {
                    throw new IllegalArgumentException(
                            "unverified guest statement in strict literal log v1");
                }
            } else if (!(stmt instanceof Ast.ReturnStmt)) {
                throw new IllegalArgumentException(
                        "unsupported Java guest statement in strict literal log v1");
            }
        }
    }
}
