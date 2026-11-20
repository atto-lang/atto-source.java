package dev.oreslang.compiler;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.LocalVirtualActorI64;
import dev.oreslang.runtime.ScalarStateContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Experimental first guest-source execution slice for exactly one checked-i64
 * state field and one add-only method. Uses the actual Oreslang lexer, and
 * rejects every unsupported body token before any runtime allocation.
 *
 * This is NOT yet wired into Parser.parse or the general HIR/MIR pipeline.
 * No dynamic expressions, heap aliases, async/await, generics, distributed
 * placement, persistence, or arbitrary actor messages can be compiled here.
 */
public final class ScalarDeclarationI64Compiler {
    private ScalarDeclarationI64Compiler() { }

    public enum Kind { STATE_CONTAINER, VIRTUAL_ACTOR }

    public record Declaration(Kind kind, String name, String field, String method,
                              String parameter, long initial) {
        public Declaration {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(name);
            Objects.requireNonNull(field);
            Objects.requireNonNull(method);
            Objects.requireNonNull(parameter);
        }

        public ScalarStateContainer new_state_container() {
            if (kind != Kind.STATE_CONTAINER)
                throw new IllegalStateException("not a state container declaration");
            return new ScalarStateContainer(initial, 4);
        }

        public long invoke(ScalarStateContainer container, long argument) {
            if (kind != Kind.STATE_CONTAINER)
                throw new IllegalStateException("only state containers allow direct invocation");
            return container.tryAddChecked(argument).orElseThrow(
                    () -> new IllegalStateException("state access unavailable"));
        }

        public LocalVirtualActorI64 new_virtual_runtime(String domain, int identities,
                                                         int mailboxCapacity,
                                                         Consumer<LocalVirtualActorI64.Notice> control) {
            if (kind != Kind.VIRTUAL_ACTOR)
                throw new IllegalStateException("not a virtual actor declaration");
            return new LocalVirtualActorI64(domain, name, identities, mailboxCapacity,
                    ignored -> {
                        var state = new ScalarStateContainer(initial, 1);
                        return new LocalVirtualActorI64.Turn() {
                            @Override public void on_message(long message) {
                                state.tryAddChecked(message).orElseThrow(
                                        () -> new IllegalStateException("actor state access unavailable"));
                            }
                            @Override public void close() {
                                state.close();
                                if (!state.readyForReclamation())
                                    throw new IllegalStateException("actor state lease not quiescent");
                            }
                        };
                    }, control);
        }
    }

    private static final class Reader {
        final List<Token> tokens;
        int cursor;
        Reader(List<Token> tokens) { this.tokens = tokens; }
        Token current() { return tokens.get(cursor); }
        boolean accept(Token.Type type) {
            if (current().type() != type) return false;
            cursor++;
            return true;
        }
        Token need(Token.Type type) {
            if (current().type() != type) throw invalid("expected " + type);
            return tokens.get(cursor++);
        }
        Token word(String spelling) {
            Token token = current();
            if (token.type() != Token.Type.IDENT || !token.lexeme().equals(spelling))
                throw invalid("expected contextual keyword '" + spelling + "'");
            cursor++;
            return token;
        }
        String ident() {
            Token token = need(Token.Type.IDENT);
            if (token.lexeme().length() > 128)
                throw invalid("identifier exceeds 128 code units");
            return token.lexeme();
        }
        IllegalArgumentException invalid(String why) {
            Token t = current();
            return new IllegalArgumentException(
                    "SCALAR_DECLARATION_UNSUPPORTED at " + t.line() + ":" + t.column() + ": " + why);
        }
        void same(String expected, String actual) {
            if (!expected.equals(actual)) throw invalid(
                    "state field or parameter mismatch; only a single owned i64 is supported");
        }
    }

    public static Declaration compile(String source) {
        Objects.requireNonNull(source, "source");
        if (source.length() > 8192 || source.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new IllegalArgumentException("SCALAR_DECLARATION_UNSUPPORTED: source budget exceeded");
        List<Token> tokens = new Lexer(source).scan();
        if (tokens.size() > 256)
            throw new IllegalArgumentException("SCALAR_DECLARATION_UNSUPPORTED: token budget exceeded");
        Reader p = new Reader(tokens);

        p.need(Token.Type.DEFINE);
        p.accept(Token.Type.PUB);
        Kind kind;
        if (p.current().lexeme().equals("state")) {
            p.word("state");
            p.word("container");
            kind = Kind.STATE_CONTAINER;
        } else if (p.current().lexeme().equals("virtual")) {
            p.word("virtual");
            p.need(Token.Type.ACTOR);
            kind = Kind.VIRTUAL_ACTOR;
        } else throw p.invalid("expected state container or virtual actor");

        String name = p.ident();
        p.need(Token.Type.AS);
        p.word("state");
        p.need(Token.Type.LBRACE);
        p.need(Token.Type.LET);
        p.word("int");
        String field = p.ident();
        p.need(Token.Type.COLON);
        boolean negative = p.accept(Token.Type.MINUS);
        Token number = p.need(Token.Type.INT);
        long initial;
        try {
            String raw = number.lexeme().replace("_", "");
            initial = Long.parseLong((negative ? "-" : "") + raw);
        } catch (NumberFormatException ex) {
            throw p.invalid("checked i64 initializer required");
        }
        p.need(Token.Type.SEMICOLON);
        p.need(Token.Type.RBRACE);

        String method = p.ident();
        p.need(Token.Type.LPAREN);
        p.word("int");
        String parameter = p.ident();
        p.need(Token.Type.RPAREN);
        p.need(Token.Type.ARROW);
        p.need(kind == Kind.STATE_CONTAINER ? Token.Type.IDENT : Token.Type.VOID);
        if (kind == Kind.STATE_CONTAINER)
            p.same("int", tokens.get(p.cursor - 1).lexeme());
        p.need(Token.Type.DOUBLE_COLON);
        if (kind == Kind.STATE_CONTAINER) p.need(Token.Type.RETURN);
        p.word("handle_state");
        p.need(Token.Type.LPAREN);
        p.word("critical");
        p.need(Token.Type.MINUS);
        p.word("write");
        p.need(Token.Type.PIPE);
        String alias = p.ident();
        p.need(Token.Type.PIPE);
        p.need(Token.Type.ARROW);
        p.need(Token.Type.LBRACE);
        p.same(alias, p.ident());
        p.need(Token.Type.DOT);
        p.same(field, p.ident());
        p.need(Token.Type.EQUAL);
        p.same(alias, p.ident());
        p.need(Token.Type.DOT);
        p.same(field, p.ident());
        p.need(Token.Type.PLUS);
        p.same(parameter, p.ident());
        p.need(Token.Type.SEMICOLON);
        if (kind == Kind.STATE_CONTAINER) {
            p.same(alias, p.ident());
            p.need(Token.Type.DOT);
            p.same(field, p.ident());
            p.need(Token.Type.SEMICOLON);
        }
        p.need(Token.Type.RBRACE);
        p.need(Token.Type.RPAREN);
        p.need(Token.Type.SEMICOLON);
        p.need(Token.Type.END);
        p.need(Token.Type.END);
        p.need(Token.Type.EOF);
        return new Declaration(kind, name, field, method, parameter, initial);
    }
}
