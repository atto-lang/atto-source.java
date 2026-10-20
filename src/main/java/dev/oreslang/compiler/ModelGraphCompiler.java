package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-owned lowering for the first executable general-purpose ModelGraph slice.
 *
 * <p>This intentionally supports only a static linear chain of verified Oreslang
 * scalar operations. It does not execute guest code while compiling, does not
 * trust source-provided purity/resource metadata, and does not imply autograd,
 * actors, GPUs, serialization, dynamic allocation or hard-real-time support.</p>
 */
public final class ModelGraphCompiler {
    private ModelGraphCompiler() { }

    public static final String MODEL_TYPE_PREFIX = "$model$";
    private static final int MAX_MODELS = 1024;
    private static final int MAX_NODES = 4096;
    private static final int MAX_STATIC_ARGS = 32;

    public record StaticArg(
            String parameterName,
            Object literal,
            Ast.TypeRef type) {
        public StaticArg {
            if ((parameterName == null) == (literal == null)) {
                throw new IllegalArgumentException(
                        "MODEL_INVALID_STATIC_ARG: exactly one of parameterName/literal is required");
            }
        }

        public boolean parameterReference() { return parameterName != null; }

        public Object resolve(Map<String, Object> configuration) {
            if (!parameterReference()) return literal;
            if (!configuration.containsKey(parameterName)) {
                throw new IllegalStateException(
                        "MODEL_MISSING_CONFIG: missing model parameter '" + parameterName + "'");
            }
            return configuration.get(parameterName);
        }

        String canonical() {
            return parameterReference()
                    ? "param:" + parameterName + ":" + canonicalType(type)
                    : "lit:" + canonicalConstant(literal) + ":" + canonicalType(type);
        }
    }

    public record Node(
            String id,
            String operationName,
            Ast.FunctionDecl operation,
            List<StaticArg> staticArguments,
            Ast.TypeRef inputType,
            Ast.TypeRef outputType,
            String operationAbi,
            String implementationDigest) {
        public Node {
            staticArguments = List.copyOf(staticArguments);
        }
    }

    public record Parameter(
            String name,
            Ast.TypeRef type,
            Object defaultValue,
            boolean hasDefault) { }

    public record CompiledModel(
            String qualifiedName,
            String sourceName,
            Ast.ModelDecl declaration,
            List<Parameter> parameters,
            List<Node> nodes,
            Ast.TypeRef inputType,
            Ast.TypeRef outputType,
            String graphDigest,
            boolean freestandingEligible,
            boolean noDynamicAllocation,
            boolean deterministic) {
        public CompiledModel {
            parameters = List.copyOf(parameters);
            nodes = List.copyOf(nodes);
        }

        public String modelTypeName() { return MODEL_TYPE_PREFIX + qualifiedName; }

        public Map<String, Object> bindConfiguration(List<?> arguments) {
            if (arguments.size() > parameters.size()) {
                throw new IllegalArgumentException(
                        "MODEL_CONFIG_ARITY: " + sourceName + " expects at most "
                                + parameters.size() + " configuration argument(s), got "
                                + arguments.size());
            }
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                Parameter parameter = parameters.get(i);
                Object value;
                if (i < arguments.size()) {
                    value = arguments.get(i);
                } else if (parameter.hasDefault()) {
                    value = parameter.defaultValue();
                } else {
                    throw new IllegalArgumentException(
                            "MODEL_CONFIG_ARITY: missing required model parameter '"
                                    + parameter.name() + "'");
                }
                values.put(parameter.name(), value);
            }
            return Collections.unmodifiableMap(values);
        }
    }

    public static final class Catalog {
        private final Map<String, CompiledModel> exact;
        private final Map<String, CompiledModel> unqualified;
        private final Set<String> ambiguous;

        private Catalog(
                Map<String, CompiledModel> exact,
                Map<String, CompiledModel> unqualified,
                Set<String> ambiguous) {
            this.exact = Map.copyOf(exact);
            this.unqualified = Map.copyOf(unqualified);
            this.ambiguous = Set.copyOf(ambiguous);
        }

        public CompiledModel find(String name) {
            CompiledModel qualified = exact.get(name);
            if (qualified != null) return qualified;
            if (ambiguous.contains(name)) {
                throw new IllegalArgumentException(
                        "MODEL_AMBIGUOUS: model '" + name + "' is ambiguous; qualify it with its module");
            }
            return unqualified.get(name);
        }

        public CompiledModel findByTypeName(String typeName) {
            if (typeName == null || !typeName.startsWith(MODEL_TYPE_PREFIX)) return null;
            return exact.get(typeName.substring(MODEL_TYPE_PREFIX.length()));
        }

        public List<CompiledModel> models() {
            return List.copyOf(exact.values());
        }

        public boolean isEmpty() { return exact.isEmpty(); }
    }

    public static Catalog compile(Ast.Program program) {
        if (program == null) throw new IllegalArgumentException("MODEL_INVALID_PROGRAM: null program");

        LinkedHashMap<String, Ast.ModelDecl> modelDecls = new LinkedHashMap<>();
        HashMap<Ast.ModelDecl, String> modelModules = new HashMap<>();
        Map<String, Map<Integer, Ast.FunctionDecl>> qualifiedFunctions = new HashMap<>();
        Map<String, String> uniqueFunctionOwners = new HashMap<>();
        Set<String> ambiguousFunctionNames = new HashSet<>();

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ModelDecl model) {
                    String qualified = qualify(module.name(), model.name());
                    if (modelDecls.putIfAbsent(qualified, model) != null) {
                        fail("MODEL_DUPLICATE", "duplicate model '" + qualified + "'");
                    }
                    modelModules.put(model, module.name());
                    if (modelDecls.size() > MAX_MODELS) {
                        fail("MODEL_BUDGET", "model declaration count exceeds " + MAX_MODELS);
                    }
                } else if (declaration instanceof Ast.FunctionDecl function) {
                    String qualifiedName = qualify(module.name(), function.name());
                    putFunction(qualifiedFunctions, qualifiedName, function);
                    String old = uniqueFunctionOwners.putIfAbsent(function.name(), module.name());
                    if (old != null && !old.equals(module.name())) {
                        ambiguousFunctionNames.add(function.name());
                    }
                }
            }
        }

        Map<String, Ast.ModelDecl> unqualifiedModels = new HashMap<>();
        Set<String> ambiguousModels = new HashSet<>();
        Map<String, String> modelOwnerByName = new HashMap<>();
        for (Map.Entry<String, Ast.ModelDecl> entry : modelDecls.entrySet()) {
            Ast.ModelDecl model = entry.getValue();
            String module = modelModules.get(model);
            String old = modelOwnerByName.putIfAbsent(model.name(), module);
            if (old == null || old.equals(module)) {
                if (!ambiguousModels.contains(model.name())) {
                    unqualifiedModels.put(model.name(), model);
                }
            } else {
                ambiguousModels.add(model.name());
                unqualifiedModels.remove(model.name());
            }
        }

        detectCompositionCycles(modelDecls, modelModules, unqualifiedModels, ambiguousModels);

        LinkedHashMap<String, CompiledModel> exact = new LinkedHashMap<>();
        HashMap<String, CompiledModel> unqualified = new HashMap<>();
        Set<String> ambiguousCompiled = new HashSet<>();

        for (Map.Entry<String, Ast.ModelDecl> entry : modelDecls.entrySet()) {
            String qualifiedName = entry.getKey();
            Ast.ModelDecl declaration = entry.getValue();
            String module = modelModules.get(declaration);
            CompiledModel compiled = compileOne(
                    qualifiedName,
                    module,
                    declaration,
                    qualifiedFunctions,
                    uniqueFunctionOwners,
                    ambiguousFunctionNames,
                    modelDecls,
                    unqualifiedModels,
                    ambiguousModels);
            exact.put(qualifiedName, compiled);

            CompiledModel previous = unqualified.putIfAbsent(declaration.name(), compiled);
            if (previous != null && previous != compiled) {
                ambiguousCompiled.add(declaration.name());
                unqualified.remove(declaration.name());
            }
        }

        return new Catalog(exact, unqualified, ambiguousCompiled);
    }

    private static void putFunction(
            Map<String, Map<Integer, Ast.FunctionDecl>> functions,
            String name,
            Ast.FunctionDecl function) {
        Map<Integer, Ast.FunctionDecl> family =
                functions.computeIfAbsent(name, ignored -> new LinkedHashMap<>());
        Ast.FunctionDecl old = family.putIfAbsent(function.parameters().size(), function);
        if (old != null && old != function) {
            fail("MODEL_AMBIGUOUS_OP",
                    "operation candidate '" + name + "' has duplicate arity "
                            + function.parameters().size());
        }
    }

    private static void detectCompositionCycles(
            Map<String, Ast.ModelDecl> models,
            Map<Ast.ModelDecl, String> modules,
            Map<String, Ast.ModelDecl> unqualified,
            Set<String> ambiguous) {
        Set<Ast.ModelDecl> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<Ast.ModelDecl> active = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Ast.ModelDecl model : models.values()) {
            detectCompositionCycle(model, modules, models, unqualified, ambiguous, visited, active);
        }
    }

    private static void detectCompositionCycle(
            Ast.ModelDecl model,
            Map<Ast.ModelDecl, String> modules,
            Map<String, Ast.ModelDecl> models,
            Map<String, Ast.ModelDecl> unqualified,
            Set<String> ambiguous,
            Set<Ast.ModelDecl> visited,
            Set<Ast.ModelDecl> active) {
        if (visited.contains(model)) return;
        if (!active.add(model)) {
            fail("MODEL_RECURSIVE_COMPOSITION",
                    "recursive model composition involving '" + model.name() + "'");
        }
        String module = modules.get(model);
        for (Ast.ModelOp operation : model.operations()) {
            Ast.ModelDecl target = models.get(qualify(module, operation.name()));
            if (target == null && !ambiguous.contains(operation.name())) {
                target = unqualified.get(operation.name());
            }
            if (target != null) {
                if (active.contains(target)) {
                    fail("MODEL_RECURSIVE_COMPOSITION",
                            "recursive model composition involving '"
                                    + model.name() + "' and '" + target.name() + "'");
                }
                detectCompositionCycle(target, modules, models, unqualified, ambiguous, visited, active);
            }
        }
        active.remove(model);
        visited.add(model);
    }

    private static CompiledModel compileOne(
            String qualifiedName,
            String module,
            Ast.ModelDecl declaration,
            Map<String, Map<Integer, Ast.FunctionDecl>> functions,
            Map<String, String> uniqueFunctionOwners,
            Set<String> ambiguousFunctionNames,
            Map<String, Ast.ModelDecl> models,
            Map<String, Ast.ModelDecl> unqualifiedModels,
            Set<String> ambiguousModels) {
        if (declaration.operations().isEmpty()) {
            fail("MODEL_EMPTY_BODY", "model '" + qualifiedName + "' has no operations");
        }
        if (declaration.operations().size() > MAX_NODES) {
            fail("MODEL_NODE_BUDGET",
                    "model '" + qualifiedName + "' exceeds " + MAX_NODES + " operations");
        }

        LinkedHashMap<String, Ast.ModelParam> sourceParameters = new LinkedHashMap<>();
        ArrayList<Parameter> parameters = new ArrayList<>();
        boolean seenDefault = false;
        for (Ast.ModelParam parameter : declaration.parameters()) {
            if (sourceParameters.putIfAbsent(parameter.name(), parameter) != null) {
                fail("MODEL_DUPLICATE_PARAMETER",
                        "duplicate model parameter '" + parameter.name() + "'");
            }
            if (parameter.defaultValue() != null) seenDefault = true;
            else if (seenDefault) {
                fail("MODEL_CONFIG_DEFAULT_ORDER",
                        "required model parameters cannot follow defaulted parameters");
            }
            Object defaultValue = null;
            if (parameter.defaultValue() != null) {
                defaultValue = constantValue(parameter.defaultValue(), "model parameter default");
                Ast.TypeRef literalType = literalType(defaultValue);
                requireSameType(literalType, parameter.type(),
                        "MODEL_CONFIG_TYPE_MISMATCH",
                        "default for model parameter '" + parameter.name() + "'");
            }
            parameters.add(new Parameter(
                    parameter.name(),
                    parameter.type(),
                    defaultValue,
                    parameter.defaultValue() != null));
        }

        ArrayList<Node> nodes = new ArrayList<>();
        Ast.TypeRef previousOutput = null;
        int nodeIndex = 0;
        for (Ast.ModelOp sourceOperation : declaration.operations()) {
            if (sourceOperation.arguments().size() > MAX_STATIC_ARGS) {
                fail("MODEL_STATIC_ARG_BUDGET",
                        "operation '" + sourceOperation.name() + "' has too many static arguments");
            }

            Ast.ModelDecl nested = models.get(qualify(module, sourceOperation.name()));
            if (nested == null && !ambiguousModels.contains(sourceOperation.name())) {
                nested = unqualifiedModels.get(sourceOperation.name());
            }
            if (nested != null) {
                fail("MODEL_COMPOSITION_NOT_ENABLED",
                        "non-recursive model composition is not enabled in executable v1; '"
                                + sourceOperation.name() + "' is a model, not an operation");
            }

            int operationArity = sourceOperation.arguments().size() + 1;
            Ast.FunctionDecl operation = findFunction(
                    functions,
                    uniqueFunctionOwners,
                    ambiguousFunctionNames,
                    module,
                    sourceOperation.name(),
                    operationArity);
            if (operation == null) {
                fail("MODEL_UNKNOWN_OP",
                        "no verified Oreslang operation '" + sourceOperation.name()
                                + "' with " + operationArity + " parameter(s)");
            }
            verifyOperation(operation, sourceOperation.name());

            ArrayList<StaticArg> staticArguments = new ArrayList<>();
            for (int i = 0; i < sourceOperation.arguments().size(); i++) {
                Ast.Expr argument = sourceOperation.arguments().get(i);
                Ast.TypeRef expected = operation.parameters().get(i).type();
                StaticArg lowered;
                if (argument instanceof Ast.NameExpr name && sourceParameters.containsKey(name.name())) {
                    Ast.ModelParam parameter = sourceParameters.get(name.name());
                    requireSameType(parameter.type(), expected,
                            "MODEL_CONFIG_TYPE_MISMATCH",
                            "model parameter '" + name.name() + "' for operation '"
                                    + sourceOperation.name() + "'");
                    lowered = new StaticArg(name.name(), null, parameter.type());
                } else {
                    Object literal = constantValue(argument,
                            "static argument for operation '" + sourceOperation.name() + "'");
                    Ast.TypeRef actual = literalType(literal);
                    requireSameType(actual, expected,
                            "MODEL_CONFIG_TYPE_MISMATCH",
                            "static argument " + i + " for operation '"
                                    + sourceOperation.name() + "'");
                    lowered = new StaticArg(null, literal, actual);
                }
                staticArguments.add(lowered);
            }

            Ast.TypeRef inputType = operation.parameters().getLast().type();
            Ast.TypeRef outputType = operation.returnType();
            if (outputType == null || canonicalType(outputType).equals("void")) {
                fail("MODEL_INVALID_OP_SIGNATURE",
                        "operation '" + sourceOperation.name() + "' must return a value");
            }
            if (previousOutput != null) {
                requireSameType(previousOutput, inputType,
                        "MODEL_PORT_TYPE_MISMATCH",
                        "between model operations");
            }

            String operationAbi = operationAbi(operation);
            String implementationDigest = implementationDigest(operation);
            nodes.add(new Node(
                    "n" + nodeIndex++,
                    sourceOperation.name(),
                    operation,
                    staticArguments,
                    inputType,
                    outputType,
                    operationAbi,
                    implementationDigest));
            previousOutput = outputType;
        }

        Ast.TypeRef inputType = nodes.getFirst().inputType();
        Ast.TypeRef outputType = nodes.getLast().outputType();
        String graphDigest = graphDigest(qualifiedName, parameters, nodes);

        // The graph is statically bounded, but this Java/Graal interpreter
        // slice still constructs a ModelInstance and immutable configuration map.
        // Do not claim freestanding or no-runtime-allocation until an AOT lowering
        // proves that those objects are erased/preallocated on the selected target.
        return new CompiledModel(
                qualifiedName,
                declaration.name(),
                declaration,
                parameters,
                nodes,
                inputType,
                outputType,
                graphDigest,
                false,
                false,
                true);
    }

    private static Ast.FunctionDecl findFunction(
            Map<String, Map<Integer, Ast.FunctionDecl>> functions,
            Map<String, String> uniqueOwners,
            Set<String> ambiguousNames,
            String module,
            String name,
            int arity) {
        Map<Integer, Ast.FunctionDecl> local = functions.get(qualify(module, name));
        if (local != null && local.get(arity) != null) return local.get(arity);
        if (ambiguousNames.contains(name)) return null;
        String owner = uniqueOwners.get(name);
        if (owner == null) return null;
        Map<Integer, Ast.FunctionDecl> family = functions.get(qualify(owner, name));
        return family == null ? null : family.get(arity);
    }

    private static void verifyOperation(Ast.FunctionDecl operation, String sourceName) {
        if (operation.kind() != Ast.CallableKind.FNC
                || operation.actorKind() != Ast.ActorKind.NONE
                || operation.async()
                || operation.suspends()
                || operation.generator()
                || operation.structural()
                || operation.nonLexical()
                || operation.trapped()
                || !operation.genericParameters().isEmpty()
                || operation.parameters().isEmpty()
                || operation.body().size() != 1
                || !(operation.body().getFirst() instanceof Ast.ReturnStmt returned)
                || returned.value() == null) {
            fail("MODEL_EFFECTFUL_BODY",
                    "operation '" + sourceName
                            + "' is not a verified synchronous pure scalar function");
        }

        Set<String> parameters = new LinkedHashSet<>();
        for (Ast.Param parameter : operation.parameters()) {
            if (parameter.mutable() || parameter.structural()
                    || !parameters.add(parameter.name())) {
                fail("MODEL_INVALID_OP_SIGNATURE",
                        "operation '" + sourceName
                                + "' must use distinct immutable ordinary parameters");
            }
        }
        Ast.ReturnStmt returned = (Ast.ReturnStmt) operation.body().getFirst();
        if (!pureExpression(returned.value(), parameters)) {
            fail("MODEL_EFFECTFUL_BODY",
                    "operation '" + sourceName
                            + "' contains calls, mutation, allocation or unsupported effects");
        }
    }

    private static boolean pureExpression(Ast.Expr expression, Set<String> parameters) {
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            return value instanceof Long || value instanceof Double
                    || value instanceof Boolean || value instanceof String;
        }
        if (expression instanceof Ast.NameExpr name) {
            return parameters.contains(name.name());
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return Set.of("+", "-", "!", "~").contains(unary.operator())
                    && pureExpression(unary.operand(), parameters);
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return Set.of(
                    "+", "-", "*", "/", "%",
                    "==", "!=", "eq", "neq", "<", "<=", ">", ">=",
                    "&&", "||", "and", "or", "^^", "xor")
                    .contains(binary.operator())
                    && pureExpression(binary.left(), parameters)
                    && pureExpression(binary.right(), parameters);
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            return pureExpression(conditional.condition(), parameters)
                    && pureExpression(conditional.whenTrue(), parameters)
                    && pureExpression(conditional.whenFalse(), parameters);
        }
        return false;
    }

    private static Object constantValue(Ast.Expr expression, String where) {
        if (!(expression instanceof Ast.LiteralExpr literal)) {
            fail("MODEL_NONCONSTANT_CONFIG",
                    where + " must be a bounded literal or a declared model parameter");
        }
        Object value = literal.value();
        if (value instanceof Double number && !Double.isFinite(number)) {
            fail("MODEL_NONFINITE_CONST", where + " cannot be NaN or Infinity");
        }
        if (value instanceof String text && text.length() > 256) {
            fail("MODEL_INVALID_CONST", where + " string exceeds 256 UTF-16 code units");
        }
        if (!(value instanceof Long || value instanceof Double
                || value instanceof Boolean || value instanceof String
                || value instanceof dev.oreslang.runtime.OresSymbol)) {
            fail("MODEL_INVALID_CONST", where + " uses an unsupported constant kind");
        }
        return value;
    }

    private static Ast.TypeRef literalType(Object value) {
        if (value instanceof Long) return Ast.TypeRef.simple("int");
        if (value instanceof Double) return Ast.TypeRef.simple("float");
        if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
        if (value instanceof String) return Ast.TypeRef.simple("string");
        if (value instanceof dev.oreslang.runtime.OresSymbol) return Ast.TypeRef.simple("Symbol");
        fail("MODEL_INVALID_CONST", "unsupported literal constant");
        throw new AssertionError();
    }

    public static boolean sameType(Ast.TypeRef left, Ast.TypeRef right) {
        return canonicalType(left).equals(canonicalType(right));
    }

    private static void requireSameType(
            Ast.TypeRef actual,
            Ast.TypeRef expected,
            String code,
            String where) {
        if (!sameType(actual, expected)) {
            fail(code,
                    where + " expects " + canonicalType(expected)
                            + " but got " + canonicalType(actual));
        }
    }

    public static String canonicalType(Ast.TypeRef type) {
        if (type == null) return "<null>";
        String name = switch (type.name()) {
            case "boolean", "Bool" -> "bool";
            case "String" -> "string";
            case "f64", "f32" -> "float";
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
                    "uint", "bigint" -> "int";
            default -> type.name();
        };
        StringBuilder result = new StringBuilder(name);
        if (!type.arguments().isEmpty()) {
            result.append("<");
            for (int i = 0; i < type.arguments().size(); i++) {
                if (i != 0) result.append(",");
                result.append(canonicalType(type.arguments().get(i)));
            }
            result.append(">");
        }
        if (type.inferArguments()) result.append("<>");
        return result.toString();
    }

    private static int fixedScalarBytes(Ast.TypeRef type) {
        return switch (canonicalType(type)) {
            case "bool" -> 1;
            case "int", "float" -> 8;
            default -> -1;
        };
    }

    private static String operationAbi(Ast.FunctionDecl operation) {
        StringBuilder canonical = new StringBuilder("ores-model-op-v1(");
        for (int i = 0; i < operation.parameters().size(); i++) {
            if (i != 0) canonical.append(",");
            canonical.append(canonicalType(operation.parameters().get(i).type()));
        }
        canonical.append(")->").append(canonicalType(operation.returnType()));
        return canonical.toString();
    }

    private static String implementationDigest(Ast.FunctionDecl operation) {
        Ast.ReturnStmt returned = (Ast.ReturnStmt) operation.body().getFirst();
        StringBuilder canonical = new StringBuilder(operationAbi(operation));
        canonical.append("|return=").append(canonicalExpression(returned.value()));
        return sha256(canonical.toString());
    }

    private static String graphDigest(
            String qualifiedName,
            List<Parameter> parameters,
            List<Node> nodes) {
        StringBuilder canonical = new StringBuilder("ores-model-graph-v1|");
        canonical.append(qualifiedName).append("|");
        for (Parameter parameter : parameters) {
            canonical.append("p:")
                    .append(parameter.name()).append(":")
                    .append(canonicalType(parameter.type())).append(":")
                    .append(parameter.hasDefault()
                            ? canonicalConstant(parameter.defaultValue())
                            : "required")
                    .append("|");
        }
        for (Node node : nodes) {
            canonical.append(node.id()).append(":")
                    .append(node.operationName()).append(":")
                    .append(node.operationAbi()).append(":")
                    .append(node.implementationDigest()).append(":");
            for (StaticArg argument : node.staticArguments()) {
                canonical.append(argument.canonical()).append(",");
            }
            canonical.append("|");
        }
        return sha256(canonical.toString());
    }

    private static String canonicalExpression(Ast.Expr expression) {
        if (expression instanceof Ast.LiteralExpr literal) {
            return canonicalConstant(literal.value());
        }
        if (expression instanceof Ast.NameExpr name) return "name:" + name.name();
        if (expression instanceof Ast.UnaryExpr unary) {
            return "unary:" + unary.operator() + "("
                    + canonicalExpression(unary.operand()) + ")";
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return "binary:" + binary.operator() + "("
                    + canonicalExpression(binary.left()) + ","
                    + canonicalExpression(binary.right()) + ")";
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            return "cond(" + canonicalExpression(conditional.condition()) + ","
                    + canonicalExpression(conditional.whenTrue()) + ","
                    + canonicalExpression(conditional.whenFalse()) + ")";
        }
        fail("MODEL_EFFECTFUL_BODY", "unsupported expression in verified operation");
        throw new AssertionError();
    }

    private static String canonicalConstant(Object value) {
        if (value instanceof Long number) return "i64:" + number;
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                fail("MODEL_NONFINITE_CONST", "nonfinite floating-point model constant");
            }
            return "f64bits:" + String.format(
                    java.util.Locale.ROOT,
                    "%016x",
                    Double.doubleToRawLongBits(number));
        }
        if (value instanceof Boolean bool) return "bool:" + bool;
        if (value instanceof String text) {
            return "string:" + text.length() + ":" + text;
        }
        if (value instanceof dev.oreslang.runtime.OresSymbol symbol) {
            String text = String.valueOf(symbol);
            return "symbol:" + text.length() + ":" + text;
        }
        fail("MODEL_INVALID_CONST", "unsupported model constant");
        throw new AssertionError();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String qualify(String module, String name) {
        return module == null || module.equals(Parser.ROOT_MODULE)
                ? name
                : module + "." + name;
    }

    private static void fail(String code, String message) {
        throw new IllegalArgumentException(code + ": " + message);
    }
}
