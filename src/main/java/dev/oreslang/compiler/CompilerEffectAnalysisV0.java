package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Conservative source-admitted, no-codegen compiler analysis adapter v0.
 *
 * This is deliberately NOT a proof of transitive throws/raises/panics.
 * Only checked closed scalar literals, parameter references and direct
 * static calls can be marked known. Every unsupported surface is inconclusive.
 */
public final class CompilerEffectAnalysisV0 {
    private CompilerEffectAnalysisV0() { }

    public record Report(List<String> records, int exitStatus) {
        public Report { records = List.copyOf(records); }
    }

    private record Row(List<String> callees, boolean unknown) {
        private Row { callees = List.copyOf(callees); }
    }

    public static Report analyze(Ast.Program checked) {
        boolean globalUnknown = !checked.imports().isEmpty()
                || checked.namespace() != null
                || checked.modules().size() != 1;
        var rows = new TreeMap<String, Row>();
        int edges = 0;
        for (Ast.ModuleDecl module : checked.modules()) {
            if (!module.annotations().isEmpty() || !module.contracts().isEmpty()
                    || !module.name().equals("__root__")) {
                globalUnknown = true;
            }
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl fn)) {
                    globalUnknown = true;
                    continue;
                }
                if (rows.size() >= 4096)
                    throw new IllegalArgumentException("analysis v0 function budget exceeded");
                String key = key(fn.name(), fn.parameters().size());
                if (rows.containsKey(key))
                    throw new IllegalArgumentException("ambiguous analysis callable identity");
                List<String> calls = new ArrayList<>();
                boolean localKnown = functionKnown(fn, calls);
                if (edges > 30000 - calls.size())
                    throw new IllegalArgumentException("analysis v0 graph budget exceeded");
                edges += calls.size();
                rows.put(key, new Row(calls, !localKnown || !reportableKey(key)));
            }
        }
        Map<String, Boolean> resolved = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        List<String> output = new ArrayList<>();
        boolean incomplete = false;
        for (String key : rows.keySet()) {
            boolean unknown = globalUnknown || unknownClosure(key, rows, resolved, visiting, 0);
            incomplete |= unknown;
            output.add("ORES-ANALYZE-V0\t" + safeKey(key)
                    + "\tstatus=" + (unknown ? "inconclusive" : "known")
                    + "\tknown_effects=none");
        }
        if (rows.isEmpty()) {
            incomplete = true;
            output.add("ORES-ANALYZE-V0\tstatus=inconclusive\treason=no-callables");
        }
        return new Report(output, incomplete ? 2 : 0);
    }

    private static String key(String name, int arity) {
        return name + "/" + arity;
    }

    /** Escaped/truncated names are ambiguous in the public tabular report. */
    private static boolean reportableKey(String key) {
        if (key.isEmpty() || key.length() > 256) return false;
        for (int i = 0; i < key.length(); ++i) {
            char c = key.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '/'))
                return false;
        }
        return true;
    }

    private static String safeKey(String key) {
        StringBuilder out = new StringBuilder(Math.min(256, key.length()));
        for (int i = 0; i < key.length() && i < 256; ++i) {
            char c = key.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '/'
                    ? c : '?');
        }
        return out.toString();
    }

    private static boolean functionKnown(Ast.FunctionDecl fn, List<String> calls) {
        if (fn.kind() != Ast.CallableKind.FNC || fn.actorKind() != Ast.ActorKind.NONE
                || fn.async() || fn.suspends() || fn.generator()
                || fn.trapped() || fn.nonLexical() || fn.structural()
                || !fn.annotations().isEmpty() || !fn.genericParameters().isEmpty()
                || !fn.effects().isEmpty() || fn.returnType() == null
                || !scalarType(fn.returnType()) || fn.body().size() != 1
                || !(fn.body().getFirst() instanceof Ast.ReturnStmt ret)
                || ret.value() == null) return false;
        Set<String> params = new HashSet<>();
        for (Ast.Param param : fn.parameters()) {
            if (param.mutable() || param.structural()
                    || !scalarType(param.type()) || !params.add(param.name()))
                return false;
        }
        return expressionKnown(ret.value(), params, calls, 0);
    }

    private static boolean scalarType(Ast.TypeRef type) {
        return type != null && !type.inferArguments()
                && type.arguments().isEmpty()
                && (type.name().equals("int") || type.name().equals("bool"));
    }

    private static boolean expressionKnown(
            Ast.Expr expr, Set<String> parameters, List<String> calls, int depth) {
        if (depth > 64) return false;
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof BigInteger big)
                return big.bitLength() < 63;
            return value instanceof Integer || value instanceof Long
                    || value instanceof Boolean;
        }
        if (expr instanceof Ast.NameExpr name)
            return parameters.contains(name.name());
        if (!(expr instanceof Ast.CallExpr call)
                || !(call.callee() instanceof Ast.NameExpr name)
                || call.origin() != Ast.CallOrigin.DIRECT
                || call.typeArgumentsPresent() || !call.typeArguments().isEmpty())
            return false;
        for (Ast.Expr argument : call.arguments()) {
            if (!expressionKnown(argument, parameters, calls, depth + 1))
                return false;
        }
        if (calls.size() >= 30000) return false;
        calls.add(key(name.name(), call.arguments().size()));
        return true;
    }

    private static boolean unknownClosure(
            String key, Map<String, Row> rows,
            Map<String, Boolean> resolved, Set<String> visiting, int depth) {
        if (resolved.containsKey(key)) return resolved.get(key);
        if (depth >= 64 || !visiting.add(key)) return true;
        Row row = rows.get(key);
        if (row == null) {
            visiting.remove(key);
            return true;
        }
        boolean unknown = row.unknown();
        for (String callee : row.callees())
            unknown |= unknownClosure(callee, rows, resolved, visiting, depth + 1);
        visiting.remove(key);
        resolved.put(key, unknown);
        return unknown;
    }
}
