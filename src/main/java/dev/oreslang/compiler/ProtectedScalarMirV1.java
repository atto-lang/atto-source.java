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
        ACQUIRE_READ, RELEASE_READ, ACQUIRE_WRITE, LOAD_STATE_I64,
        CHECKED_ADD_I64, STORE_STATE_I64, LOAD_RESULT_I64,
        RELEASE_WRITE, RETURN_I64, RETURN_VOID
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
            // The only permitted read plan owns a READ lease and returns
            // a copied scalar. Writer and actor plans retain exact ordering;
            // callers cannot construct a fake plan with write under READ.
            boolean approved = kind == Kind.STATE_CONTAINER
                    ? instructions.equals(STATE_READ) || instructions.equals(STATE_WRITE)
                    : instructions.equals(VIRTUAL_WRITE);
            if (!approved)
                throw new IllegalArgumentException(
                        "PROTECTED_MIR_UNSUPPORTED: invalid acquire/release or return sequence");
        }

        public ScalarStateContainer new_state_container() {
            requireKind(Kind.STATE_CONTAINER);
            return new ScalarStateContainer(initialValue, 4);
        }

        public boolean readOnly() {
            return kind == Kind.STATE_CONTAINER && instructions.equals(STATE_READ);
        }

        public long invoke(ScalarStateContainer state, long argument) {
            requireKind(Kind.STATE_CONTAINER);
            Objects.requireNonNull(state, "state");
            // A read plan can only invoke the VM's non-mutating tryRead()
            // primitive. No returned lease, state reference or closure crosses
            // this boundary. Both primitives release their leases on exit.
            if (readOnly()) {
                return state.tryRead().orElseThrow(
                        () -> new IllegalStateException("protected state reader unavailable"));
            }
            // The write plan retains atomic checked-add and release on trap.
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
                        return message -> state.tryAddChecked(message).orElseThrow(
                                () -> new IllegalStateException("virtual state writer unavailable"));
                    }, supervisorControl);
        }

        private void requireKind(Kind required) {
            if (kind != required)
                throw new IllegalStateException("wrong protected declaration kind");
        }
    }

    private static final List<Instruction> STATE_READ = List.of(
            Instruction.ACQUIRE_READ, Instruction.LOAD_STATE_I64,
            Instruction.RELEASE_READ, Instruction.RETURN_I64);
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
        boolean readOnly = matchBody(method.bodyTokens(), kind, field.name(), arg.name());
        return new Plan(kind, name, field.name(), method.name(), arg.name(), initial,
                kind == Kind.VIRTUAL_ACTOR ? VIRTUAL_WRITE
                        : readOnly ? STATE_READ : STATE_WRITE);
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
        int index;
        Cursor(List<String> tokens) { this.tokens = List.copyOf(tokens); }
        boolean at(String expected) {
            return index < tokens.size() && tokens.get(index).equals(expected);
        }
        void need(String expected) {
            if (!at(expected))
                throw invalid("expected '" + expected + "' at protected method token " + index);
            index++;
        }
        String ident() {
            if (index >= tokens.size()) throw invalid("missing protected state alias");
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

    /**
     * Turn the parser-owned protected-method token sequence into one of two
     * closed, typed scalar effects. The region alias never becomes a guest
     * expression value: READ can project only the declared Copy i64 field,
     * while WRITE can perform only a checked scalar addition. All other
     * expressions fail closed without executing arbitrary guest callbacks.
     *
     * This is a deliberately limited AST->MIR admission, NOT a general
     * protected-state borrow checker or method-effect inference engine.
     */
    private static boolean matchBody(
            List<String> tokens, Kind kind, String field, String parameter) {
        if (tokens.size() > 96)
            throw invalid("protected-method token budget exceeded");
        Cursor c = new Cursor(tokens);
        if (kind == Kind.STATE_CONTAINER) c.need("return");
        c.need("handle_state");
        c.need("(");
        c.need("critical");
        c.need("-");
        String effect = c.ident();
        if (!effect.equals("read") && !effect.equals("write"))
            throw invalid("only checked scalar critical-read/write are admitted");
        boolean readOnly = effect.equals("read");
        if (kind != Kind.STATE_CONTAINER && readOnly)
            throw invalid("virtual actor read effects require separate actor response lowering");
        c.need("|");
        String alias = c.ident();
        if (alias.equals(parameter))
            throw invalid("guard region alias must not shadow the parameter");
        c.need("|");
        c.need("->");
        c.need("{");
        if (readOnly) {
            // The alias is an affine, lexical state capability, not a
            // returnable value. Only a scalar field projection can leave
            // the protected region; returning the alias is an escape.
            c.need(alias);
            if (c.at(";") || c.at("}") || c.at(")"))
                throw new IllegalArgumentException(
                        "E_STATE_BORROW_ESCAPE: protected region alias cannot be returned");
            c.need(".");
            c.need(field);
            if (c.at("="))
                throw new IllegalArgumentException(
                        "E_CRITICAL_READ_MUTATION: read lease cannot assign protected state");
            c.need(";");
        } else {
            c.need(alias); c.need("."); c.need(field);
            c.need("=");
            c.need(alias); c.need("."); c.need(field);
            c.need("+"); c.need(parameter); c.need(";");
            if (kind == Kind.STATE_CONTAINER) {
                c.need(alias);
                if (c.at(";") || c.at("}") || c.at(")"))
                    throw new IllegalArgumentException(
                            "E_STATE_BORROW_ESCAPE: protected region alias cannot be returned");
                c.need("."); c.need(field); c.need(";");
            }
        }
        c.need("}"); c.need(")"); c.need(";");
        c.done();
        return readOnly;
    }
}
