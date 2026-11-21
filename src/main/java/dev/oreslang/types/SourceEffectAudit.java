package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative opt-in AST effect audit AFTER the ordinary source type and
 * ownership checks. It makes no claim of certified HIR-level call resolution.
 *
 * A zero effect row here is limited to an intentionally tiny syntactic subset.
 * Calls, operators, ambient names and unclassified AST nodes fail closed.
 * Effects in deferred/lambda bodies are included eagerly as an overapproximation;
 * this is not yet a latent-effect type system.
 */
public final class SourceEffectAudit {
    private SourceEffectAudit() { }

    private static final int MAX_VISITS = 100_000;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_DECLARATIONS = 1024;

    /**
     * Compiler-produced inspection result. No public constructor: a caller
     * must not mint a syntactically pure report from arbitrary map values.
     * The source digest is an identity binding, NOT a signed codegen proof.
     */
    public static final class Report {
        private final Map<String, Set<EffectContracts.Effect>> functions;
        private final List<String> unsupportedDeclarations;
        private final String sourceSha256;

        private Report(Map<String, Set<EffectContracts.Effect>> functions,
                List<String> unsupportedDeclarations, String sourceSha256) {
            Map<String, Set<EffectContracts.Effect>> snapshot = new LinkedHashMap<>();
            functions.forEach((key, value) -> snapshot.put(key, Set.copyOf(value)));
            this.functions = Collections.unmodifiableMap(snapshot);
            this.unsupportedDeclarations = List.copyOf(unsupportedDeclarations);
            this.sourceSha256 = sourceSha256;
        }

        public Map<String, Set<EffectContracts.Effect>> functions() { return functions; }
        public List<String> unsupportedDeclarations() { return unsupportedDeclarations; }
        public String sourceSha256() { return sourceSha256; }

        /** A limited source-analysis fact, not authority to execute effects. */
        public boolean conservativelyEffectFree() {
            return sourceSha256 != null
                    && !functions.isEmpty()
                    && unsupportedDeclarations.isEmpty()
                    && functions.values().stream().allMatch(Set::isEmpty);
        }

        @Override
        public String toString() {
            return "SourceEffectAudit.Report{sourceSha256=" + sourceSha256
                    + ", functions=" + functions
                    + ", unsupportedDeclarations=" + unsupportedDeclarations + "}";
        }
    }

    /** Parse, typecheck and ownership-check before attaching source identity. */
    public static Report inspect(String source) {
        java.util.Objects.requireNonNull(source, "source");
        var checked = OresCompiler.parseAndTypeCheckForEffectAudit(source);
        Report facts = inspectChecked(checked.program(), checked.resolvedLocalCalls());
        return new Report(facts.functions, facts.unsupportedDeclarations,
                sourceDigest(source));
    }

    private static String sourceDigest(String source) {
        try {
            // String#getBytes(UTF_8) silently substitutes malformed UTF-16.
            // Two different source strings could then have the same digest.
            // Fail closed instead of minting a misleading source identity.
            var utf8 = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(source));
            byte[] exact = new byte[utf8.remaining()];
            utf8.get(exact);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(exact);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw new IllegalArgumentException("source contains invalid UTF-16", malformed);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("mandatory SHA-256 digest unavailable", unavailable);
        }
    }

    private static Report inspectChecked(Ast.Program checked) {
        return inspectChecked(checked, Map.of());
    }

    private static Report inspectChecked(Ast.Program checked,
            Map<Ast.CallExpr, Ast.FunctionDecl> resolvedLocalCalls) {
        Map<String, Set<EffectContracts.Effect>> functions = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();

        // Imported functions and ambient module initializers are not yet
        // authenticated or sealed against future changes.
        if (!checked.imports().isEmpty()) {
            unsupported.add("imports require verified effect metadata");
        }
        if (checked.modules().isEmpty()) {
            unsupported.add("empty source cannot establish a compilation unit effect proof");
        }
        if (checked.modules().size() > MAX_DECLARATIONS) {
            throw new IllegalArgumentException("effect module budget exceeded");
        }
        VisitBudget budget = new VisitBudget();
        Map<Ast.FunctionDecl, String> symbols = new IdentityHashMap<>();
        // Only target declarations in this exact checked compilation unit can
        // become static graph edges. No textual name or imported declaration
        // is sufficient for an effect-clearance fact.
        for (Ast.ModuleDecl module : checked.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    symbols.put(fn, module.name() + "." + fn.name() + "/"
                            + fn.parameters().size() + ":" + fn.kind());
                }
            }
        }
        EffectContracts.Graph graph = new EffectContracts.Graph();
        int declarations = 0;
        for (Ast.ModuleDecl module : checked.modules()) {
            if (module.declarations().size() > MAX_DECLARATIONS - declarations) {
                throw new IllegalArgumentException("effect declaration budget exceeded");
            }
            declarations += module.declarations().size();
            if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
                unsupported.add(module.name() + ": module annotations/contracts need effect elaboration");
            }
            if (module.declarations().isEmpty()) {
                unsupported.add(module.name() + ": no analyzable declarations");
            }
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FunctionDecl fn)) {
                    unsupported.add(module.name() + ": unclassified "
                            + decl.getClass().getSimpleName());
                    continue;
                }
                String symbol = module.name() + "." + fn.name() + "/"
                        + fn.parameters().size() + ":" + fn.kind();
                EnumSet<EffectContracts.Effect> direct =
                        EnumSet.noneOf(EffectContracts.Effect.class);
                if (fn.async() || fn.suspends() || fn.generator()) {
                    direct.add(EffectContracts.Effect.SCHEDULER);
                    direct.add(EffectContracts.Effect.SUSPEND);
                }
                if (!fn.annotations().isEmpty() || fn.kind() != Ast.CallableKind.FNC
                        || fn.structural() || fn.nonLexical()
                        || !fn.genericParameters().isEmpty() || !fn.genericSpecs().isEmpty()) {
                    // Annotation and routine semantics are not yet certified
                    // by the closed-subset effect elaborator.
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
                if (fn.actorKind() != Ast.ActorKind.NONE) {
                    direct.add(EffectContracts.Effect.ACTOR);
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
                // Callable-level trap metadata is not a verified handler proof.
                // Never use an annotation to subtract ordinary THROW here.
                if (fn.trapped()) direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                List<EffectContracts.Call> calls = new ArrayList<>();
                Walker walker = new Walker(direct, budget, calls, resolvedLocalCalls, symbols);
                walker.walk(fn.body(), 0);
                if (functions.putIfAbsent(symbol, Set.copyOf(direct)) != null) {
                    throw new IllegalArgumentException("effect symbol collision: " + symbol);
                }
                graph.add(EffectContracts.Callable.inferred(symbol, direct, calls));
            }
        }
        // Fixed-point effect inference includes recursive locally bound
        // functions; unknown callbacks never become trusted direct calls.
        return new Report(graph.infer(), unsupported, null);
    }

    private static final class VisitBudget {
        private int visits;
    }

    private static final class Walker {
        private final EnumSet<EffectContracts.Effect> direct;
        private final VisitBudget budget;
        private final List<EffectContracts.Call> calls;
        private final Map<Ast.CallExpr, Ast.FunctionDecl> resolvedLocalCalls;
        private final Map<Ast.FunctionDecl, String> symbols;

        private Walker(EnumSet<EffectContracts.Effect> direct, VisitBudget budget,
                List<EffectContracts.Call> calls,
                Map<Ast.CallExpr, Ast.FunctionDecl> resolvedLocalCalls,
                Map<Ast.FunctionDecl, String> symbols) {
            this.direct = direct;
            this.budget = budget;
            this.calls = calls;
            this.resolvedLocalCalls = resolvedLocalCalls;
            this.symbols = symbols;
        }

        private void walk(Object node, int depth) {
            if (node == null) return;
            if (++budget.visits > MAX_VISITS || depth > MAX_DEPTH) {
                throw new IllegalArgumentException("effect AST traversal budget exceeded");
            }
            if (node instanceof List<?> nodes) {
                for (Object child : nodes) walk(child, depth + 1);
                return;
            }
            if (node instanceof Ast.CallExpr call) {
                Ast.FunctionDecl target = resolvedLocalCalls.get(call);
                String checkedTarget = target == null ? null : symbols.get(target);
                if (checkedTarget != null) {
                    // Checked direct function callee is a static slot, not
                    // an ambient executable receiver or dynamic property.
                    // Arguments/spreads still execute and must be audited.
                    calls.add(EffectContracts.Call.direct(checkedTarget));
                    walk(call.arguments(), depth + 1);
                    return;
                }
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.LiteralExpr literal) {
                // Parser-supplied primitive constants are the only audited
                // zero-effect literals. All other values, including aggregate
                // or runtime objects carried by a synthetic literal, require
                // an explicit compiler lowering proof before treating them
                // as effect-free. String/big-number materialization may alloc.
                Object value = literal.value();
                if (value instanceof String || value instanceof java.math.BigInteger
                        || value instanceof java.math.BigDecimal) {
                    direct.add(EffectContracts.Effect.ALLOC);
                } else if (value != null
                        && !(value instanceof Boolean || value instanceof Character
                        || value instanceof Byte || value instanceof Short
                        || value instanceof Integer || value instanceof Long
                        || value instanceof Float || value instanceof Double)) {
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
                return;
            }

            // No symbolic-name resolution occurs in this audit. An unrelated
            // declaration with the same spelling cannot launder a callback.
            if (node instanceof Ast.RuntimeCallExpr
                    || node instanceof Ast.NewExpr
                    || node instanceof Ast.BinaryExpr
                    || node instanceof Ast.UnaryExpr
                    || node instanceof Ast.LambdaExpr
                    || node instanceof Ast.MatchExpr
                    || node instanceof Ast.PatternTestExpr
                    || node instanceof Ast.TypeTestExpr) {
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.RuntimeCallExpr) {
                direct.add(EffectContracts.Effect.FFI_UNKNOWN);
            }
            if (node instanceof Ast.NameExpr
                    || node instanceof Ast.MemberExpr
                    || node instanceof Ast.IndexExpr) {
                direct.add(EffectContracts.Effect.AMBIENT_READ);
            }
            if (node instanceof Ast.MemberExpr || node instanceof Ast.IndexExpr) {
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.AssignExpr) {
                direct.add(EffectContracts.Effect.EXTERNAL_WRITE);
            }
            if (node instanceof Ast.AwaitExpr
                    || node instanceof Ast.ChannelOpExpr
                    || node instanceof Ast.DynamicSelectExpr
                    || node instanceof Ast.SelectStmt) {
                direct.add(EffectContracts.Effect.SCHEDULER);
                direct.add(EffectContracts.Effect.SYNCHRONIZATION);
                direct.add(EffectContracts.Effect.SUSPEND);
            }
            if (node instanceof Ast.ChannelOpExpr
                    || node instanceof Ast.DynamicSelectExpr
                    || node instanceof Ast.SelectStmt) {
                direct.add(EffectContracts.Effect.ACTOR);
            }
            if ((node instanceof Ast.ChannelOpExpr channel
                        && channel.mode() == Ast.WaitMode.BLOCKING)
                    || (node instanceof Ast.DynamicSelectExpr dynamic
                        && dynamic.mode() == Ast.WaitMode.BLOCKING)
                    || (node instanceof Ast.SelectStmt staticSelect
                        && staticSelect.mode() == Ast.WaitMode.BLOCKING)) {
                direct.add(EffectContracts.Effect.BLOCK);
            }
            if (node instanceof Ast.DynamicSelectExpr) {
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.YieldStmt) {
                // Yield/cooperate is a scheduling boundary even if it yields a
                // constant; never certify a generator's body as effect-free.
                direct.add(EffectContracts.Effect.SCHEDULER);
                direct.add(EffectContracts.Effect.SUSPEND);
            }
            if (node instanceof Ast.ListExpr || node instanceof Ast.TupleExpr
                    || node instanceof Ast.ObjectExpr) {
                // Aggregate creation can allocate even if its elements are
                // themselves literal constants. This is not a no-heap proof.
                direct.add(EffectContracts.Effect.ALLOC);
            }
            if (node instanceof Ast.TrapExpr) {
                // A syntactic trap is not authenticated evidence that THROW
                // has been discharged, nor that its Option wrapping is free.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                direct.add(EffectContracts.Effect.ALLOC);
            }
            if (node instanceof Ast.CastExpr || node instanceof Ast.SpreadExpr) {
                // Conversion, iteration and generic spreads may invoke
                // compiler hooks or user-defined protocol implementations.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }

            // Never silently treat a newly introduced syntax node as pure.
            if (node instanceof Ast.Expr expr
                    && !(expr instanceof Ast.NameExpr
                      || expr instanceof Ast.BinaryExpr
                      || expr instanceof Ast.UnaryExpr
                      || expr instanceof Ast.CallExpr
                      || expr instanceof Ast.RuntimeCallExpr
                      || expr instanceof Ast.NewExpr
                      || expr instanceof Ast.MemberExpr
                      || expr instanceof Ast.IndexExpr
                      || expr instanceof Ast.AssignExpr
                      || expr instanceof Ast.AwaitExpr
                      || expr instanceof Ast.ChannelOpExpr
                      || expr instanceof Ast.DynamicSelectExpr
                      || expr instanceof Ast.LambdaExpr
                      || expr instanceof Ast.MatchExpr
                      || expr instanceof Ast.PatternTestExpr
                      || expr instanceof Ast.TypeTestExpr
                      || expr instanceof Ast.TrapExpr
                      || expr instanceof Ast.CastExpr
                      || expr instanceof Ast.ConditionalExpr
                      || expr instanceof Ast.ListExpr
                      || expr instanceof Ast.TupleExpr
                      || expr instanceof Ast.ObjectExpr
                      || expr instanceof Ast.SpreadExpr)) {
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            if (node instanceof Ast.Stmt stmt
                    && !(stmt instanceof Ast.BindingStmt
                      || stmt instanceof Ast.DestructureStmt
                      || stmt instanceof Ast.ReturnStmt
                      || stmt instanceof Ast.ExprStmt
                      || stmt instanceof Ast.DeferStmt
                      || stmt instanceof Ast.BlockStmt
                      || stmt instanceof Ast.BreakStmt
                      || stmt instanceof Ast.ContinueStmt
                      || stmt instanceof Ast.IfStmt
                      || stmt instanceof Ast.TryStmt
                      || stmt instanceof Ast.YieldStmt)) {
                // Match, loops, selects and all future statements may execute
                // implicit protocol hooks, backpressure or user code.
                direct.add(EffectContracts.Effect.UNKNOWN_CALL);
            }
            // Visit every embedded expression in deferred, try/finally and
            // handler forms. A trap never automatically discharges ambient
            // effects and a finally does not inherit an exception waiver.
            if (!node.getClass().isRecord()
                    || !node.getClass().getName().startsWith("dev.oreslang.ast.Ast$")) {
                return;
            }
            // Record traversal is used only to conservatively find nested
            // execution sites; metadata TypeRefs/enums never authorize effects.
            for (RecordComponent component : node.getClass().getRecordComponents()) {
                try {
                    Object child = component.getAccessor().invoke(node);
                    if (child instanceof Ast.Expr || child instanceof Ast.Stmt
                            || child instanceof List<?> || child instanceof Record) {
                        walk(child, depth + 1);
                    }
                } catch (ReflectiveOperationException | SecurityException error) {
                    direct.add(EffectContracts.Effect.UNKNOWN_CALL);
                }
            }
        }
    }
}
