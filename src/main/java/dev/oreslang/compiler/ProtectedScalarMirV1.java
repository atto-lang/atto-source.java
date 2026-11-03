package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LocalVirtualActorI64;
import dev.oreslang.runtime.ScalarStateContainer;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * First typed MIR admission for the single-i64 protected-state slice.
 *
 * Unlike ScalarDeclarationI64Compiler, this entry point reads declarations
 * from the production Parser AST. It verifies the exact effectful body before
 * emitting typed acquire/add/release instructions. General TypeChecker
 * admission remains blocked until region, alias, and lifetime proofs exist.
 */
public final class ProtectedScalarMirV1 {
    private ProtectedScalarMirV1() { }

    public enum Kind { STATE_CONTAINER, VIRTUAL_ACTOR }
    public enum Instruction {
        ACQUIRE_WRITE, LOAD_STATE_I64, CHECKED_ADD_I64, STORE_STATE_I64,
        LOAD_RESULT_I64, RELEASE_WRITE, RETURN_I64, RETURN_VOID
    }

    public record Plan(Kind kind, String declarationName, String stateField,
                       String methodName, String parameterName, long initialValue,
                       List<Instruction> instructions) {
        public Plan {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(declarationName);
            Objects.requireNonNull(stateField);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(parameterName);
            instructions = List.copyOf(instructions);
            List<Instruction> expected = kind == Kind.STATE_CONTAINER
                    ? STATE_WRITE : VIRTUAL_WRITE;
            if (!instructions.equals(expected))
                throw new IllegalArgumentException(
                        "PROTECTED_MIR_UNSUPPORTED: invalid acquire/release or return sequence");
        }

        public ScalarStateContainer new_state_container() {
            requireKind(Kind.STATE_CONTAINER);
            return new ScalarStateContainer(initialValue, 4);
        }

        public long invoke(ScalarStateContainer state, long argument) {
            requireKind(Kind.STATE_CONTAINER);
            Objects.requireNonNull(state, "state");
            // A single VM primitive is the verified implementation of this
            // exact MIR sequence: it owns the permit for load/check/store,
            // releases it on exceptional exits, and checks overflow before
            // mutation. The returned i64 is read before permit release.
            return state.tryAddChecked(argument).orElseThrow(
                    () -> new IllegalStateException("protected state writer unavailable"));
        }

        public LocalVirtualActorI64 new_virtual_runtime(
                String domain, int maxIdentities, int mailboxCapacity,
                Consumer<LocalVirtualActorI64.Notice> supervisorControl) {
            requireKind(Kind.VIRTUAL_ACTOR);
            return new LocalVirtualActorI64(domain, declarationName,
                    maxIdentities, mailboxCapacity,
                    identity -> {
                        ScalarStateContainer state = new ScalarStateContainer(initialValue, 1);
                        return new LocalVirtualActorI64.Turn() {
                            @Override public void on_message(long message) {
                                state.tryAddChecked(message).orElseThrow(
                                        () -> new IllegalStateException("virtual state writer unavailable"));
                            }
                            @Override public void close() {
                                state.close();
                                if (!state.readyForReclamation())
                                    throw new IllegalStateException("virtual state lease not quiescent");
                            }
                        };
                    }, supervisorControl);
        }

        private void requireKind(Kind required) {
            if (kind != required)
                throw new IllegalStateException("wrong protected declaration kind");
        }
    }

    private static final List<Instruction> STATE_WRITE = List.of(
            Instruction.ACQUIRE_WRITE, Instruction.LOAD_STATE_I64,
            Instruction.CHECKED_ADD_I64, Instruction.STORE_STATE_I64,
            Instruction.LOAD_RESULT_I64, Instruction.RELEASE_WRITE,
            Instruction.RETURN_I64);
    private static final List<Instruction> VIRTUAL_WRITE = List.of(
            Instruction.ACQUIRE_WRITE, Instruction.LOAD_STATE_I64,
            Instruction.CHECKED_ADD_I64, Instruction.STORE_STATE_I64,
            Instruction.RELEASE_WRITE, Instruction.RETURN_VOID);

    private static IllegalArgumentException invalid(String issue) {
        return new IllegalArgumentException("PROTECTED_MIR_UNSUPPORTED: " + issue);
    }

    public static Plan lower(String source) {
        Objects.requireNonNull(source, "source");
        if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192)
            throw invalid("source budget exceeded");
        return lower(Parser.parse(source));
    }

    public static Plan lower(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        if (!program.imports().isEmpty() || program.modules().size() != 1)
            throw invalid("only one closed protected declaration with no imports is supported");
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!module.name().equals(Parser.ROOT_MODULE)
                || !module.annotations().isEmpty()
                || !module.contracts().isEmpty()
                || module.declarations().size() != 1)
            throw invalid("module linking and extra declarations require protected-state proofs");

        Ast.Decl declaration = module.declarations().getFirst();
        final Kind kind;
        final String name;
        final List<Ast.ProtectedStateField> fields;
        final List<Ast.ProtectedMethod> methods;
        if (declaration instanceof Ast.StateContainerDecl state) {
            kind = Kind.STATE_CONTAINER;
            name = state.name();
            fields = state.stateFields();
            methods = state.methods();
        } else if (declaration instanceof Ast.VirtualActorDecl actor) {
            kind = Kind.VIRTUAL_ACTOR;
            name = actor.name();
            fields = actor.stateFields();
            methods = actor.methods();
        } else {
            throw invalid("expected dedicated state container or virtual actor AST");
        }
        if (name.length() > 128 || fields.size() != 1 || methods.size() != 1)
            throw invalid("exactly one state field and one method required");
        var field = fields.getFirst();
        var method = methods.getFirst();
        if (!isInt(field.type()) || field.name().length() > 128)
            throw invalid("state field must be a single owned int");
        long initial = initializer(field.initializer());
        if (method.name().length() > 128 || method.parameters().size() != 1
                || !isInt(method.parameters().getFirst().type()))
            throw invalid("method must have exactly one int parameter");
        Ast.Param arg = method.parameters().getFirst();
        if (arg.structural() || arg.mutable() || arg.name().length() > 128)
            throw invalid("structural or mutable parameters not yet supported");
        if (!(kind == Kind.STATE_CONTAINER ? isInt(method.returnType())
                                          : isVoid(method.returnType())))
            throw invalid("method return type is incompatible with protected declaration");
        matchBody(method.bodyTokens(), method.bodyTokenKinds(), kind, field.name(), arg.name());
        return new Plan(kind, name, field.name(), method.name(), arg.name(), initial,
                kind == Kind.STATE_CONTAINER ? STATE_WRITE : VIRTUAL_WRITE);
    }

    private static boolean isInt(Ast.TypeRef type) {
        return type.name().equals("int") && type.arguments().isEmpty()
                && !type.inferArguments();
    }

    private static boolean isVoid(Ast.TypeRef type) {
        return type.name().equals("void") && type.arguments().isEmpty()
                && !type.inferArguments();
    }

    private static long initializer(Ast.Expr value) {
        if (value instanceof Ast.LiteralExpr literal && literal.value() instanceof Long n)
            return n;
        if (value instanceof Ast.UnaryExpr unary && unary.operator().equals("-")
                && unary.operand() instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long n) {
            return Math.negateExact(n);
        }
        throw invalid("state initializer must be an exact signed i64 literal");
    }

    private static final class Cursor {
        final List<String> tokens;
        final List<String> kinds;
        int index;
        Cursor(List<String> tokens, List<String> kinds) {
            this.tokens = List.copyOf(tokens);
            this.kinds = List.copyOf(kinds);
        }
        private static String expectedKind(String word) {
            return switch (word) {
                case "return" -> "RETURN";
                case "(" -> "LPAREN";
                case ")" -> "RPAREN";
                case "{" -> "LBRACE";
                case "}" -> "RBRACE";
                case "-" -> "MINUS";
                case "|" -> "PIPE";
                case "->" -> "ARROW";
                case "." -> "DOT";
                case "=" -> "EQUAL";
                case "+" -> "PLUS";
                case ";" -> "SEMICOLON";
                default -> "IDENT";
            };
        }
        void need(String expected) {
            if (index >= tokens.size() || !tokens.get(index).equals(expected)
                    || !kinds.get(index).equals(expectedKind(expected)))
                throw invalid("expected typed '" + expected + "' at protected method token " + index);
            index++;
        }
        void needIdent(String expected) {
            if (index >= tokens.size() || !tokens.get(index).equals(expected)
                    || !kinds.get(index).equals("IDENT"))
                throw invalid("expected identifier '" + expected + "' at protected method token " + index);
            index++;
        }
        String ident() {
            if (index >= tokens.size() || !kinds.get(index).equals("IDENT"))
                throw invalid("missing typed protected state alias");
            String name = tokens.get(index++);
            if (!name.matches("[A-Za-z_][A-Za-z_0-9]*"))
                throw invalid("invalid protected state alias");
            return name;
        }
        void done() {
            if (index != tokens.size())
                throw invalid("extra unverified protected-method tokens");
        }
    }

    private static void matchBody(List<String> tokens, List<String> kinds,
                                  Kind kind, String field, String parameter) {
        if (tokens.size() > 96 || tokens.size() != kinds.size())
            throw invalid("protected-method token provenance or budget exceeded");
        Cursor c = new Cursor(tokens, kinds);
        if (kind == Kind.STATE_CONTAINER) c.need("return");
        c.need("handle_state");
        c.need("(");
        c.need("critical");
        c.need("-");
        c.need("write");
        c.need("|");
        String alias = c.ident();
        if (alias.equals(parameter))
            throw invalid("protected state alias shadows method parameter");
        c.need("|");
        c.need("->");
        c.need("{");
        c.needIdent(alias); c.need("."); c.needIdent(field);
        c.need("=");
        c.needIdent(alias); c.need("."); c.needIdent(field);
        c.need("+"); c.needIdent(parameter); c.need(";");
        if (kind == Kind.STATE_CONTAINER) {
            c.needIdent(alias); c.need("."); c.needIdent(field); c.need(";");
        }
        c.need("}"); c.need(")"); c.need(";");
        c.done();
    }
}
