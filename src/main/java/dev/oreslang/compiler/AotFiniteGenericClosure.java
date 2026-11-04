package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative, bounded admission manifest for the first direct-call generic
 * AOT subset. This pass never emits code, executes guest code, or claims full
 * AOT closure. Unsupported witness/virtual calls and inference fail closed.
 */
public final class AotFiniteGenericClosure {
    private static final int MAX_INSTANCES = 256;
    private static final int MAX_NODES = 16_384;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_TYPE_DEPTH = 48;
    private static final int MAX_MODULES = 256;
    private static final int MAX_DECLARATIONS = 4_096;
    private static final int MAX_FORMALS_PER_FUNCTION = 64;
    private static final int MAX_PARAMETERS_PER_FUNCTION = 128;
    private static final int MAX_ENTRY_POINTS = 256;
    // Phase-two planning admits only built-in type constructors. Nominal
    // classes/traits need a separately proven layout and witness manifest.
    // Unknown type names must not be mistaken for concrete specializations.
    private static final Set<String> ADMITTED_TYPES = Set.of(
            "void", "bool", "int", "i8", "i16", "i32", "i64",
            "u8", "u16", "u32", "u64", "uint",
            "float", "f32", "f64", "double", "decimal",
            "string", "String", "char",
            "List", "Array", "Option", "Result");

    private AotFiniteGenericClosure() { }

    public record Instance(String module, String name, int arity,
                           List<Ast.TypeRef> typeArguments) {
        public Instance {
            typeArguments = List.copyOf(typeArguments);
        }
    }

    public record Plan(String sourceUnitId, List<Instance> reachable) {
        public Plan {
            Objects.requireNonNull(sourceUnitId, "AOT source unit identity");
            reachable = List.copyOf(reachable);
        }
    }

    /**
     * Shared concrete-type substitution for the strict planner and specialized
     * IR emitter. There is deliberately no unresolved nominal/generic fallback.
     */
    static Ast.TypeRef specializeType(
            Ast.TypeRef source, Map<String, Ast.TypeRef> bindings) {
        Objects.requireNonNull(bindings, "AOT type bindings");
        return specializeType(source, bindings, 0);
    }

    private static Ast.TypeRef specializeType(
            Ast.TypeRef source, Map<String, Ast.TypeRef> bindings, int depth) {
        if (source == null || depth > MAX_TYPE_DEPTH
                || source.inferArguments() || source.name().startsWith("$")) {
            throw new IllegalArgumentException(
                    "AOT-FINITE-GENERICS: incomplete or unbounded specialized type");
        }
        Ast.TypeRef bound = bindings.get(source.name());
        if (bound != null) {
            if (!source.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "AOT-FINITE-GENERICS: type formal used as constructor");
            }
            return specializeType(bound, Map.of(), depth + 1);
        }
        if (!ADMITTED_TYPES.contains(source.name())) {
            throw new IllegalArgumentException(
                    "AOT-FINITE-GENERICS: unknown nominal or unbound generic '"
                            + source.name() + "'; layout proof required");
        }
        List<Ast.TypeRef> concrete = new ArrayList<>(source.arguments().size());
        for (Ast.TypeRef item : source.arguments()) {
            concrete.add(specializeType(item, bindings, depth + 1));
        }
        return new Ast.TypeRef(source.name(), concrete, false);
    }

    private record Symbol(String module, String name, int arity) { }
    private record Work(Instance instance, int depth) { }

    /** Package-internal planner for already checked ASTs; callers must not bypass
     * OresCompiler.planFiniteAotGenerics for executable source admission. */
    static Plan plan(Ast.Program checked, BuildOptions options) {
        Objects.requireNonNull(checked, "checked AOT program");
        Objects.requireNonNull(options, "AOT build options");
        return new Planner(checked, options).run();
    }

    private static final class Planner {
        private final Ast.Program program;
        private final BuildOptions options;
        private final Map<Symbol, Ast.FunctionDecl> functions = new LinkedHashMap<>();
        private final ArrayDeque<Work> queue = new ArrayDeque<>();
        private final LinkedHashSet<Instance> visited = new LinkedHashSet<>();
        private int nodes;

        Planner(Ast.Program program, BuildOptions options) {
            this.program = program;
            this.options = options;
        }

        Plan run() {
            if (!program.imports().isEmpty()) {
                throw fail("linked import graph not yet admitted for generic AOT closure");
            }
            if (program.modules().size() > MAX_MODULES) {
                throw fail("module indexing budget exceeded");
            }
            int indexed = 0;
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl decl : module.declarations()) {
                    if (++indexed > MAX_DECLARATIONS) {
                        throw fail("declaration indexing budget exceeded");
                    }
                    if (!(decl instanceof Ast.FunctionDecl fn)) {
                        throw fail("static subset admits only free functions; layouts, "
                                + "actors, macros and traits require separate closure proofs");
                    }
                    if (fn.genericParameters().size() > MAX_FORMALS_PER_FUNCTION
                            || fn.parameters().size() > MAX_PARAMETERS_PER_FUNCTION) {
                        throw fail("function signature budget exceeded");
                    }
                    Symbol key = new Symbol(module.name(), fn.name(), fn.parameters().size());
                    if (functions.putIfAbsent(key, fn) != null) {
                        throw fail("ambiguous overload " + key.module() + "." + key.name()
                                + "/" + key.arity());
                    }
                }
            }
            if (options.entryPoints().size() > MAX_ENTRY_POINTS) {
                throw fail("entry-point indexing budget exceeded");
            }
            if (options.preservePublicApi()) {
                for (Map.Entry<Symbol, Ast.FunctionDecl> entry : functions.entrySet()) {
                    if (entry.getValue().visibility() == Ast.Visibility.PUBLIC) {
                        if (!entry.getValue().genericParameters().isEmpty()) {
                            throw fail("open generic public API needs a finite instantiation manifest");
                        }
                        Symbol key = entry.getKey();
                        enqueue(new Instance(key.module(), key.name(), key.arity(), List.of()), 0);
                    }
                }
            }
            // Set.copyOf does not guarantee iteration order across JVMs; AOT
            // manifests must be reproducible independent of hash seed.
            for (String entry : options.entryPoints().stream().sorted().toList()) {
                List<Symbol> choices = functions.keySet().stream()
                        .filter(key -> entry.equals(key.module() + "." + key.name())
                                || (!entry.contains(".")
                                    && key.module().equals(Parser.ROOT_MODULE)
                                    && key.name().equals(entry)))
                        .toList();
                if (choices.size() != 1) {
                    throw fail("missing or ambiguous AOT entry point '" + entry + "'");
                }
                Symbol key = choices.getFirst();
                Ast.FunctionDecl fn = functions.get(key);
                if (!fn.genericParameters().isEmpty()) {
                    throw fail("AOT entry point cannot be an unspecialized generic: " + entry);
                }
                enqueue(new Instance(key.module(), key.name(), key.arity(), List.of()), 0);
            }
            if (queue.isEmpty()) throw fail("no closed AOT roots");
            while (!queue.isEmpty()) {
                Work work = queue.removeFirst();
                if (!visited.add(work.instance())) continue;
                if (visited.size() > MAX_INSTANCES) throw fail("finite specialization budget exceeded");
                if (work.depth() > MAX_DEPTH) throw fail("generic expansion depth exceeded");
                Instance instance = work.instance();
                Ast.FunctionDecl fn = functions.get(
                        new Symbol(instance.module(), instance.name(), instance.arity()));
                if (fn == null) throw fail("unresolved specialization target " + instance);
                if (instance.typeArguments().size() != fn.genericParameters().size()) {
                    throw fail("generic arity mismatch for " + instance.module() + "." + fn.name());
                }
                Map<String, Ast.TypeRef> bindings = new HashMap<>();
                for (int i = 0; i < fn.genericParameters().size(); i++) {
                    bindings.put(fn.genericParameters().get(i), instance.typeArguments().get(i));
                }
                for (Ast.Param param : fn.parameters()) resolveType(param.type(), bindings, 0);
                resolveType(fn.returnType(), bindings, 0);
                Set<String> locals = new HashSet<>();
                for (Ast.Param param : fn.parameters()) locals.add(param.name());
                visitStatements(fn.body(), instance.module(), bindings, locals, work.depth(), 0);
            }
            return new Plan(MacroSourceUnitIdentity.unitId(program),
                    List.copyOf(visited));
        }

        private void enqueue(Instance instance, int depth) {
            if (depth > MAX_DEPTH) throw fail("generic expansion depth exceeded");
            if (queue.size() + visited.size() > MAX_INSTANCES * 2) {
                throw fail("pending generic specialization budget exceeded");
            }
            queue.addLast(new Work(instance, depth));
        }

        private void tick(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw fail("bounded generic AOT AST traversal exceeded");
            }
        }

        private Ast.TypeRef resolveType(
                Ast.TypeRef type, Map<String, Ast.TypeRef> bindings, int depth) {
            if (type == null || depth > MAX_TYPE_DEPTH || ++nodes > MAX_NODES) {
                throw fail("generic type closure budget exceeded");
            }
            if (type.inferArguments() || type.name().startsWith("$")) {
                throw fail("unresolved/inferred type cannot enter AOT specialization");
            }
            if (type.name().equals("Fnc") || type.name().equals("Routine")) {
                throw fail("function values need static witness/closure-layout proof");
            }
            Ast.TypeRef parameter = bindings.get(type.name());
            if (parameter != null) {
                if (!type.arguments().isEmpty()) {
                    throw fail("generic type parameter used as an unapplied constructor");
                }
                return resolveType(parameter, Map.of(), depth + 1);
            }
            if (!ADMITTED_TYPES.contains(type.name())) {
                throw fail("unbound generic formal or unproven nominal type '"
                        + type.name() + "' in AOT instance");
            }
            List<Ast.TypeRef> args = new ArrayList<>();
            for (Ast.TypeRef arg : type.arguments()) {
                args.add(resolveType(arg, bindings, depth + 1));
            }
            return new Ast.TypeRef(type.name(), args, false);
        }

        private void visitStatements(List<Ast.Stmt> statements, String module,
                Map<String, Ast.TypeRef> bindings, Set<String> locals,
                int graphDepth, int depth) {
            tick(depth);
            for (Ast.Stmt statement : statements) {
                tick(depth);
                if (statement instanceof Ast.ReturnStmt ret) {
                    visitExpr(ret.value(), module, bindings, locals, graphDepth, depth + 1);
                } else if (statement instanceof Ast.ExprStmt expr) {
                    visitExpr(expr.expression(), module, bindings, locals, graphDepth, depth + 1);
                } else if (statement instanceof Ast.BindingStmt bind) {
                    // A local declaration participates in specialized HIR layout.
                    // Merely scanning its initializer silently omits that type.
                    resolveType(bind.declaredType(), bindings, 0);
                    if (bind.inferredTypeName() != null) {
                        throw fail("unresolved local inferred-type metadata");
                    }
                    visitExpr(bind.initializer(), module, bindings, locals, graphDepth, depth + 1);
                    if (!locals.add(bind.name())) throw fail("ambiguous local binding in AOT subset");
                } else if (statement instanceof Ast.BlockStmt block) {
                    visitStatements(block.body(), module, bindings, new HashSet<>(locals),
                            graphDepth, depth + 1);
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch arm : conditional.branches()) {
                        visitExpr(arm.condition(), module, bindings, locals, graphDepth, depth + 1);
                        visitStatements(arm.body(), module, bindings, new HashSet<>(locals),
                                graphDepth, depth + 1);
                    }
                    visitStatements(conditional.elseBody(), module, bindings,
                            new HashSet<>(locals), graphDepth, depth + 1);
                } else {
                    throw fail("unlowered statement " + statement.getClass().getSimpleName()
                            + " requires separate AOT closure proof");
                }
            }
        }

        private void visitExpr(Ast.Expr expr, String module,
                Map<String, Ast.TypeRef> bindings, Set<String> locals,
                int graphDepth, int depth) {
            if (expr == null) return;
            tick(depth);
            if (expr instanceof Ast.LiteralExpr) return;
            if (expr instanceof Ast.NameExpr name) {
                if (!locals.contains(name.name())) {
                    throw fail("unproven global/function value in AOT expression: " + name.name());
                }
                return;
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                visitExpr(binary.left(), module, bindings, locals, graphDepth, depth + 1);
                visitExpr(binary.right(), module, bindings, locals, graphDepth, depth + 1);
                return;
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                visitExpr(unary.operand(), module, bindings, locals, graphDepth, depth + 1);
                return;
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                visitExpr(conditional.condition(), module, bindings, locals, graphDepth, depth + 1);
                visitExpr(conditional.whenTrue(), module, bindings, locals, graphDepth, depth + 1);
                visitExpr(conditional.whenFalse(), module, bindings, locals, graphDepth, depth + 1);
                return;
            }
            if (expr instanceof Ast.CallExpr call) {
                if (call.arguments().size() > MAX_PARAMETERS_PER_FUNCTION
                        || call.typeArguments().size() > MAX_FORMALS_PER_FUNCTION) {
                    throw fail("call shape budget exceeded");
                }
                if (call.origin() != Ast.CallOrigin.DIRECT) {
                    throw fail("dynamic/pipeline call requires static dispatch proof");
                }
                Symbol target;
                if (call.callee() instanceof Ast.NameExpr name) {
                    if (locals.contains(name.name())) {
                        throw fail("indirect local function value call is not statically closed");
                    }
                    target = new Symbol(module, name.name(), call.arguments().size());
                } else if (call.callee() instanceof Ast.MemberExpr member) {
                    MacroIdentifierHygiene.CapturedFunction capture =
                            MacroIdentifierHygiene.authorizedCapturedFunction(member);
                    if (capture == null) throw fail("unproven virtual/module dispatch");
                    target = new Symbol(capture.hygiene().callerModule(),
                            capture.name(), call.arguments().size());
                } else {
                    throw fail("indirect function dispatch has no static AOT witness");
                }
                Ast.FunctionDecl targetFn = functions.get(target);
                if (targetFn == null) {
                    throw fail("unresolved static call " + target.module() + "." + target.name()
                            + "/" + target.arity());
                }
                if (!call.typeArgumentsPresent()
                        && !targetFn.genericParameters().isEmpty()) {
                    throw fail("inferred generic arguments lack an explicit AOT specialization proof");
                }
                if (call.typeArguments().size() != targetFn.genericParameters().size()) {
                    throw fail("generic arguments must be fully explicit in AOT subset");
                }
                List<Ast.TypeRef> args = new ArrayList<>();
                for (Ast.TypeRef type : call.typeArguments()) {
                    args.add(resolveType(type, bindings, 0));
                }
                for (Ast.Expr arg : call.arguments()) {
                    visitExpr(arg, module, bindings, locals, graphDepth, depth + 1);
                }
                enqueue(new Instance(target.module(), target.name(), target.arity(), args),
                        graphDepth + 1);
                return;
            }
            throw fail("unproven AOT expression " + expr.getClass().getSimpleName()
                    + "; runtime lowering fallback is forbidden");
        }

        private static IllegalArgumentException fail(String reason) {
            return new IllegalArgumentException(
                    "AOT-FINITE-GENERICS: " + reason + "; runtime specialization forbidden");
        }
    }
}
