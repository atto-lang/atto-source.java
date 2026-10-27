package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A deliberately small, immutable specialized HIR. All calls carry concrete,
 * statically resolved instance identities. This is build-time data, not a JVM
 * classfile, native image, IR interpreter, or an authorization to skip the
 * subsequent ownership/effect/backend verification gates.
 */
public final class AotSpecializedIr {
    private static final int MAX_LOWERED_NODES = 16_384;
    private static final int MAX_LOWERING_DEPTH = 64;
    private static final int MAX_SOURCE_MODULES = 256;
    private static final int MAX_SOURCE_DECLARATIONS = 4_096;
    private static final int MAX_INSTANCES = 256;
    private static final Set<String> SCALAR_LAYOUTS =
            Set.of("int", "bool", "float", "string");

    private AotSpecializedIr() { }

    public record Module(String sourceUnitId, List<Function> functions) {
        public Module {
            Objects.requireNonNull(sourceUnitId, "source-unit identity");
            functions = List.copyOf(functions);
        }
    }

    public record Function(AotFiniteGenericClosure.Instance instance,
                           List<Parameter> parameters, Ast.TypeRef returnType,
                           Ast.EffectSpec effects, List<Statement> body) {
        public Function {
            Objects.requireNonNull(instance, "specialized function identity");
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(returnType, "specialized return type");
            Objects.requireNonNull(effects, "verified effect declaration");
            body = List.copyOf(body);
        }
    }

    public record Parameter(String name, Ast.TypeRef type) {
        public Parameter {
            Objects.requireNonNull(name, "parameter name");
            Objects.requireNonNull(type, "parameter type");
        }
    }

    public sealed interface Statement permits Return, ExpressionStatement, Local, Block, If { }
    public record Return(Value value) implements Statement { }
    public record ExpressionStatement(Value value) implements Statement { }
    public record Local(String name, Ast.TypeRef type, Value initializer) implements Statement { }
    public record Block(List<Statement> statements) implements Statement {
        public Block { statements = List.copyOf(statements); }
    }
    public record Branch(Value condition, List<Statement> body) {
        public Branch { body = List.copyOf(body); }
    }
    public record If(List<Branch> branches, List<Statement> otherwise) implements Statement {
        public If {
            branches = List.copyOf(branches);
            otherwise = List.copyOf(otherwise);
        }
    }

    public sealed interface Value permits Literal, LocalRef, Binary, Unary, Select, StaticCall {
        Ast.TypeRef type();
    }
    public record Literal(Object value, Ast.TypeRef type) implements Value { }
    public record LocalRef(String name, Ast.TypeRef type) implements Value { }
    public record Binary(String operator, Value left, Value right,
                         Ast.TypeRef type) implements Value { }
    public record Unary(String operator, Value operand, Ast.TypeRef type) implements Value { }
    public record Select(Value condition, Value ifTrue, Value ifFalse,
                         Ast.TypeRef type) implements Value { }
    public record StaticCall(AotFiniteGenericClosure.Instance target,
                             List<Value> arguments, Ast.TypeRef type) implements Value {
        public StaticCall { arguments = List.copyOf(arguments); }
    }

    /** Package-private: only OresCompiler may offer the checked public path. */
    static Module lower(Ast.Program checked, AotFiniteGenericClosure.Plan plan) {
        return new Lowerer(checked, plan).run();
    }

    private record Symbol(String module, String name, int arity) { }

    private static final class Lowerer {
        private final Ast.Program source;
        private final AotFiniteGenericClosure.Plan plan;
        private final Map<Symbol, Ast.FunctionDecl> declarations = new LinkedHashMap<>();
        private final Set<AotFiniteGenericClosure.Instance> closedInstances;
        private int nodeCount;

        Lowerer(Ast.Program source, AotFiniteGenericClosure.Plan plan) {
            this.source = Objects.requireNonNull(source, "checked AST");
            this.plan = Objects.requireNonNull(plan, "finite generic plan");
            this.closedInstances = new HashSet<>(plan.reachable());
        }

        Module run() {
            if (!plan.sourceUnitId().equals(MacroSourceUnitIdentity.unitId(source))) {
                throw fail("source unit/manifest mismatch");
            }
            if (plan.reachable().isEmpty() || plan.reachable().size() > MAX_INSTANCES
                    || closedInstances.size() != plan.reachable().size()) {
                throw fail("empty or duplicate specialization manifest");
            }
            if (source.modules().size() > MAX_SOURCE_MODULES) {
                throw fail("specialized HIR module indexing budget exceeded");
            }
            int declarationCount = 0;
            for (Ast.ModuleDecl module : source.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (++declarationCount > MAX_SOURCE_DECLARATIONS) {
                        throw fail("specialized HIR declaration indexing budget exceeded");
                    }
                    if (!(declaration instanceof Ast.FunctionDecl function)) {
                        throw fail("unclosed nonfunction declaration in specialized IR");
                    }
                    Symbol symbol = new Symbol(module.name(), function.name(),
                            function.parameters().size());
                    if (declarations.putIfAbsent(symbol, function) != null) {
                        throw fail("ambiguous static function declaration");
                    }
                }
            }
            List<Function> functions = new ArrayList<>();
            for (AotFiniteGenericClosure.Instance instance : plan.reachable()) {
                Ast.FunctionDecl declaration = declarations.get(
                        new Symbol(instance.module(), instance.name(), instance.arity()));
                if (declaration == null
                        || instance.typeArguments().size() != declaration.genericParameters().size()) {
                    throw fail("missing declaration or wrong instantiation arity");
                }
                if (declaration.kind() != Ast.CallableKind.FNC
                        || declaration.async() || declaration.suspends()
                        || declaration.generator() || declaration.structural()
                        || declaration.nonLexical() || declaration.trapped()
                        || declaration.actorKind() != Ast.ActorKind.NONE
                        || !declaration.effects().isEmpty()) {
                    throw fail("unsupported function effect, actor or suspension in HIR");
                }
                Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                for (int i = 0; i < declaration.genericParameters().size(); i++) {
                    substitutions.put(declaration.genericParameters().get(i),
                            instance.typeArguments().get(i));
                }
                List<Parameter> params = new ArrayList<>();
                Map<String, Ast.TypeRef> locals = new HashMap<>();
                for (Ast.Param param : declaration.parameters()) {
                    if (param.structural() || param.mutable()) {
                        throw fail("borrow/structural parameter needs specialized ownership proof");
                    }
                    Ast.TypeRef type = scalar(AotFiniteGenericClosure.specializeType(
                            param.type(), substitutions));
                    if (locals.putIfAbsent(param.name(), type) != null) {
                        throw fail("duplicate parameter binding");
                    }
                    params.add(new Parameter(param.name(), type));
                }
                Ast.TypeRef result = scalar(AotFiniteGenericClosure.specializeType(
                        declaration.returnType(), substitutions));
                List<Statement> statements = statements(declaration.body(), instance.module(),
                        substitutions, locals, result, 0);
                functions.add(new Function(instance, params, result,
                        declaration.effects(), statements));
            }
            return new Module(plan.sourceUnitId(), functions);
        }

        private Ast.TypeRef scalar(Ast.TypeRef type) {
            if (!type.arguments().isEmpty() || !SCALAR_LAYOUTS.contains(type.name())) {
                throw fail("specialized type needs fixed scalar layout proof: " + type.name());
            }
            return type;
        }

        private void tick(int depth) {
            if (depth > MAX_LOWERING_DEPTH || ++nodeCount > MAX_LOWERED_NODES) {
                throw fail("specialized HIR expansion resource budget exceeded");
            }
        }

        private List<Statement> statements(List<Ast.Stmt> body, String module,
                Map<String, Ast.TypeRef> generics, Map<String, Ast.TypeRef> locals,
                Ast.TypeRef returnType, int depth) {
            tick(depth);
            List<Statement> lowered = new ArrayList<>(body.size());
            for (Ast.Stmt stmt : body) {
                tick(depth);
                if (stmt instanceof Ast.ReturnStmt ret) {
                    Value value = value(ret.value(), module, generics, locals, depth + 1);
                    if (value == null || !value.type().equals(returnType)) {
                        throw fail("specialized return type does not match verified signature");
                    }
                    lowered.add(new Return(value));
                } else if (stmt instanceof Ast.ExprStmt expression) {
                    Value result = value(expression.expression(),
                            module, generics, locals, depth + 1);
                    if (result == null) throw fail("missing expression statement value");
                    lowered.add(new ExpressionStatement(result));
                } else if (stmt instanceof Ast.BindingStmt binding) {
                    if (binding.inferredTypeName() != null || binding.declaredType() == null
                            || binding.mutableReferent()) {
                        throw fail("unproven inferred/mutable local layout");
                    }
                    Ast.TypeRef declared = scalar(AotFiniteGenericClosure.specializeType(
                            binding.declaredType(), generics));
                    Value initial = value(binding.initializer(), module, generics, locals, depth + 1);
                    if (initial == null || !declared.equals(initial.type())) {
                        throw fail("specialized local initializer type mismatch");
                    }
                    if (locals.putIfAbsent(binding.name(), declared) != null) {
                        throw fail("ambiguous specialized local binding");
                    }
                    lowered.add(new Local(binding.name(), declared, initial));
                } else if (stmt instanceof Ast.BlockStmt block) {
                    lowered.add(new Block(statements(block.body(), module, generics,
                            new HashMap<>(locals), returnType, depth + 1)));
                } else if (stmt instanceof Ast.IfStmt conditional) {
                    List<Branch> branches = new ArrayList<>();
                    for (Ast.IfBranch arm : conditional.branches()) {
                        Value condition = value(arm.condition(), module, generics, locals, depth + 1);
                        if (condition == null || !condition.type().name().equals("bool")) {
                            throw fail("non-boolean static HIR branch");
                        }
                        branches.add(new Branch(condition, statements(arm.body(), module,
                                generics, new HashMap<>(locals), returnType, depth + 1)));
                    }
                    lowered.add(new If(branches, statements(conditional.elseBody(),
                            module, generics, new HashMap<>(locals), returnType, depth + 1)));
                } else {
                    throw fail("unlowered statement " + stmt.getClass().getSimpleName());
                }
            }
            return List.copyOf(lowered);
        }

        private Value value(Ast.Expr expr, String module,
                Map<String, Ast.TypeRef> generics, Map<String, Ast.TypeRef> locals, int depth) {
            if (expr == null) return null;
            tick(depth);
            if (expr instanceof Ast.LiteralExpr literal) {
                Object raw = literal.value();
                String type = raw instanceof Long || raw instanceof Integer ? "int"
                        : raw instanceof Boolean ? "bool"
                        : raw instanceof Double || raw instanceof Float ? "float"
                        : raw instanceof String ? "string" : null;
                if (type == null) throw fail("literal has unproven scalar layout");
                return new Literal(raw, Ast.TypeRef.simple(type));
            }
            if (expr instanceof Ast.NameExpr name) {
                Ast.TypeRef type = locals.get(name.name());
                if (type == null) throw fail("free or dynamic identifier in specialized HIR");
                return new LocalRef(name.name(), type);
            }
            // Retain the checked source arithmetic operator without choosing
            // a wrapping/checked backend instruction here. Numeric overflow
            // and division parity require the separate #1068 contract gate.
            if (expr instanceof Ast.BinaryExpr binary) {
                Value left = value(binary.left(), module, generics, locals, depth + 1);
                Value right = value(binary.right(), module, generics, locals, depth + 1);
                if (left == null || right == null
                        || !left.type().equals(right.type())
                        || !left.type().name().equals("int")
                        || !Set.of("+", "-", "*").contains(binary.operator())) {
                    throw fail("operator lacks proven scalar lowering semantics");
                }
                return new Binary(binary.operator(), left, right, left.type());
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                Value operand = value(unary.operand(), module, generics, locals, depth + 1);
                if (operand == null
                        || !Set.of("+", "-").contains(unary.operator())
                        || !operand.type().name().equals("int")) {
                    throw fail("unproven specialized unary operator");
                }
                return new Unary(unary.operator(), operand, operand.type());
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                Value cond = value(conditional.condition(), module, generics, locals, depth + 1);
                Value yes = value(conditional.whenTrue(), module, generics, locals, depth + 1);
                Value no = value(conditional.whenFalse(), module, generics, locals, depth + 1);
                if (cond == null || yes == null || no == null
                        || !cond.type().name().equals("bool")
                        || !yes.type().equals(no.type())) {
                    throw fail("unproven conditional result layout");
                }
                return new Select(cond, yes, no, yes.type());
            }
            if (expr instanceof Ast.CallExpr call) {
                if (call.origin() != Ast.CallOrigin.DIRECT) {
                    throw fail("pipeline/indirect call has no static lowering witness");
                }
                Symbol target;
                if (call.callee() instanceof Ast.NameExpr callee) {
                    if (locals.containsKey(callee.name())) {
                        throw fail("indirect local function call");
                    }
                    target = new Symbol(module, callee.name(), call.arguments().size());
                } else if (call.callee() instanceof Ast.MemberExpr member) {
                    MacroIdentifierHygiene.CapturedFunction capture =
                            MacroIdentifierHygiene.authorizedCapturedFunction(member);
                    if (capture == null) throw fail("unresolved virtual call");
                    target = new Symbol(capture.hygiene().callerModule(),
                            capture.name(), call.arguments().size());
                } else {
                    throw fail("unproven dynamic call");
                }
                Ast.FunctionDecl fn = declarations.get(target);
                if (fn == null || call.typeArguments().size() != fn.genericParameters().size()
                        || (!fn.genericParameters().isEmpty() && !call.typeArgumentsPresent())) {
                    throw fail("unresolved generic call or static target");
                }
                List<Ast.TypeRef> concrete = new ArrayList<>();
                for (Ast.TypeRef arg : call.typeArguments()) {
                    concrete.add(AotFiniteGenericClosure.specializeType(arg, generics));
                }
                AotFiniteGenericClosure.Instance targetInstance = new AotFiniteGenericClosure.Instance(
                        target.module(), target.name(), target.arity(), concrete);
                if (!closedInstances.contains(targetInstance)) {
                    throw fail("specialized call not present in closed reachability graph");
                }
                Map<String, Ast.TypeRef> calleeGenerics = new HashMap<>();
                for (int i = 0; i < fn.genericParameters().size(); i++) {
                    calleeGenerics.put(fn.genericParameters().get(i), concrete.get(i));
                }
                List<Value> arguments = new ArrayList<>();
                for (int i = 0; i < call.arguments().size(); i++) {
                    Value actual = value(call.arguments().get(i), module, generics, locals, depth + 1);
                    Ast.TypeRef expected = scalar(AotFiniteGenericClosure.specializeType(
                            fn.parameters().get(i).type(), calleeGenerics));
                    if (actual == null || !actual.type().equals(expected)) {
                        throw fail("specialized call argument type mismatch");
                    }
                    arguments.add(actual);
                }
                Ast.TypeRef result = scalar(AotFiniteGenericClosure.specializeType(
                        fn.returnType(), calleeGenerics));
                return new StaticCall(targetInstance, arguments, result);
            }
            throw fail("expression lacks specialized HIR lowering: "
                    + expr.getClass().getSimpleName());
        }

        private static IllegalArgumentException fail(String reason) {
            return new IllegalArgumentException(
                    "AOT-SPECIALIZED-HIR: " + reason + "; runtime fallback forbidden");
        }
    }
}
