package dev.oreslang.compiler;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.oreslang.parser.Token.Type.*;

/** Opt-in closed i64 slot profile; no guest pointer or JVM object authority. */
final class ReusableScalarSlotV1 {
    private static final Set<String> RESERVED = Set.of("main", "int", "RawPointer", "Pointer",
            "ref", "deref", "unref", "to", "empty", "borrow", "take", "copy", "share",
            "drop", "move", "pin", "freeze", "weak", "task", "sync", "susp");
    private final List<Token> tokens;
    private int pos;
    private ReusableScalarSlotV1(String source) {
        if (source == null || source.length() > 16384) throw invalid();
        tokens = new Lexer(source).scan();
        if (tokens.size() > 2048) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("E_POINTER_SLOT_PROFILE: invalid closed reusable scalar slot");
    }
    private Token token() { return tokens.get(pos); }
    private boolean take(Token.Type type, String spelling) {
        if (token().type() != type || !token().lexeme().equals(spelling)) return false;
        pos++; return true;
    }
    private void need(Token.Type type, String spelling) {
        if (!take(type, spelling)) throw invalid();
    }
    private String name() {
        String n = token().lexeme();
        if (token().type() != IDENT || !n.matches("[a-z_][a-z0-9_]{0,95}")
                || RESERVED.contains(n)) throw invalid();
        pos++; return n;
    }
    private long number() {
        if (token().type() != INT || !token().lexeme().matches("[0-9]+")) throw invalid();
        try { long n = Long.parseLong(token().lexeme()); pos++; return n; }
        catch (NumberFormatException ex) { throw invalid(); }
    }
    private void op(String name) {
        Token start = token();
        need(IDENT, name);
        if (token().line() != start.line() || token().column() != start.column() + name.length())
            throw invalid();
        need(BANG, "!");
    }
    private long term(Map<String,Long> values) {
        if (token().type() == INT) return number();
        Long value = values.get(name());
        if (value == null) throw invalid();
        return value;
    }
    static String lower(String source) {
        long answer = new ReusableScalarSlotV1(source).parse();
        // Only trusted numeric data is emitted. No source text, names, raw
        // addresses, pointer identity or object lifetime metadata escapes.
        // Long.MIN_VALUE is spelled using representable signed operands.
        String value = answer == Long.MIN_VALUE ? "(-9223372036854775807 - 1)" : Long.toString(answer);
        return "pub fnc main(): int { return " + value + "; }";
    }
    private long parse() {
        need(PUB,"pub"); need(FNC,"fnc"); need(IDENT,"main");
        need(LPAREN,"("); need(RPAREN,")"); need(COLON,":"); need(IDENT,"int");
        need(LBRACE,"{"); need(LET,"let");
        Map<String,Long> owners = new HashMap<>(), values = new HashMap<>();
        while (!take(MUT,"mut")) {
            if (owners.size() >= 16) throw invalid();
            String n = name();
            need(COLON,":"); need(IDENT,"int"); need(EQUAL,"=");
            long v = number(); need(SEMICOLON,";");
            if (owners.putIfAbsent(n,v) != null || values.putIfAbsent(n,v) != null) throw invalid();
            need(LET,"let");
        }
        need(IDENT,"RawPointer"); need(LT,"<"); need(IDENT,"int"); need(GT,">");
        String slot = name();
        if (values.containsKey(slot)) throw invalid();
        need(EQUAL,"="); need(IDENT,"RawPointer"); need(DOT,"."); need(IDENT,"empty");
        need(LT,"<"); need(IDENT,"int"); need(GT,">"); need(LPAREN,"("); need(RPAREN,")");
        need(SEMICOLON,";");
        String bound = null;
        int ops = 0;
        while (token().type() != RETURN) {
            if (++ops > 32) throw invalid();
            if (token().type() == IDENT && token().lexeme().equals("ref")) {
                op("ref"); need(IDENT,slot); need(IDENT,"to");
                String owner = name();
                if (bound != null || !owners.containsKey(owner)) throw invalid();
                bound = owner;
            } else if (take(VAL,"val")) {
                String copy = name();
                need(EQUAL,"="); op("deref"); need(IDENT,slot);
                if (bound == null || copy.equals(slot) ||
                        values.putIfAbsent(copy,owners.get(bound)) != null) throw invalid();
            } else if (token().type() == IDENT && token().lexeme().equals("unref")) {
                op("unref"); need(IDENT,slot);
                if (bound == null) throw invalid();
                bound = null;
            } else throw invalid();
            need(SEMICOLON,";");
        }
        if (bound != null) throw invalid();
        need(RETURN,"return");
        long answer = term(values);
        int terms = 1;
        try {
            while (token().type() == PLUS || token().type() == MINUS) {
                if (++terms > 32) throw invalid();
                boolean add = take(PLUS,"+");
                if (!add) need(MINUS,"-");
                long rhs = term(values);
                answer = add ? Math.addExact(answer,rhs) : Math.subtractExact(answer,rhs);
            }
        } catch (ArithmeticException ex) { throw invalid(); }
        need(SEMICOLON,";"); need(RBRACE,"}");
        if (token().type() != EOF) throw invalid();
        return answer;
    }
}
