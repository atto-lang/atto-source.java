package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Token;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checked, immutable *pre-lowering* MIR for the first scalar virtual-actor
 * method family. This does not create a runtime actor, grant a state loan,
 * evaluate guest code, or authorize a VirtualActorRef invocation.
 *
 * A method is accepted only when its entire lexer-provenance token stream
 * matches a finite syntax recognized by this checker. Unknown syntax fails
 * closed; there is no string search for "critical-write", dynamic evaluation,
 * lambda capture, inferred borrow or ordinary-class lowering.
 *
 * Executable admission remains blocked in TypeChecker until an AOT-safe
 * compiler-owned state access region, actor heap, CONTROL and typed method
 * dispatch have been implemented and verified.
 */
public final class VirtualActorProtectedMirV1 {
    private VirtualActorProtectedMirV1() { }

    /** No arbitrary objects, captured mutable references, or guest closures. */
    public record ScalarState(String name, Ast.TypeRef type, long initialValue) {
        public ScalarState {
            Objects.requireNonNull(name, "state field name");
            Objects.requireNonNull(type, "state field type");
        }
    }

    /**
     * Semantics: in one compiler-owned exclusive write loan,
     * state[field] := checkedAddI64(state[field], parameter).
     * This is a descriptor, NEVER an interpreter or permission to mutate.
     */
    public record CheckedAdd(String method, String parameter,
                             String field, Ast.TypeRef parameterType,
                             Ast.TypeRef returnType, int arity) {
        public CheckedAdd {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(parameterType, "parameterType");
            Objects.requireNonNull(returnType, "returnType");
            if (arity != 1) throw new IllegalArgumentException("checked-add arity must be one");
        }
    }

    /**
     * Compile-time nominal typed reference contract only. No raw physical
     * ActorId, state pointer, reflected class, or dynamic dispatch target.
     * The backend must still verify this type against its published code
     * image and logical-generation fence at actual invocation.
     */
    /** Only this checker can mint a typed reference contract. */
    public static final class TypedRefContract {
        private final String declaredActor;
        private final String spelling;
        private final List<CheckedAdd> methods;

        private TypedRefContract(String declaredActor, String spelling,
                                 List<CheckedAdd> methods) {
            this.declaredActor = Objects.requireNonNull(declaredActor, "declaredActor");
            this.spelling = Objects.requireNonNull(spelling, "spelling");
            this.methods = List.copyOf(methods);
        }

        public String declaredActor() { return declaredActor; }
        public String spelling() { return spelling; }
        public List<CheckedAdd> methods() { return methods; }

        public CheckedAdd method(String name, int arity) {
            Objects.requireNonNull(name, "method name");
            return methods.stream()
                    .filter(slot -> slot.method().equals(name) && slot.arity() == arity)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "virtual actor type has no checked method '" + name + "/" + arity + "'"));
        }

        /**
         * Compile-time exact method selector for statically resolved guest
         * argument types. A successful check is not a runtime invocation,
         * host capability or borrow permit.
         */
        public CheckedAdd requireCall(String methodName, List<Ast.TypeRef> arguments) {
            List<Ast.TypeRef> types = List.copyOf(Objects.requireNonNull(arguments));
            CheckedAdd selected = method(methodName, types.size());
            if (types.size() != 1 || !types.getFirst().equals(selected.parameterType()))
                throw new IllegalArgumentException(
                        "virtual actor call parameter type mismatch for '"
                                + methodName + "/" + types.size() + "'");
            return selected;
        }
    }

    /**
     * A caller cannot hand-construct a validated Plan to impersonate a compiler
     * checker result. A plan remains observational metadata, not a capability.
     */
    public static final class Plan {
        private final String declaration;
        private final Ast.Visibility visibility;
        private final ScalarState state;
        private final List<CheckedAdd> methods;
        private final TypedRefContract referenceType;

        private Plan(String declaration, Ast.Visibility visibility,
                     ScalarState state, List<CheckedAdd> methods,
                     TypedRefContract referenceType) {
            this.declaration = Objects.requireNonNull(declaration, "declaration");
            this.visibility = Objects.requireNonNull(visibility, "visibility");
            this.state = Objects.requireNonNull(state, "state");
            this.methods = List.copyOf(methods);
            this.referenceType = Objects.requireNonNull(referenceType, "referenceType");
        }

        public String declaration() { return declaration; }
        public Ast.Visibility visibility() { return visibility; }
        public ScalarState state() { return state; }
        public List<CheckedAdd> methods() { return methods; }
        public TypedRefContract referenceType() { return referenceType; }
    }

    /** Build an immutable type/ownership plan without changing execution. */
    public static Plan analyze(Ast.VirtualActorDecl actor) {
        Objects.requireNonNull(actor, "virtual actor declaration");
        requireIdentifier(actor.name(), "actor name");
        if (actor.stateFields().size() != 1)
            throw unsupported("v1 requires exactly one actor-owned scalar state field");
        Ast.ProtectedStateField state = actor.stateFields().getFirst();
        requireIdentifier(state.name(), "state field");
        requireSimpleI64(state.type(), "state field " + state.name());
        if (!(state.initializer() instanceof Ast.LiteralExpr literal)
                || !(literal.value() instanceof Long initial)) {
            throw unsupported("state initializer must be a checked constant i64 literal");
        }
        ScalarState scalar = new ScalarState(state.name(), state.type(), initial);
        if (actor.methods().isEmpty() || actor.methods().size() > 64)
            throw unsupported("v1 requires 1..64 fully checked methods");
        List<CheckedAdd> methods = new ArrayList<>(actor.methods().size());
        Set<String> slots = new HashSet<>();
        for (Ast.ProtectedMethod method : actor.methods()) {
            requireIdentifier(method.name(), "virtual method");
            if (Set.of("handle_state", "receive", "on_panic", "on_throw",
                    "on_error", "on_cancel", "stop", "new").contains(method.name())) {
                throw unsupported("reserved supervisor/actor method cannot be user-defined");
            }
            if (method.parameters().size() != 1)
                throw unsupported("v1 requires one value parameter per protected method");
            Ast.Param parameter = method.parameters().getFirst();
            requireIdentifier(parameter.name(), "method parameter");
            if (parameter.name().equals("s") || parameter.name().equals(state.name()))
                throw unsupported("parameter shadows the state-loan binding");
            if (parameter.mutable() || parameter.structural())
                throw unsupported("mutable/structural caller parameter forbidden");
            requireSimpleI64(parameter.type(), "method parameter");
            if (!isSimple(method.returnType(), "void"))
                throw unsupported("protected checked-add method must return void");
            if (!slots.add(method.name() + "/1"))
                throw unsupported("duplicate method name and arity");
            checkBody(method, state.name(), parameter.name());
            methods.add(new CheckedAdd(method.name(), parameter.name(), state.name(),
                    parameter.type(), method.returnType(), 1));
        }
        List<CheckedAdd> immutable = List.copyOf(methods);
        TypedRefContract reference = new TypedRefContract(
                actor.name(), "VirtualActorRef[" + actor.name() + "]", immutable);
        return new Plan(actor.name(), actor.visibility(), scalar, immutable, reference);
    }

    private static boolean isSimple(Ast.TypeRef type, String expected) {
        return type != null && type.name().equals(expected)
                && type.arguments().isEmpty() && !type.inferArguments();
    }

    private static void requireSimpleI64(Ast.TypeRef type, String label) {
        if (!isSimple(type, "int"))
            throw unsupported(label + " must have exact AttoLang 'int' (checked i64) type");
    }

    private static void requireIdentifier(String value, String label) {
        if (value == null || value.length() > 128
                || !value.matches("[A-Za-z_][A-Za-z0-9_]*")
                || value.startsWith("$")) {
            throw unsupported("invalid " + label);
        }
    }

    /**
     * Full lexical proof for v1:
     *   handle_state(critical-write |s| -> { s.<field> = s.<field> + <arg>; });
     * Every token, kind and position is checked. No extra statements,
     * nested functions, suspension, return, address-taking or aliases.
     */
    private static void checkBody(
            Ast.ProtectedMethod method, String field, String parameter) {
        List<String> expected = List.of(
                "handle_state", "(", "critical", "-", "write", "|", "s", "|",
                "->", "{", "s", ".", field, "=", "s", ".", field, "+",
                parameter, ";", "}", ")", ";");
        List<Token.Type> types = List.of(
                Token.Type.IDENT, Token.Type.LPAREN,
                Token.Type.IDENT, Token.Type.MINUS, Token.Type.IDENT,
                Token.Type.PIPE, Token.Type.IDENT, Token.Type.PIPE,
                Token.Type.ARROW, Token.Type.LBRACE, Token.Type.IDENT,
                Token.Type.DOT, Token.Type.IDENT, Token.Type.EQUAL,
                Token.Type.IDENT, Token.Type.DOT, Token.Type.IDENT,
                Token.Type.PLUS, Token.Type.IDENT, Token.Type.SEMICOLON,
                Token.Type.RBRACE, Token.Type.RPAREN, Token.Type.SEMICOLON);
        List<String> body = method.bodyTokens();
        List<String> provenance = method.bodyTokenKinds();
        if (body.size() != expected.size() || provenance.size() != expected.size())
            throw unsupported("unrecognized protected method body: no unchecked fallback");
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(body.get(i))
                    || !types.get(i).name().equals(provenance.get(i))) {
                throw unsupported("protected method token/provenance mismatch at index " + i);
            }
        }
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException("VIRTUAL_ACTOR_MIR_V1_REJECTED: " + reason);
    }
}
