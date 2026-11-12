package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ActorReceiverBinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Strictly typed, source-bound PRE-HIR for simple external actor method bodies.
 *
 * <p>No executable callable, actor reference, mailbox capability, state loan,
 * JVM method, or HIR/MIR authority is produced here. This v1 verifies only
 * terminal scalar returns, immutable parameter reads, scalar literals and
 * int arithmetic. Every other expression/statement fails CLOSED instead of
 * silently erasing effects, dynamic calls, state access or borrow boundaries.
 * Checked integer arithmetic and divide-by-zero traps require a subsequent
 * runtime lowering gate and MUST NOT be executed directly from this IR.
 */
public final class ActorTypedMethodIR {
    private ActorTypedMethodIR() { }

    public static final String SCHEMA = "atto-typed-external-mtd-prehir-v1";
    public static final int MAX_METHODS = 4096;
    public static final int MAX_BODY_STATEMENTS = 1;
    public static final int MAX_EXPRESSION_DEPTH = 32;
    public static final int MAX_EXPRESSION_NODES = 256;

    public sealed interface Value permits ParameterRead, ScalarLiteral, CheckedIntBinary {
        String typeName();
    }

    /** No actor receiver is exposed as a value, only scalar method arguments. */
    public record ParameterRead(String name, int position, String typeName)
            implements Value {
        public ParameterRead {
            if (name == null || name.isBlank() || position < 0) {
                throw new IllegalArgumentException("invalid typed actor parameter reference");
            }
            scalar(typeName, false);
        }
    }

    /** Values are limited to deeply immutable boxed scalar Java primitives. */
    public record ScalarLiteral(String typeName, Object value) implements Value {
        public ScalarLiteral {
            scalar(typeName, false);
            if (!(typeName.equals("int") && value instanceof Long)
                    && !(typeName.equals("bool") && value instanceof Boolean)
                    && !(typeName.equals("string") && value instanceof String)) {
                throw new IllegalArgumentException("typed actor literal/type mismatch");
            }
        }
    }

    /** Checked arithmetic is a *requirement*, not a compiled operation. */
    public record CheckedIntBinary(String operator, Value left, Value right)
            implements Value {
        public CheckedIntBinary {
            if (!Set.of("+", "-", "*", "/", "%").contains(operator)
                    || left == null || right == null
                    || !left.typeName().equals("int")
                    || !right.typeName().equals("int")) {
                throw new IllegalArgumentException(
                        "typed actor integer arithmetic requires two int values");
            }
        }
        @Override public String typeName() { return "int"; }
    }

    /** Null return value is permitted only for a void method. */
    public record ReturnTerminator(String returnType, Value value) {
        public ReturnTerminator {
            scalar(returnType, true);
            if (returnType.equals("void") ? value != null
                    : value == null || !returnType.equals(value.typeName())) {
                throw new IllegalArgumentException(
                        "typed actor return value must match method return type");
            }
        }
    }

    public record TypedMethod(
            ActorMethodIndex.MethodKey key, String methodSourceUnit,
            List<ParameterRead> parameters, ReturnTerminator terminator,
            String sourceSha256) {
        public TypedMethod {
            Objects.requireNonNull(key, "typed actor method key");
            requireText(methodSourceUnit, "typed actor method source");
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(terminator, "typed method terminator");
            requireSha(sourceSha256, "typed method source fingerprint");
            if (parameters.size() != key.arity()) {
                throw new IllegalArgumentException("typed actor method arity mismatch");
            }
        }
    }

    /**
     * Immutable actor-level PRE-HIR. The descriptor's sealed state layout is
     * present for type identity and layout checks ONLY. No state handle,
     * scheduler permit, mailbox entrypoint or critical loan is represented.
     */
    public static final class ActorPlan {
        private final ActorDescriptorIndex.Descriptor descriptor;
        private final List<TypedMethod> checkedMethods;

        private ActorPlan(
                ActorDescriptorIndex.Descriptor descriptor,
                List<TypedMethod> checkedMethods) {
            this.descriptor = Objects.requireNonNull(descriptor, "actor descriptor");
            this.checkedMethods = List.copyOf(checkedMethods);
            for (TypedMethod method : checkedMethods) {
                if (!method.key().actor().equals(descriptor.key())) {
                    throw new IllegalArgumentException(
                            "ACTOR_TYPED_IR_PROVENANCE_MISMATCH: alien actor method in plan");
                }
            }
        }

        public ActorDescriptorIndex.Descriptor descriptor() { return descriptor; }
        public List<TypedMethod> checkedMethods() { return checkedMethods; }
        public String stateLoanStatus() { return "SEALED_STATE_LOANS_UNLOWERED"; }
        public String mailboxDispatchStatus() { return "MAILBOX_DISPATCH_UNLOWERED"; }
        public boolean executable() { return false; }
    }

    /** A static checked expression plan, never an executable capability. */
    public static final class Result {
        private final Map<ActorMethodIndex.MethodKey, TypedMethod> methods;
        private final Map<ActorMethodIndex.ActorKey, ActorPlan> actors;

        private Result(
                Map<ActorMethodIndex.MethodKey, TypedMethod> methods,
                Map<ActorMethodIndex.ActorKey, ActorPlan> actors) {
            this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(methods));
            this.actors = Collections.unmodifiableMap(new LinkedHashMap<>(actors));
        }

        public Map<ActorMethodIndex.MethodKey, TypedMethod> methods() {
            return methods;
        }

        public TypedMethod method(ActorMethodIndex.MethodKey key) {
            return methods.get(key);
        }

        public Map<ActorMethodIndex.ActorKey, ActorPlan> actors() { return actors; }
        public ActorPlan actor(ActorMethodIndex.ActorKey key) { return actors.get(key); }
    }

    /**
     * Package-owned construction: the caller MUST derive audit and parsed
     * sources from the exact same manifest snapshot. Never call this from
     * raw-map IncrementalCompiler, guest code, a synthetic AST, or codegen.
     */
    static Result checkSnapshot(
            String packageIdentity,
            Map<String, Ast.Program> parsed,
            PackageNamespaceBuild.ActorAudit audit) {
        requireText(packageIdentity, "typed actor package identity");
        Objects.requireNonNull(parsed, "typed actor source programs");
        Objects.requireNonNull(audit, "manifest actor audit");
        if (!packageIdentity.equals(audit.contractWitness().packageIdentity())
                || !parsed.keySet().equals(audit.sourceSha256().keySet())) {
            throw new IllegalArgumentException(
                    "ACTOR_TYPED_IR_PROVENANCE_MISMATCH: package/source-set mismatch");
        }

        Map<ActorMethodIndex.MethodKey, TypedMethod> checked = new LinkedHashMap<>();
        int count = 0;
        for (var actorEntry : audit.descriptors().actors().entrySet()) {
            ActorMethodIndex.ActorKey actorKey = actorEntry.getKey();
            ActorDescriptorIndex.Descriptor descriptor = actorEntry.getValue();
            if (!packageIdentity.equals(actorKey.packageId())
                    || !descriptor.key().equals(actorKey)
                    || !descriptor.manifestSha256().equals(audit.manifestSha256())) {
                throw new IllegalArgumentException(
                        "ACTOR_TYPED_IR_PROVENANCE_MISMATCH: actor descriptor mismatch");
            }
            for (ActorDescriptorIndex.Method signature : descriptor.methods()) {
                if (++count > MAX_METHODS) {
                    throw new IllegalArgumentException(
                            "ACTOR_TYPED_IR_LIMIT: too many external actor methods");
                }
                ActorMethodIndex.MethodKey key = new ActorMethodIndex.MethodKey(
                        actorKey, signature.name(), signature.arity());
                ActorReceiverBinder.SourceSlot sourceKey = new ActorReceiverBinder.SourceSlot(
                        packageIdentity, actorKey.namespace(), actorKey.actorName(),
                        signature.name(), signature.arity());
                ActorReceiverBinder.SourceBinding origin =
                        audit.contractWitness().boundMethods().get(sourceKey);
                var indexed = audit.actorMethods().method(key);
                if (origin == null || indexed == null
                        || !origin.actorUnit().equals(descriptor.actorUnit())
                        || !origin.methodUnit().equals(signature.sourceUnit())
                        || !indexed.declaringUnit().equals(signature.sourceUnit())
                        || indexed.visibility() != signature.visibility()) {
                    throw new IllegalArgumentException(
                            "ACTOR_TYPED_IR_PROVENANCE_MISMATCH: missing exact actor method slot "
                                    + key);
                }
                Ast.Program sourceProgram = parsed.get(signature.sourceUnit());
                if (sourceProgram == null
                        || !actorKey.namespace().equals(sourceProgram.namespace())) {
                    throw new IllegalArgumentException(
                            "ACTOR_TYPED_IR_PROVENANCE_MISMATCH: missing source namespace");
                }
                Ast.ExternalActorMethodDecl method = origin.nominalBinding().method();
                Ast.Program actorProgram = parsed.get(origin.actorUnit());
                if (actorProgram == null
                        || !actorKey.namespace().equals(actorProgram.namespace())
                        || !hasExactRootDeclaration(actorProgram,
                                origin.nominalBinding().nominalActor())
                        || !hasExactRootDeclaration(sourceProgram, method)) {
                    // Equal AST *values* parsed independently are not the
                    // authenticated AST identities used by the binder. This
                    // closes stale/mixed snapshot reuse of actor method IR.
                    throw new IllegalArgumentException(
                            "ACTOR_TYPED_IR_SOURCE_AST_IDENTITY_MISMATCH: " + key);
                }
                if (!method.actorName().equals(actorKey.actorName())
                        || !method.name().equals(signature.name())
                        || method.parameters().size() != signature.arity()
                        || !method.returnType().name().equals(signature.returnType())
                        || method.returnType().inferArguments()
                        || !method.returnType().arguments().isEmpty()) {
                    throw new IllegalArgumentException(
                            "ACTOR_TYPED_IR_SIGNATURE_MISMATCH: " + key);
                }
                var parameters = new ArrayList<ParameterRead>();
                var byName = new LinkedHashMap<String, ParameterRead>();
                for (int i = 0; i < method.parameters().size(); i++) {
                    Ast.Param p = method.parameters().get(i);
                    if (p.name().equals(method.receiverName())
                            || p.type().isBorrow() || p.type().inferArguments()
                            || !p.type().arguments().isEmpty()
                            || !p.type().name().equals(signature.parameterTypes().get(i))
                            || p.mutable() != signature.mutableParameters().get(i)
                            || p.structural()) {
                        throw new IllegalArgumentException(
                                "ACTOR_TYPED_IR_SIGNATURE_MISMATCH: parameter " + i + " of " + key);
                    }
                    var parameter = new ParameterRead(p.name(), i, p.type().name());
                    if (byName.putIfAbsent(p.name(), parameter) != null) {
                        throw new IllegalArgumentException(
                                "ACTOR_TYPED_IR_DUPLICATE_PARAMETER: " + p.name());
                    }
                    parameters.add(parameter);
                }
                ReturnTerminator terminal = checkTerminal(
                        method.body(), signature.returnType(), method.receiverName(),
                        byName, key);
                String hash = audit.sourceSha256().get(signature.sourceUnit());
                var body = new TypedMethod(key, signature.sourceUnit(), parameters,
                        terminal, hash);
                if (checked.putIfAbsent(key, body) != null) {
                    throw new IllegalArgumentException(
                            "ACTOR_TYPED_IR_DUPLICATE_METHOD_SLOT: " + key);
                }
            }
        }
        // The nominal binder can recognize private lifecycle hooks such as
        // on_start/tear_down/receive without granting them public slots.
        // Never silently omit such opaque bodies from a "typed" actor plan:
        // CONTROL/receive require a separate compiler-owned effect/runtime IR.
        for (var sourceSlot : audit.contractWitness().boundMethods().keySet()) {
            var actorKey = new ActorMethodIndex.ActorKey(
                    sourceSlot.packageIdentity(), sourceSlot.namespace(),
                    sourceSlot.actor());
            if (!audit.descriptors().actors().containsKey(actorKey)) continue;
            var key = new ActorMethodIndex.MethodKey(
                    actorKey, sourceSlot.name(), sourceSlot.arity());
            if (!checked.containsKey(key)) {
                throw new IllegalArgumentException(
                        "ACTOR_TYPED_IR_CONTROL_UNSUPPORTED: unlowered actor-owned "
                                + "lifecycle, receive or other method body: " + sourceSlot);
            }
        }
        if (checked.size() != audit.actorMethods().methods().keySet().stream()
                .filter(key -> audit.descriptors().actors().containsKey(key.actor()))
                .count()) {
            throw new IllegalArgumentException(
                    "ACTOR_TYPED_IR_PROVENANCE_MISMATCH: unrepresented external method");
        }
        Map<ActorMethodIndex.ActorKey, ActorPlan> plans = new LinkedHashMap<>();
        for (var descriptor : audit.descriptors().actors().entrySet()) {
            var typed = checked.entrySet().stream()
                    .filter(entry -> entry.getKey().actor().equals(descriptor.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();
            if (typed.size() != descriptor.getValue().methods().size()) {
                throw new IllegalArgumentException(
                        "ACTOR_TYPED_IR_PROVENANCE_MISMATCH: incomplete actor method plan");
            }
            if (plans.putIfAbsent(descriptor.getKey(),
                    new ActorPlan(descriptor.getValue(), typed)) != null) {
                throw new IllegalArgumentException(
                        "ACTOR_TYPED_IR_PROVENANCE_MISMATCH: duplicate actor plan");
            }
        }
        return new Result(checked, plans);
    }

    private static boolean hasExactRootDeclaration(
            Ast.Program program, Ast.Decl expected) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (!dev.oreslang.parser.Parser.ROOT_MODULE.equals(module.name())) {
                continue;
            }
            for (Ast.Decl candidate : module.declarations()) {
                if (candidate == expected) return true;
            }
        }
        return false;
    }

    private static ReturnTerminator checkTerminal(
            List<Ast.Stmt> body, String resultType, String receiver,
            Map<String, ParameterRead> parameters,
            ActorMethodIndex.MethodKey key) {
        scalar(resultType, true);
        if (body.isEmpty() && resultType.equals("void")) {
            return new ReturnTerminator("void", null);
        }
        // Deliberately no implicit control-flow merge, local captures, loans,
        // effectful calls, unbounded expressions, exceptions or await/select.
        if (body.size() != 1 || !(body.getFirst() instanceof Ast.ReturnStmt ret)) {
            throw unsupported(key, "v1 requires exactly one terminal return");
        }
        if (ret.value() == null) {
            if (!resultType.equals("void")) {
                throw new IllegalArgumentException(
                        "ACTOR_TYPED_IR_RETURN_MISMATCH: missing non-void return in " + key);
            }
            return new ReturnTerminator("void", null);
        }
        if (resultType.equals("void")) {
            throw new IllegalArgumentException(
                    "ACTOR_TYPED_IR_RETURN_MISMATCH: value returned from void " + key);
        }
        int[] nodeBudget = {0};
        Value value = checkValue(ret.value(), receiver, parameters, key, 0, nodeBudget);
        if (!resultType.equals(value.typeName())) {
            throw new IllegalArgumentException(
                    "ACTOR_TYPED_IR_RETURN_MISMATCH: " + key
                            + " declares " + resultType + " but returns " + value.typeName());
        }
        return new ReturnTerminator(resultType, value);
    }

    private static Value checkValue(
            Ast.Expr expr, String receiver, Map<String, ParameterRead> params,
            ActorMethodIndex.MethodKey key, int depth, int[] nodes) {
        if (expr == null || depth > MAX_EXPRESSION_DEPTH
                || ++nodes[0] > MAX_EXPRESSION_NODES) {
            throw unsupported(key, "expression depth/node budget exceeded");
        }
        if (expr instanceof Ast.NameExpr name) {
            if (name.name().equals(receiver)) {
                throw unsupported(key, "actor receiver is not an ordinary value/capability");
            }
            ParameterRead param = params.get(name.name());
            if (param == null) {
                throw unsupported(key,
                        "unbound name/capture, state or global access: " + name.name());
            }
            return param;
        }
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof Long) return new ScalarLiteral("int", value);
            if (value instanceof Boolean) return new ScalarLiteral("bool", value);
            if (value instanceof String) return new ScalarLiteral("string", value);
            throw unsupported(key, "unsupported literal type");
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            if (!Set.of("+", "-", "*", "/", "%").contains(binary.operator())) {
                throw unsupported(key, "non-arithmetic/unchecked operator");
            }
            Value left = checkValue(binary.left(), receiver, params, key, depth + 1, nodes);
            Value right = checkValue(binary.right(), receiver, params, key, depth + 1, nodes);
            if (!left.typeName().equals("int") || !right.typeName().equals("int")) {
                throw unsupported(key, "integer arithmetic requires exactly int operands");
            }
            // Analyze only fully constant integer subtrees. Never execute
            // dynamic arithmetic or rely on Java's unchecked overflow.
            // Checking the divisor after recursive exact-folding detects
            // constants such as 1 - 1, not just literal 0.
            Long denominator = constantInt(right, key);
            if ((binary.operator().equals("/") || binary.operator().equals("%"))
                    && denominator != null && denominator == 0L) {
                throw new IllegalArgumentException(
                        "ACTOR_TYPED_IR_DIVIDE_BY_ZERO: " + key
                                + " has a provably zero integer divisor");
            }
            var typed = new CheckedIntBinary(binary.operator(), left, right);
            constantInt(typed, key); // rejects statically provable overflow
            return typed;
        }
        // In particular, never infer effect/ownership semantics of CallExpr,
        // MemberExpr, AwaitExpr, AssignExpr, LambdaExpr or state access from
        // textual spelling. Typed state-loan MIR is a separate milestone.
        throw unsupported(key,
                "expression node requires checked ownership/effect/critical lowering: "
                        + expr.getClass().getSimpleName());
    }

    /**
     * Exact, bounded constant analysis for checked int operations. A null
     * result means runtime-dependent; it NEVER licenses execution or
     * unwraps actor/ownership values. Nested division is deliberately left
     * dynamic because backend integer division semantics require MIR proof.
     */
    private static Long constantInt(Value value, ActorMethodIndex.MethodKey key) {
        if (value instanceof ScalarLiteral literal) {
            return literal.typeName().equals("int") && literal.value() instanceof Long integer
                    ? integer : null;
        }
        if (!(value instanceof CheckedIntBinary expr)) return null;
        Long left = constantInt(expr.left(), key);
        Long right = constantInt(expr.right(), key);
        if ((expr.operator().equals("/") || expr.operator().equals("%"))
                && right != null && right == 0L) {
            throw new IllegalArgumentException(
                    "ACTOR_TYPED_IR_DIVIDE_BY_ZERO: " + key
                            + " has a provably zero integer divisor");
        }
        if (left == null || right == null) return null;
        try {
            return switch (expr.operator()) {
                case "+" -> Math.addExact(left, right);
                case "-" -> Math.subtractExact(left, right);
                case "*" -> Math.multiplyExact(left, right);
                // Do not fold until compiler-owned checked division is
                // defined in MIR, including Long.MIN_VALUE / -1.
                case "/", "%" -> {
                    if (left == Long.MIN_VALUE && right == -1L) {
                        throw new ArithmeticException("signed division overflow");
                    }
                    yield null;
                }
                default -> throw new IllegalStateException(
                        "unexpected pre-HIR integer operator");
            };
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "ACTOR_TYPED_IR_INTEGER_OVERFLOW: " + key
                            + " has statically overflowing checked arithmetic", overflow);
        }
    }

    private static IllegalArgumentException unsupported(
            ActorMethodIndex.MethodKey key, String reason) {
        return new IllegalArgumentException(
                "ACTOR_TYPED_IR_UNSUPPORTED_BODY: " + key + ": " + reason);
    }

    private static void scalar(String name, boolean allowVoid) {
        if (name == null || !(name.equals("int") || name.equals("bool")
                || name.equals("string") || allowVoid && name.equals("void"))) {
            throw new IllegalArgumentException(
                    "ACTOR_TYPED_IR_TYPE_UNSUPPORTED: " + name);
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + label);
        }
    }

    private static void requireSha(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }
}
