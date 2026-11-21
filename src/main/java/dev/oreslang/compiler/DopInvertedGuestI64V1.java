package dev.oreslang.compiler;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.InvertedColumnStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static dev.oreslang.parser.Token.Type.*;

/**
 * Restricted, opt-in AttoLang inverted two-i64 guest compiler and interpreter.
 *
 * Uses the production lexer and a closed, typed instruction vocabulary. This
 * is NOT general Parser/TypeChecker admission, Java AOT, or five-backend parity.
 * Unsupported source rejects before allocating a guest store. The normal
 * OresCompiler.parseAndTypeCheck admission remains fail-closed.
 */
public final class DopInvertedGuestI64V1 {
    private static final int MAX_SOURCE_CHARS = 262144;
    private static final int MAX_CAPACITY = 4096;
    private static final int MAX_OPERATIONS = 10000;

    private DopInvertedGuestI64V1() { }

    public static final class BoundsTrap extends RuntimeException {
        public BoundsTrap() { super("DOP_INVERTED_BOUNDS_TRAP: dense row outside current length"); }
    }

    public static final class CapacityTrap extends RuntimeException {
        public CapacityTrap() { super("DOP_INVERTED_CAPACITY_TRAP: row budget exhausted"); }
    }

    private sealed interface Operation permits Push, EraseSwap, SetField, ReturnField { }
    private record Push(long first, long second) implements Operation { }
    private record EraseSwap(int index) implements Operation { }
    private record SetField(int index, int field, long value) implements Operation { }
    private record ReturnField(int index, int field) implements Operation { }

    /** Closed, immutable, checked MIR; cannot be constructed by a caller. */
    public static final class Plan {
        private final String schemaName;
        private final List<String> columns;
        private final int capacity;
        private final List<Operation> operations;

        private Plan(String schemaName, List<String> columns, int capacity,
                     List<Operation> operations) {
            this.schemaName = schemaName;
            this.columns = List.copyOf(columns);
            this.capacity = capacity;
            this.operations = List.copyOf(operations);
        }

        public String schemaName() { return schemaName; }
        public List<String> columns() { return columns; }
        public int capacity() { return capacity; }
        public int instructionCount() { return operations.size(); }

        /** Runs checked guest instructions on an owner-local physical SoA store. */
        public long execute() {
            try (InvertedColumnStore store = new InvertedColumnStore(
                    InvertedColumnStore.Kind.I64, InvertedColumnStore.Kind.I64)) {
                store.reserve(capacity);
                for (Operation op : operations) {
                    if (op instanceof Push push) {
                        if (store.size() >= capacity) throw new CapacityTrap();
                        store.push(push.first(), push.second());
                    } else if (op instanceof EraseSwap erase) {
                        store.eraseSwap(resolveDense(store, erase.index()));
                    } else if (op instanceof SetField set) {
                        store.set(resolveDense(store, set.index()), set.field(), set.value());
                    } else if (op instanceof ReturnField read) {
                        return (Long) store.get(resolveDense(store, read.index()), read.field());
                    } else {
                        throw new IllegalStateException("unverified inverted instruction");
                    }
                }
                throw new IllegalStateException("missing verified return instruction");
            }
        }

        private static InvertedColumnStore.Handle resolveDense(InvertedColumnStore store, int index) {
            try {
                return store.handleAt(index);
            } catch (IllegalArgumentException exception) {
                throw new BoundsTrap();
            }
        }
    }

    public static Plan compile(String source) {
        Objects.requireNonNull(source, "AttoLang guest source");
        if (source.length() == 0 || source.length() > MAX_SOURCE_CHARS) {
            throw new IllegalArgumentException("DOP_INVERTED_UNSUPPORTED: source size");
        }
        return new CheckedParser(new Lexer(source).scan()).parse();
    }

    private static final class CheckedParser {
        private final List<Token> tokens;
        private int at;
        private String owner;
        private String schema;
        private String firstField;
        private String secondField;
        private final boolean[] mutable = new boolean[2];

        private CheckedParser(List<Token> tokens) { this.tokens = List.copyOf(tokens); }
        private Token peek() { return tokens.get(Math.min(at, tokens.size() - 1)); }
        private boolean check(Token.Type type) { return peek().type() == type; }
        private boolean match(Token.Type type) {
            if (!check(type)) return false;
            at++;
            return true;
        }
        private IllegalArgumentException reject(String why) {
            Token token = peek();
            return new IllegalArgumentException("DOP_INVERTED_UNSUPPORTED: " + why +
                    " at " + token.line() + ":" + token.column());
        }
        private Token expect(Token.Type type, String why) {
            if (!check(type)) throw reject(why);
            return tokens.get(at++);
        }
        private String ident(String why) {
            String value = expect(IDENT, why).lexeme();
            if (!value.matches("[A-Za-z_][A-Za-z_0-9]*")) throw reject("noncanonical identifier");
            return value;
        }
        private void word(String expected) {
            if (!ident("expected '" + expected + "'").equals(expected))
                throw reject("expected '" + expected + "'");
        }
        private int field() {
            String name = ident("expected known inverted column");
            if (name.equals(firstField)) return 0;
            if (name.equals(secondField)) return 1;
            throw reject("unknown inverted column '" + name + "'");
        }
        private long signedLiteral() {
            boolean negative = match(MINUS);
            String raw = expect(INT, "expected i64 literal").lexeme();
            if (!raw.matches("[0-9]+")) throw reject("nondecimal i64 literal");
            try {
                return Long.parseLong(negative ? "-" + raw : raw);
            } catch (NumberFormatException exc) {
                throw reject("i64 literal overflow");
            }
        }
        private int smallUnsigned(String what, int maximum) {
            long value = signedLiteral();
            if (value < 0 || value > maximum) throw reject(what + " out of bounds");
            return (int) value;
        }
        private void ownerReceiver() {
            if (!ident("expected local inverted owner").equals(owner))
                throw reject("foreign or nonlocal inverted owner");
            expect(DOT, "expected owner operation");
        }
        private Plan parse() {
            expect(DEFINE, "expected nominal inverted schema");
            word("inverted");
            expect(STRUCT, "expected inverted struct (not class)");
            schema = ident("expected schema name");
            expect(AS, "expected 'as' after inverted struct");
            mutable[0] = match(LET);
            firstField = ident("expected first column");
            expect(COLON, "expected scalar column type");
            word("int");
            expect(SEMICOLON, "expected first column terminator");
            mutable[1] = match(LET);
            secondField = ident("expected second column");
            if (firstField.equals(secondField)) throw reject("duplicate inverted column");
            expect(COLON, "expected scalar column type");
            word("int");
            expect(SEMICOLON, "expected second column terminator");
            expect(END, "only two i64 inverted columns supported");

            expect(FNC, "expected one non-suspending main function");
            word("main");
            expect(LPAREN, "expected zero-argument main");
            expect(RPAREN, "expected zero-argument main");
            expect(COLON, "expected main return type");
            word("int");
            expect(LBRACE, "expected main body");
            expect(LET, "expected local owner binding");
            owner = ident("expected local storage owner");
            expect(EQUAL, "expected local storage initializer");
            word("InvertedArray");
            expect(LT, "expected nominal row type");
            if (!ident("expected matching nominal row type").equals(schema))
                throw reject("foreign inverted schema");
            expect(GT, "expected closed row type");
            expect(DOT, "expected typed storage constructor");
            word("with_capacity");
            expect(LPAREN, "expected storage capacity");
            int capacity = smallUnsigned("capacity", MAX_CAPACITY);
            expect(RPAREN, "expected storage capacity terminator");
            expect(SEMICOLON, "expected owner declaration terminator");

            List<Operation> operations = new ArrayList<>();
            while (!check(RETURN)) {
                if (check(RBRACE) || check(EOF)) throw reject("main requires a read-return");
                if (operations.size() >= MAX_OPERATIONS) throw reject("instruction budget exceeded");
                ownerReceiver();
                String operation = ident("expected storage operation");
                expect(LPAREN, "expected operation arguments");
                switch (operation) {
                    case "push" -> {
                        long first = signedLiteral();
                        expect(COMMA, "expected second column value");
                        long second = signedLiteral();
                        operations.add(new Push(first, second));
                    }
                    case "erase_swap" ->
                            operations.add(new EraseSwap(smallUnsigned("dense index", MAX_CAPACITY)));
                    case "set" -> {
                        int index = smallUnsigned("dense index", MAX_CAPACITY);
                        expect(COMMA, "expected column selector");
                        int column = field();
                        if (!mutable[column]) throw reject("immutable inverted field");
                        expect(COMMA, "expected scalar value");
                        operations.add(new SetField(index, column, signedLiteral()));
                    }
                    default -> throw reject("unknown or unsupported guest operation");
                }
                expect(RPAREN, "expected operation closing parenthesis");
                expect(SEMICOLON, "expected operation terminator");
            }
            expect(RETURN, "expected return");
            ownerReceiver();
            word("get");
            expect(LPAREN, "expected row read arguments");
            int row = smallUnsigned("dense index", MAX_CAPACITY);
            expect(COMMA, "expected read column");
            int field = field();
            expect(RPAREN, "expected read closing parenthesis");
            expect(SEMICOLON, "expected return terminator");
            operations.add(new ReturnField(row, field));
            expect(RBRACE, "expected main body terminator");
            expect(EOF, "extra declarations or statements not allowed");
            return new Plan(schema, List.of(firstField, secondField), capacity, operations);
        }
    }
}
