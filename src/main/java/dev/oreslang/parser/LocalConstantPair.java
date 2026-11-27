package dev.oreslang.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.oreslang.parser.Token.Type.*;

/**
 * Deliberately bounded, nonescaping local const Pair<int> source admission.
 * The full nominal key is retained in compiler-local metadata. Only checked
 * scalar storage and explicit projections enter the existing typed AST.
 * No guest Pair allocation, general callable ABI or arbitrary scopes.
 */
final class LocalConstantPair {
    private static final String PREFIX = "__atto_pair_local_";
    private static final Set<String> NON_LOCAL_NAMES = Set.of(
            "Pair", "Entry", "Record", "Map", "Tuple", "Vector", "List", "Array",
            "int", "i64", "bool", "string", "float", "main", "Integer", "String");
    private final List<Token> tokens;
    private final List<Token> out = new ArrayList<>();
    private final Map<String, Pair> locals = new HashMap<>();
    private int at;

    private record Pair(String key, String slot) {}

    private LocalConstantPair(List<Token> tokens) { this.tokens = tokens; }

    static List<Token> lower(List<Token> tokens) {
        // No user may mint the private slot name, even in a non-Pair source.
        for (Token t : tokens) {
            if (t.type() == IDENT && t.lexeme().startsWith(PREFIX))
                throw error(t, "E_RESERVED", "compiler-owned Pair scalar slot");
        }
        var lower = new LocalConstantPair(tokens);
        if (!lower.containsStoredPair()) return tokens;
        return lower.program();
    }

    private Token peek(int offset) {
        return tokens.get(Math.min(at + offset, tokens.size() - 1));
    }
    private boolean type(int offset, Token.Type t) { return peek(offset).type() == t; }
    private boolean word(int offset, String word) { return peek(offset).lexeme().equals(word); }
    private Token next() { return tokens.get(at++); }
    private boolean take(Token.Type t) {
        if (!type(0,t)) return false;
        next(); return true;
    }
    private Token require(Token.Type t) {
        if (!type(0,t)) throw unsupported(peek(0));
        return next();
    }
    private Token requireWord(String word) {
        if (!word(0,word)) throw unsupported(peek(0));
        return next();
    }
    private static IllegalArgumentException error(Token at, String code, String reason) {
        return new IllegalArgumentException(code + " at " + at.line() + ":" + at.column() + ": " + reason);
    }
    private static IllegalArgumentException unsupported(Token at) {
        return error(at,"E_PAIR_STORAGE_UNSUPPORTED",
                "local const Pair<int> requires one flat int main and explicit read-only projections");
    }
    private boolean factoryAt(int offset) {
        return type(offset,IDENT) && word(offset,"Pair") && type(offset+1,DOT)
                && word(offset+2,"of") && type(offset+3,LPAREN);
    }
    private boolean storedFactoryAt(int offset) {
        if (!factoryAt(offset)) return false;
        int depth = 0;
        for (int j = at + offset + 3; j < tokens.size(); j++) {
            Token.Type t = tokens.get(j).type();
            if (t == LPAREN) depth++;
            else if (t == RPAREN && --depth == 0)
                return j + 1 < tokens.size() && tokens.get(j+1).type() == SEMICOLON;
            else if (t == EOF) return false;
        }
        return false;
    }
    private boolean containsStoredPair() {
        for (at = 0; at < tokens.size(); at++) {
            if (!type(0,CONST) || !type(1,IDENT)) continue;
            if (word(1,"Pair") && type(2,LT)) return true;
            if (type(2,COLON_EQUAL)) return true;
            if (type(2,EQUAL) && ((type(3,IDENT) || type(3,STRING)) && type(4,COLON_EQUAL)
                    || storedFactoryAt(3))) return true;
        }
        at = 0;
        return false;
    }
    private List<Token> program() {
        at = 0;
        // Exact complete header and EOF; no hidden scopes/parameters/imports.
        if (type(0,PUB)) out.add(next());
        out.add(require(FNC));
        out.add(requireWord("main"));
        out.add(require(LPAREN));
        out.add(require(RPAREN));
        out.add(require(COLON));
        out.add(requireWord("int"));
        out.add(require(LBRACE));
        while (type(0,CONST)) binding();
        out.add(require(RETURN));
        while (!type(0,SEMICOLON)) {
            Token t = peek(0);
            if (type(0,IDENT)) {
                Pair p = locals.get(t.lexeme());
                if (p == null) throw error(t,"E_UNBOUND","unknown local Pair operand");
                next();
                if (!take(DOT)) throw error(t,"E_PAIR_ESCAPE_UNSUPPORTED","Pair cannot escape or become a scalar");
                Token field = require(IDENT);
                if (field.lexeme().equals("value")) out.add(synthetic(IDENT,p.slot(),field));
                else if (field.lexeme().equals("key")) out.add(new Token(STRING,p.key(),field.line(),field.column(),'"'));
                else throw error(field,"E_PAIR_PROJECTION_UNSUPPORTED","only key/value are admitted");
            } else {
                // The existing parser/type and ownership checker remains
                // authoritative for conditions, arithmetic and result types.
                switch (t.type()) {
                    case INT, STRING, IF, THEN, ELSE, FI, TRUE, FALSE, EQ, NEQ,
                         PLUS, MINUS, STAR, SLASH, LPAREN, RPAREN, LT, LTE, GT, GTE,
                         QUESTION, COLON -> out.add(next());
                    default -> throw unsupported(t);
                }
            }
        }
        out.add(require(SEMICOLON));
        out.add(require(RBRACE));
        out.add(require(EOF));
        if (at != tokens.size()) throw unsupported(peek(0));
        return List.copyOf(out);
    }
    private String localName() {
        Token t = require(IDENT);
        if (NON_LOCAL_NAMES.contains(t.lexeme()) || t.lexeme().startsWith("__atto_"))
            throw error(t,"E_RESERVED","reserved Pair local name");
        return t.lexeme();
    }
    private Token synthetic(Token.Type type, String lexeme, Token at) {
        return new Token(type,lexeme,at.line(),at.column());
    }
    private void binding() {
        Token site = require(CONST);
        if (locals.size() >= 64) throw error(site,"E_SOURCE_LIMIT","local Pair count exceeds 64");
        boolean typed = word(0,"Pair");
        if (typed) {
            requireWord("Pair"); require(LT); requireWord("int"); require(GT);
        }
        String name = localName();
        if (locals.containsKey(name)) throw error(site,"E_DUPLICATE","duplicate const Pair");
        String key;
        List<Token> payload = new ArrayList<>();
        if (typed) {
            require(EQUAL);
            Token origin = require(IDENT);
            Pair prior = locals.get(origin.lexeme());
            if (prior == null) throw error(origin,"E_PAIR_TYPE","expected initialized Pair<int>");
            key = prior.key();
            payload.add(synthetic(IDENT,prior.slot(),origin));
        } else {
            boolean factory = false;
            if (take(COLON_EQUAL)) key = name;
            else {
                require(EQUAL);
                if (factoryAt(0)) {
                    factory = true;
                    requireWord("Pair"); require(DOT); requireWord("of"); require(LPAREN);
                    key = require(STRING).lexeme(); require(COMMA);
                } else {
                    Token literal = peek(0);
                    if (literal.type() != IDENT && literal.type() != STRING)
                        throw error(literal,"E_PAIR_KEY_TYPE","expected literal Pair key");
                    key = next().lexeme(); require(COLON_EQUAL);
                }
            }
            // No user calls, effects, runtime values or implicit conversion.
            if (type(0,MINUS)) payload.add(next());
            Token value = peek(0);
            if (!type(0,INT))
                throw error(value,type(0,IDENT)?"E_UNBOUND":"E_PAIR_TYPE","expected literal int payload");
            payload.add(next());
            if (factory) require(RPAREN);
        }
        require(SEMICOLON);
        // Retain each declaration as an ordinary typed scalar const. Invalid
        // signed integer bounds still reject even when its Pair is unused.
        String slot = PREFIX + locals.size();
        out.add(site);
        out.add(synthetic(IDENT,"int",site));
        out.add(synthetic(IDENT,slot,site));
        out.add(synthetic(EQUAL,"=",site));
        out.addAll(payload);
        out.add(synthetic(SEMICOLON,";",site));
        locals.put(name,new Pair(key,slot));
    }
}
