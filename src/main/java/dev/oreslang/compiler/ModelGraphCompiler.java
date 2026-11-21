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
    public static final String PORTABLE_PROFILE_I64_V1 = "model-core-i64-v1";
    private static final int MAX_MODELS = 1024;
    private static final int MAX_NODES = 4096;
    private static final int MAX_CONFIG_PARAMS = 64;
    private static final int MAX_STATIC_ARGS = 32;
    private static final int MAX_TOTAL_STATIC_ARGS = 16384;
    private static final int MAX_MODEL_IDENTIFIER_LENGTH = 128;

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
            Set<String> portableProfiles,
            boolean freestandingEligible,
            boolean noDynamicAllocation,
            boolean deterministic) {
        public CompiledModel {
            parameters = List.copyOf(parameters);
            nodes = List.copyOf(nodes);
            portableProfiles = Set.copyOf(portableProfiles);
        }

        public boolean supportsPortableProfile(String profile) {
            return portableProfiles.contains(profile);
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

        /**
         * Digest of this blueprint after binding its immutable static configuration.
         * The declaration-level graphDigest remains the public blueprint ABI identity;
         * execution/cache/run identity must use this configured digest instead.
         */
        public String configuredGraphDigest(Map<String, Object> configuration) {
            if (configuration.size() != parameters.size()) {
                fail("MODEL_CONFIG_SHAPE",
                        "configured model '" + sourceName
                                + "' must bind exactly " + parameters.size() + " parameter(s)");
            }
            StringBuilder canonical = new StringBuilder("ores-model-configured-v1|");
            canonical.append(graphDigest).append("|");
            for (Parameter parameter : parameters) {
                if (!configuration.containsKey(parameter.name())) {
                    fail("MODEL_MISSING_CONFIG",
                            "configured model '" + sourceName
                                    + "' is missing parameter '" + parameter.name() + "'");
                }
                Object value = configuration.get(parameter.name());
                Ast.TypeRef actual = literalType(value);
                requireSameType(
                        actual,
                        parameter.type(),
                        "MODEL_CONFIG_TYPE_MISMATCH",
                        "configured model parameter '" + parameter.name() + "'");
                canonical.append(parameter.name())
                        .append("=")
                        .append(canonicalConstant(value))
                        .append("|");
            }
            for (String key : configuration.keySet()) {
                if (parameters.stream().noneMatch(parameter -> parameter.name().equals(key))) {
                    fail("MODEL_UNKNOWN_CONFIG",
                            "configured model '" + sourceName
                                    + "' has unknown parameter '" + key + "'");
                }
            }
            return sha256(canonical.toString());
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

        validateModelIdentifier(declaration.name(), "model");
        if (declaration.parameters().size() > MAX_CONFIG_PARAMS) {
            fail("MODEL_CONFIG_BUDGET",
                    "model '" + qualifiedName + "' exceeds "
                            + MAX_CONFIG_PARAMS + " configuration parameters");
        }

        LinkedHashMap<String, Ast.ModelParam> sourceParameters = new LinkedHashMap<>();
        ArrayList<Parameter> parameters = new ArrayList<>();
        boolean seenDefault = false;
        for (Ast.ModelParam parameter : declaration.parameters()) {
            validateModelIdentifier(parameter.name(), "model parameter");
            requireExecutableScalarType(
                    parameter.type(),
                    "MODEL_INVALID_CONFIG_TYPE",
                    "model parameter '" + parameter.name() + "'");
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
        int totalStaticArgs = 0;
        for (Ast.ModelOp sourceOperation : declaration.operations()) {
            validateModelIdentifier(sourceOperation.name(), "model operation");
            totalStaticArgs = Math.addExact(totalStaticArgs, sourceOperation.arguments().size());
            if (totalStaticArgs > MAX_TOTAL_STATIC_ARGS) {
                fail("MODEL_STATIC_ARG_BUDGET",
                        "model '" + qualifiedName + "' exceeds "
                                + MAX_TOTAL_STATIC_ARGS + " total static arguments");
            }
            if (sourceOperation.arguments().size() > MAX_STATIC_ARGS) {
                fail("MODEL_STATIC_ARG_BUDGET",
                        "operation '" + sourceOperation.name() + "' has too many static arguments");
            }

            Ast.ModelDecl nested = models.get(qualify(module, sourceOperation.name()));
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
                } else if (argument instanceof Ast.NameExpr name) {
                    fail("MODEL_UNKNOWN_FORMAL",
                            "unknown model parameter '" + name.name()
                                    + "' for operation '" + sourceOperation.name() + "'");
                    throw new AssertionError();
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
                portableI64V1(parameters, nodes)
                        ? Set.of(PORTABLE_PROFILE_I64_V1)
                        : Set.of(),
                false,
                false,
                true);
    }

    private static void validateModelIdentifier(String name, String label) {
        if (name == null || name.isEmpty()
                || name.length() > MAX_MODEL_IDENTIFIER_LENGTH
                || !asciiLetter(name.charAt(0))) {
            fail("MODEL_INVALID_IDENTIFIER",
                    label + " identifier is invalid or exceeds "
                            + MAX_MODEL_IDENTIFIER_LENGTH + " ASCII code units");
        }
        for (int i = 1; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!(asciiLetter(ch) || ch >= '0' && ch <= '9' || ch == '_')) {
                fail("MODEL_INVALID_IDENTIFIER",
                        label + " identifier '" + name + "' is not canonical ASCII");
            }
        }
    }

    private static boolean asciiLetter(char ch) {
        return ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z';
    }

    private static Ast.FunctionDecl findFunction(
            Map<String, Map<Integer, Ast.FunctionDecl>> functions,
            Map<String, String> uniqueOwners,
            Set<String> ambiguousNames,
            String module,
            String name,
            int arity) {
        // Executable v1 intentionally resolves model operations only in the
        // declaration's lexical module. A uniquely named function elsewhere
        // is not ambient authority. Cross-module ops need an explicit
        // import/provenance-aware contract in a later version.
        Map<Integer, Ast.FunctionDecl> local = functions.get(qualify(module, name));
        return local == null ? null : local.get(arity);
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
                || !operation.annotations().isEmpty()
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
            requireExecutableScalarType(
                    parameter.type(),
                    "MODEL_INVALID_OP_SIGNATURE",
                    "operation '" + sourceName + "' parameter '" + parameter.name() + "'");
            if (parameter.mutable() || parameter.structural()
                    || !parameters.add(parameter.name())) {
                fail("MODEL_INVALID_OP_SIGNATURE",
                        "operation '" + sourceName
                                + "' must use distinct immutable ordinary parameters");
            }
        }
        requireExecutableScalarType(
                operation.returnType(),
                "MODEL_INVALID_OP_SIGNATURE",
                "operation '" + sourceName + "' return type");
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
                    || value instanceof Boolean || value instanceof String
                    || value instanceof dev.oreslang.runtime.OresSymbol;
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

    private static boolean portableI64V1(
            List<Parameter> parameters,
            List<Node> nodes) {
        for (Parameter parameter : parameters) {
            if (!canonicalType(parameter.type()).equals("int")) return false;
            if (parameter.hasDefault() && !(parameter.defaultValue() instanceof Long)) return false;
        }
        for (Node node : nodes) {
            if (!canonicalType(node.inputType()).equals("int")
                    || !canonicalType(node.outputType()).equals("int")) {
                return false;
            }
            for (StaticArg argument : node.staticArguments()) {
                if (!canonicalType(argument.type()).equals("int")) return false;
                if (!argument.parameterReference() && !(argument.literal() instanceof Long)) return false;
            }
            LinkedHashSet<String> parameterNames = new LinkedHashSet<>();
            for (Ast.Param parameter : node.operation().parameters()) {
                if (!canonicalType(parameter.type()).equals("int")) return false;
                parameterNames.add(parameter.name());
            }
            if (!canonicalType(node.operation().returnType()).equals("int")) return false;
            Ast.ReturnStmt returned =
                    (Ast.ReturnStmt) node.operation().body().getFirst();
            if (!portableI64Expression(returned.value(), parameterNames)) return false;
        }
        return true;
    }

    private static boolean portableI64Expression(
            Ast.Expr expression,
            Set<String> parameters) {
        if (expression instanceof Ast.LiteralExpr literal) {
            return literal.value() instanceof Long;
        }
        if (expression instanceof Ast.NameExpr name) {
            return parameters.contains(name.name());
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return unary.operator().equals("-")
                    && portableI64Expression(unary.operand(), parameters);
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return Set.of("+", "-", "*").contains(binary.operator())
                    && portableI64Expression(binary.left(), parameters)
                    && portableI64Expression(binary.right(), parameters);
        }
        return false;
    }

    private static Object constantValue(Ast.Expr expression, String where) {
        Object value;
        if (expression instanceof Ast.LiteralExpr literal) {
            value = literal.value();
        } else if (expression instanceof Ast.UnaryExpr unary
                && unary.operator().equals("-")
                && unary.operand() instanceof Ast.LiteralExpr literal) {
            Object raw = literal.value();
            if (raw instanceof Long number) {
                if (number == Long.MIN_VALUE) {
                    fail("MODEL_CONST_OVERFLOW", where + " unary negation overflows i64");
                }
                value = -number;
            } else if (raw instanceof Double number) {
                value = -number;
            } else {
                fail("MODEL_NONCONSTANT_CONFIG",
                        where + " unary '-' requires a numeric literal");
                throw new AssertionError();
            }
        } else {
            fail("MODEL_NONCONSTANT_CONFIG",
                    where + " must be a bounded literal or a declared model parameter");
            throw new AssertionError();
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            fail("MODEL_NONFINITE_CONST", where + " cannot be NaN or Infinity");
        }
        if (value instanceof String text) {
            canonicalUtf8(text, where + " string");
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

    private static void requireExecutableScalarType(
            Ast.TypeRef type,
            String code,
            String where) {
        if (type == null
                || type.isBorrow()
                || type.inferArguments()
                || !type.arguments().isEmpty()
                || !Set.of("int", "float", "bool", "string", "Symbol")
                        .contains(canonicalType(type))) {
            fail(code,
                    where + " must be an owned executable-v1 scalar "
                            + "(int, float, bool, string, or Symbol)");
        }
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
        LinkedHashMap<String, Integer> parameterIndexes = new LinkedHashMap<>();
        for (int i = 0; i < operation.parameters().size(); i++) {
            parameterIndexes.put(operation.parameters().get(i).name(), i);
        }
        StringBuilder canonical = new StringBuilder(operationAbi(operation));
        canonical.append("|return=")
                .append(canonicalExpression(returned.value(), parameterIndexes));
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

    private static String canonicalExpression(
            Ast.Expr expression,
            Map<String, Integer> parameterIndexes) {
        if (expression instanceof Ast.LiteralExpr literal) {
            return canonicalConstant(literal.value());
        }
        if (expression instanceof Ast.NameExpr name) {
            Integer index = parameterIndexes.get(name.name());
            if (index == null) {
                fail("MODEL_EFFECTFUL_BODY",
                        "verified operation reads non-parameter '" + name.name() + "'");
            }
            return "arg:" + index;
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return "unary:" + unary.operator() + "("
                    + canonicalExpression(unary.operand(), parameterIndexes) + ")";
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return "binary:" + binary.operator() + "("
                    + canonicalExpression(binary.left(), parameterIndexes) + ","
                    + canonicalExpression(binary.right(), parameterIndexes) + ")";
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            return "cond(" + canonicalExpression(conditional.condition(), parameterIndexes) + ","
                    + canonicalExpression(conditional.whenTrue(), parameterIndexes) + ","
                    + canonicalExpression(conditional.whenFalse(), parameterIndexes) + ")";
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
            byte[] utf8 = canonicalUtf8(text, "model string constant");
            return "string:utf8:" + HexFormat.of().formatHex(utf8);
        }
        if (value instanceof dev.oreslang.runtime.OresSymbol symbol) {
            // Identity is the nominal Symbol kind plus its NFC-normalized name,
            // never the guest-facing #name / Symbol.stable(...) display syntax.
            // A presentation change must not invalidate model ABI/cache identities.
            String kind = switch (symbol.kind()) {
                case PROCESS -> "process";
                case STABLE -> "stable";
            };
            byte[] utf8 = canonicalUtf8(symbol.name(), "model symbol constant");
            return "symbol:" + kind + ":utf8:" + HexFormat.of().formatHex(utf8);
        }
        fail("MODEL_INVALID_CONST", "unsupported model constant");
        throw new AssertionError();
    }

    private static byte[] canonicalUtf8(String text, String where) {
        if (text.indexOf('\0') >= 0) {
            fail("MODEL_INVALID_CONST", where + " cannot contain NUL");
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    fail("MODEL_INVALID_CONST", where + " contains an unpaired UTF-16 surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                fail("MODEL_INVALID_CONST", where + " contains an unpaired UTF-16 surrogate");
            }
        }
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 256) {
            fail("MODEL_INVALID_CONST", where + " exceeds 256 UTF-8 bytes");
        }
        return utf8;
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
