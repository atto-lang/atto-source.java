package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed, non-executable MIR for a deliberately small subset of private
 * out-of-line critical actor methods. The only state authority is lexical:
 * field accesses are bound to a sealed schema and occur inside one explicit
 * write loan. No guest callback or user-provided runtime capability exists.
 *
 * This does not authorize execution; TypeChecker retains its unconditional
 * CRITICAL_ACTOR_STATE_LOWERING_UNSUPPORTED error until trusted actor dispatch,
 * non-reentrant runtime scheduling and generation-fenced memory are integrated.
 */
public final class CriticalActorMirV1 {
    private CriticalActorMirV1() { }

    public enum Type { I64, BOOL, VOID }

    public sealed interface Expr permits Literal, Local, Field, Unary, Binary {
        Type type();
    }
    public record Literal(Object value, Type type) implements Expr { }
    public record Local(String name, Type type) implements Expr { }
    public record Field(String name, Type type) implements Expr { }
    public record Unary(String op, Expr operand, Type type) implements Expr { }
    public record Binary(String op, Expr left, Expr right, Type type) implements Expr { }

    public sealed interface Step permits Declare, SetLocal, SetField, Branch, WriteLoan, Return { }
    public record Declare(String name, Type type, Expr initialValue) implements Step { }
    public record SetLocal(String name, Expr value) implements Step { }
    public record SetField(String field, Expr value) implements Step { }
    public record Branch(Expr condition, List<Step> yes, List<Step> no) implements Step {
        public Branch {
            yes = List.copyOf(yes);
            no = List.copyOf(no);
        }
    }
    public record WriteLoan(List<Step> body) implements Step {
        public WriteLoan { body = List.copyOf(body); }
    }
    public record Return(Expr value) implements Step { }

    public record Method(String actor, String name, Map<String, Type> parameters,
                         Type returns, List<Step> steps) {
        public Method {
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            steps = List.copyOf(steps);
        }
    }

    /**
     * Bind all supplied source units against one package namespace before
     * lowering any method. A caller must not substitute a hand-built schema
     * from a different actor or namespace.
     */
    public static Map<ActorStateBinder.Slot, List<Method>> lowerSourceSet(
            String packageIdentity, Map<String, Ast.Program> sourceUnits) {
        Map<ActorStateBinder.Slot, ActorStateBinder.Binding> states =
                ActorStateBinder.bindSourceSet(packageIdentity, sourceUnits);
        Map<ActorStateBinder.Slot, List<Method>> output = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Program> unit : new java.util.TreeMap<>(sourceUnits).entrySet()) {
            String namespace = unit.getValue().namespace();
            for (Ast.ModuleDecl module : unit.getValue().modules()) {
                if (!dev.oreslang.parser.Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl decl : module.declarations()) {
                    if (!(decl instanceof Ast.CriticalActorMethodDecl critical)) continue;
                    var slot = new ActorStateBinder.Slot(
                            packageIdentity, namespace, critical.actorName());
                    var binding = states.get(slot);
                    if (binding == null) {
                        throw rejected("critical actor method lacks a sealed attached state schema: "
                                + critical.actorName());
                    }
                    output.computeIfAbsent(slot, ignored -> new ArrayList<>())
                            .add(lower(binding.schema(), critical));
                }
            }
        }
        Map<ActorStateBinder.Slot, List<Method>> frozen = new LinkedHashMap<>();
        output.forEach((slot, methods) -> frozen.put(slot, List.copyOf(methods)));
        return Collections.unmodifiableMap(frozen);
    }

    /** Build a typed MIR tree only; never execute or publish actor state. */
    public static Method lower(Ast.ActorStateDecl schema, Ast.CriticalActorMethodDecl method) {
        Objects.requireNonNull(schema, "sealed actor state");
        Objects.requireNonNull(method, "critical actor method");
        if (!schema.actorName().equals(method.actorName())
                || method.visibility() != Ast.Visibility.PRIVATE) {
            throw rejected("critical receiver does not own this sealed state");
        }
        Map<String, Type> fields = new LinkedHashMap<>();
        for (Ast.ProtectedStateField field : schema.fields()) {
            if (field.type().isBorrow() || field.type().inferArguments()
                    || !field.type().arguments().isEmpty()) {
                throw rejected("sealed field must have non-generic owned scalar type");
            }
            Type type = type(field.type().name(), false);
            if (!(field.initializer() instanceof Ast.LiteralExpr literal)
                    || !literalTypeMatches(type, literal.value())
                    || fields.putIfAbsent(field.name(), type) != null) {
                throw rejected("field initializer or sealed layout invalid: " + field.name());
            }
        }
        Map<String, Type> parameters = new LinkedHashMap<>();
        for (Ast.Param param : method.parameters()) {
            if (param.structural() || param.mutable()
                    || param.type().isBorrow() || param.type().inferArguments()
                    || !param.type().arguments().isEmpty()
                    || parameters.putIfAbsent(param.name(), type(param.type().name(), false)) != null
                    || param.name().equals(method.receiverName())) {
                throw rejected("critical arguments must be distinct copied bool/int scalars");
            }
        }
        if (method.returnType().isBorrow() || method.returnType().inferArguments()
                || !method.returnType().arguments().isEmpty()) {
            throw rejected("critical return cannot borrow or expose a generic actor value");
        }
        Type returns = type(method.returnType().name(), true);
        Cursor parser = new Cursor(method.bodyTokens(), method.bodyTokenKinds(),
                method.receiverName(), fields, parameters, returns);
        List<Step> steps = parser.block(false, false);
        parser.requireEnd();
        if (steps.isEmpty() || !(steps.getLast() instanceof Return)) {
            throw rejected("critical method requires a terminal return after releasing its loan");
        }
        return new Method(method.actorName(), method.name(), parameters, returns, steps);
    }

    private static boolean literalTypeMatches(Type expected, Object value) {
        return expected == Type.I64 && value instanceof Long
                || expected == Type.BOOL && value instanceof Boolean;
    }

    private static Type type(String name, boolean allowVoid) {
        return switch (name) {
            case "int" -> Type.I64;
            case "bool" -> Type.BOOL;
            case "void" -> {
                if (!allowVoid) throw rejected("void scalar field or parameter");
                yield Type.VOID;
            }
            default -> throw rejected("unsupported critical scalar type: " + name);
        };
    }

    private static void require(Type actual, Type expected, String reason) {
        if (actual != expected) throw rejected(reason + " expected " + expected + " got " + actual);
    }

    private static IllegalArgumentException rejected(String reason) {
        return new IllegalArgumentException("CRITICAL_ACTOR_TYPED_MIR_REJECTED: " + reason);
    }

    private static final class Cursor {
        private final List<String> lexemes;
        private final List<String> kinds;
        private final String receiver;
        private final Map<String, Type> fields;
        private final Map<String, Type> variables;
        private final Type returns;
        private String activeAlias;
        private int offset;

        Cursor(List<String> lexemes, List<String> kinds, String receiver,
               Map<String, Type> fields, Map<String, Type> parameters, Type returns) {
            this.lexemes = lexemes;
            this.kinds = kinds;
            this.receiver = receiver;
            this.fields = fields;
            this.variables = new LinkedHashMap<>(parameters);
            this.returns = returns;
            if (lexemes.size() != kinds.size()) throw rejected("lost token provenance");
        }

        private boolean at(String value) {
            return offset < lexemes.size() && lexemes.get(offset).equals(value);
        }
        private boolean kind(String type) {
            return offset < kinds.size() && kinds.get(offset).equals(type);
        }
        private String take(String expected) {
            if (!at(expected)) throw rejected("expected '" + expected
                    + "' at token " + offset);
            return lexemes.get(offset++);
        }
        private String identifier() {
            if (!kind("IDENT")) throw rejected("identifier expected at token " + offset);
            return lexemes.get(offset++);
        }
        void requireEnd() {
            if (offset != lexemes.size()) throw rejected("trailing critical tokens");
        }

        /**
         * Closed-world statement grammar. Even if the lexical parser admits
         * additional tokens, anything without typed MIR lowering fails here.
         */
        List<Step> block(boolean inLoan, boolean untilBranchEnd) {
            List<Step> result = new ArrayList<>();
            while (offset < lexemes.size()) {
                if (inLoan && at("}")) break;
                if (untilBranchEnd && (at("fi") || at("else"))) break;
                if (at("let")) {
                    take("let");
                    String primitive = identifier();
                    Type declared = type(primitive, false);
                    String name = identifier();
                    if (name.equals(receiver) || name.equals("state")
                            || name.equals(activeAlias) || variables.containsKey(name)
                            || fields.containsKey(name)) {
                        throw rejected("local shadows protected actor identity");
                    }
                    take("=");
                    Expr initial = expression();
                    require(initial.type(), declared, "local initializer");
                    take(";");
                    variables.put(name, declared);
                    result.add(new Declare(name, declared, initial));
                } else if (at("using")) {
                    if (inLoan) throw rejected("nested critical write loan");
                    result.add(parseLoan());
                } else if (at("if")) {
                    take("if");
                    Expr condition = expression();
                    require(condition.type(), Type.BOOL, "critical branch guard");
                    take("then");
                    // Branch-local names are never visible after the branch.
                    Map<String, Type> scope = new LinkedHashMap<>(variables);
                    List<Step> yes = block(inLoan, true);
                    variables.clear();
                    variables.putAll(scope);
                    List<Step> no = List.of();
                    if (at("else")) {
                        take("else");
                        no = block(inLoan, true);
                        variables.clear();
                        variables.putAll(scope);
                    }
                    take("fi");
                    result.add(new Branch(condition, yes, no));
                } else if (at("return")) {
                    if (inLoan) throw rejected("return while a write loan is live");
                    take("return");
                    Expr value = at(";") ? null : expression();
                    if (value == null) {
                        require(returns, Type.VOID, "empty return");
                    } else {
                        require(value.type(), returns, "return type");
                    }
                    take(";");
                    result.add(new Return(value));
                    if (offset < lexemes.size()) throw rejected("unreachable tokens after return");
                } else if (kind("IDENT")) {
                    String name = identifier();
                    if (name.equals(activeAlias) && inLoan) {
                        take(".");
                        String field = identifier();
                        Type target = fields.get(field);
                        if (target == null) throw rejected("unknown sealed field: " + field);
                        take("=");
                        Expr value = expression();
                        require(value.type(), target, "sealed field write");
                        take(";");
                        result.add(new SetField(field, value));
                    } else {
                        Type target = variables.get(name);
                        if (target == null) throw rejected("unknown or protected variable: " + name);
                        take("=");
                        Expr value = expression();
                        require(value.type(), target, "local assignment");
                        take(";");
                        result.add(new SetLocal(name, value));
                    }
                } else {
                    throw rejected("unsupported critical statement: " + lexemes.get(offset));
                }
                if (result.size() > 4096) throw rejected("critical MIR instruction budget");
            }
            return List.copyOf(result);
        }

        private WriteLoan parseLoan() {
            take("using");
            take("state");
            take(".");
            take("write");
            take("(");
            take("critical");
            take("|");
            String alias = identifier();
            if (alias.equals(receiver) || alias.equals("state")
                    || variables.containsKey(alias) || fields.containsKey(alias)) {
                throw rejected("critical loan alias shadows existing identity");
            }
            take("|");
            take("->");
            take("{");
            Map<String, Type> outerScope = new LinkedHashMap<>(variables);
            activeAlias = alias;
            List<Step> body;
            try {
                body = block(true, false);
            } finally {
                activeAlias = null;
                variables.clear();
                variables.putAll(outerScope);
            }
            take("}");
            take(")");
            take(";");
            if (body.isEmpty()) throw rejected("empty critical write loan");
            return new WriteLoan(body);
        }

        private Expr expression() { return parseOr(); }
        private Expr parseOr() {
            Expr left = parseAnd();
            while (at("or") || at("|")) {
                String op = lexemes.get(offset++);
                Expr right = parseAnd();
                require(left.type(), Type.BOOL, "or left");
                require(right.type(), Type.BOOL, "or right");
                left = new Binary(op, left, right, Type.BOOL);
            }
            return left;
        }
        private Expr parseAnd() {
            Expr left = parseEquality();
            while (at("and")) {
                take("and");
                Expr right = parseEquality();
                require(left.type(), Type.BOOL, "and left");
                require(right.type(), Type.BOOL, "and right");
                left = new Binary("and", left, right, Type.BOOL);
            }
            return left;
        }
        private Expr parseEquality() {
            Expr left = parseComparison();
            while (at("==") || at("!=") || at("eq") || at("neq")) {
                String op = lexemes.get(offset++);
                Expr right = parseComparison();
                if (left.type() != right.type() || left.type() == Type.VOID)
                    throw rejected("equality operands must have identical scalar types");
                left = new Binary(op, left, right, Type.BOOL);
            }
            return left;
        }
        private Expr parseComparison() {
            Expr left = parseAddition();
            while (at("<") || at(">") || at("<=") || at(">=")) {
                String op = lexemes.get(offset++);
                Expr right = parseAddition();
                require(left.type(), Type.I64, "comparison left");
                require(right.type(), Type.I64, "comparison right");
                left = new Binary(op, left, right, Type.BOOL);
            }
            return left;
        }
        private Expr parseAddition() {
            Expr left = parseUnary();
            while (at("+") || at("-")) {
                String op = lexemes.get(offset++);
                Expr right = parseUnary();
                require(left.type(), Type.I64, "arithmetic left");
                require(right.type(), Type.I64, "arithmetic right");
                // MIR arithmetic must lower to checked i64 operations; no wrap.
                left = new Binary(op.equals("+") ? "add_checked" : "sub_checked",
                        left, right, Type.I64);
            }
            return left;
        }
        private Expr parseUnary() {
            if (at("!") || at("-")) {
                String op = lexemes.get(offset++);
                Expr operand = parseUnary();
                require(operand.type(), op.equals("!") ? Type.BOOL : Type.I64,
                        "unary operand");
                return new Unary(op.equals("-") ? "neg_checked" : "not",
                        operand, operand.type());
            }
            return primary();
        }
        private Expr primary() {
            if (at("(")) {
                take("(");
                Expr e = expression();
                take(")");
                return e;
            }
            if (kind("INT")) {
                String source = lexemes.get(offset++);
                try {
                    return new Literal(Long.parseLong(source.replace("_", "")), Type.I64);
                } catch (NumberFormatException rejected) {
                    throw rejected("i64 literal overflow");
                }
            }
            if (at("true") || at("false")) {
                return new Literal(Boolean.valueOf(lexemes.get(offset++)), Type.BOOL);
            }
            if (!kind("IDENT")) throw rejected("unsupported critical expression at " + offset);
            String name = identifier();
            if (name.equals(activeAlias)) {
                take(".");
                String field = identifier();
                Type fieldType = fields.get(field);
                if (fieldType == null) throw rejected("sealed field does not exist: " + field);
                if (at("(")) throw rejected("method calls through state loan are forbidden");
                return new Field(field, fieldType);
            }
            Type variable = variables.get(name);
            if (variable == null) {
                throw rejected("unbound variable or escaped state alias: " + name);
            }
            if (at("(") || at(".")) throw rejected("calls and member escapes are forbidden");
            return new Local(name, variable);
        }
    }
}
