package dev.oreslang.parser;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.oreslang.parser.Token.Type.*;

/**
 * Bounded, expression-only declarative macro expansion, before parsing and ownership checking.
 * No runtime objects, actors or capabilities are exposed to this pass.
 *
 * This intentionally excludes binding-introducing templates until identifier hygiene has a
 * proper symbol-identity representation. Captured input tokens retain their source positions;
 * template tokens point at the invocation.
 */
public final class MacroExpander {
    private static final int MAX_DEFINITIONS = 256;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_MACRO_GROUP_DEPTH = 128;
    private static final int MAX_OUTPUT_TOKENS = 50_000;
    // A token-only implementation cannot yet prove a generated statement, control
    // delimiter, declaration, or implicit receiver is hygienic. Keep templates
    // expression-only until typed AST expansion and symbol IDs are implemented.
    private static final Set<Token.Type> FORBIDDEN_TEMPLATE_TOKENS = Set.of(
            SEMICOLON, VAL, LET, CONST, FNC, ROUTINE, DEFINE, CLASS, MODULE,
            ACTOR, ISOACTOR, IMPORT, FROM, RETURN, THROW, RAISE, PANIC, RECOVER,
            SELF, SUPER, END, FI, DO, IF,
            ELSE, ELSEIF, THEN, SWITCH, MATCH, MATCHES, WHEN, CASE, DEFAULT,
            WHILE, FOR, OF, LOOP, BLOCK, BREAK, CONTINUE, YIELD, DEFER,
            TRY, CATCH, FINALLY, STRUCT, INTERFACE, TRAIT, IMPL, CONTRACT,
            NAMESPACE, TYPE, TYPES, PUB, PRIVATE, STATIC, ABSTRACT,
            SHARED, UNTRUSTED, GENERATOR, STOP, DONE, DEF,
            CONFORMS, EXTENDS, IMPLEMENTS, STRUCTURAL, MUT, NLEX, RT,
            // Annotation synthesis and async/actor authority are not valid
            // expression-template syntax without typed AST/effect lowering.
            AT, BACKTICK, AWAIT, SPAWN, SELECT, READCH, WRITECH,
            ASYNC, SUSPENDS, TRAP, NB, CB,
            EQUAL, FAT_ARROW, ARROW);


    public enum Mode { AOT, REBUILDABLE }

    public record Fingerprint(Mode mode, String sha256) {
        public Fingerprint {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(sha256);
        }
    }

    /** Immutable data to fence AOT declarations across compilation generations. */
    public record Snapshot(Map<String, Fingerprint> definitions) {
        public Snapshot {
            definitions = Map.copyOf(definitions);
        }
    }

    public record Result(List<Token> tokens, Snapshot snapshot) {
        public Result {
            tokens = List.copyOf(tokens);
            Objects.requireNonNull(snapshot);
        }
    }

    private record Parameter(String name, String kind) { }
    private record Definition(String name, Mode mode, List<Parameter> params,
                              List<Token> template, Fingerprint fingerprint) { }
    private record Parsed(Definition definition, int next) { }

    private MacroExpander() { }

    /**
     * Runtime OresLanguage/HotReload parsing must not invoke source macros or annotation code generators.
     * Such source must instead be expanded and guest-AOT compiled in a separate
     * trusted build, then admitted through a future verified artifact loader.
     * Lexical tokens prevent comments and string literals from spoofing macros.
     */
    public static void rejectRuntimeCodeGeneration(List<Token> tokens) {
        Objects.requireNonNull(tokens, "tokens");
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (named(tokens, i, "macro_rules") && at(tokens, i + 1, BANG)) {
                throw fail(tokens.get(i),
                        "runtime macro expansion forbidden: use an offline guest-AOT artifact");
            }
            if (at(tokens, i, AT) && named(tokens, i + 1, "FromJson")) {
                throw fail(tokens.get(i),
                        "runtime annotation code generation forbidden: @FromJson accessors require an offline guest-AOT build");
            }
        }
    }

    public static Result expand(List<Token> source) {
        return expand(source, null);
    }

    /**
     * AOT macros must remain byte-for-byte semantically identical to the previous generation.
     * Rebuildable macros may change only across separately compiled, fully validated
     * program generations. All macro expansion takes place before guest AOT code generation;
     * no macro implementation or token expansion is retained for runtime execution.
     */
    public static Result expand(List<Token> source, Snapshot previous) {
        Objects.requireNonNull(source);
        if (source.isEmpty() || source.getLast().type() != EOF) {
            throw new IllegalArgumentException("macro expansion requires a complete token stream");
        }
        Map<String, Definition> definitions = new LinkedHashMap<>();
        List<Token> remaining = new ArrayList<>();
        int end = source.size() - 1;
        // A premature EOF cannot hide trailing source from the AST checker.
        for (int i = 0; i < end; i++) {
            if (source.get(i).type() == EOF) {
                throw fail(source.get(i), "unexpected EOF before the end of the token stream");
            }
        }
        ArrayDeque<Token.Type> sourceGroups = new ArrayDeque<>();
        boolean seenSourceDeclaration = false;
        for (int i = 0; i < end;) {
            int start = i;
            Mode mode = Mode.AOT;
            if (at(source, i, AT) && named(source, i + 1, "expand")) {
                if (!at(source, i + 2, LPAREN) || !at(source, i + 3, IDENT)
                        || !at(source, i + 4, RPAREN)
                        || !named(source, i + 5, "macro_rules")
                        || !at(source, i + 6, BANG)) {
                    throw fail(source.get(i),
                            "@expand is reserved for an immediately following macro_rules! definition"
                            + " and requires @expand(aot) or @expand(rebuildable)");
                }
                String value = source.get(i + 3).lexeme();
                mode = switch (value) {
                    case "aot" -> Mode.AOT;
                    case "rebuildable" -> Mode.REBUILDABLE;
                    case "jit" -> throw fail(source.get(i + 3), "JIT macro expansion is forbidden: use @expand(rebuildable) for compile-time-only rebuilds");
                    default -> throw fail(source.get(i + 3), "unknown macro expansion mode: " + value);
                };
                i += 5;
            }
            if (named(source, i, "macro_rules") && at(source, i + 1, BANG)) {
                if (!sourceGroups.isEmpty() || seenSourceDeclaration) {
                    throw fail(source.get(i), "macro definitions must appear in the file prelude");
                }
                Parsed parsed = parseDefinition(source, i, mode);
                if (definitions.putIfAbsent(parsed.definition.name, parsed.definition) != null) {
                    throw fail(source.get(i), "duplicate macro definition: " + parsed.definition.name);
                }
                if (definitions.size() > MAX_DEFINITIONS) {
                    throw fail(source.get(i), "too many macro definitions");
                }
                i = parsed.next;
            } else {
                Token token = source.get(start);
                // This first version has no AST symbol identity for module/actor-local
                // macros: declarations belong before all runtime/type declarations.
                // Import selectors may contain "fnc", "class", or "actor".
                if (sourceGroups.isEmpty()
                        && (token.type() == DEFINE
                            || (List.of(FNC, ROUTINE, CLASS, ACTOR, ISOACTOR).contains(token.type())
                                && !(start > 0 && source.get(start - 1).type() == IMPORT)))) {
                    seenSourceDeclaration = true;
                }
                if (opening(token.type())) {
                    sourceGroups.push(closing(token.type()));
                } else if (token.type() == RPAREN || token.type() == RBRACE || token.type() == RBRACKET) {
                    if (!sourceGroups.isEmpty() && sourceGroups.peek() == token.type()) {
                        sourceGroups.pop();
                    }
                }
                remaining.add(token);
                i = start + 1;
            }
        }

        Map<String, Fingerprint> hashes = new LinkedHashMap<>();
        definitions.forEach((name, definition) -> hashes.put(name, definition.fingerprint));
        Snapshot snapshot = new Snapshot(hashes);
        if (previous != null) {
            for (var entry : previous.definitions().entrySet()) {
                Fingerprint current = hashes.get(entry.getKey());
                if (current != null && entry.getValue().mode() != current.mode()) {
                    throw new IllegalArgumentException("macro expansion mode changed across generations: "
                            + entry.getKey());
                }
                if (entry.getValue().mode() == Mode.AOT
                        && !entry.getValue().equals(current)) {
                    throw new IllegalArgumentException("AOT macro changed or disappeared across generations: "
                            + entry.getKey());
                }
            }
        }

        // Large ordinary source files must not hit a macro-only quota.
        int budget = Math.addExact(source.size(), MAX_OUTPUT_TOKENS);
        int[] emitted = {0};
        List<Token> output = new ArrayList<>(expandTokens(remaining, definitions, 0, emitted, budget));
        output.add(source.getLast());
        if (output.size() > budget) {
            throw new IllegalArgumentException("macro expansion exceeds token budget");
        }
        return new Result(output, snapshot);
    }

    private static Parsed parseDefinition(List<Token> tokens, int position, Mode mode) {
        int i = position + 2;
        Token name = need(tokens, i++, IDENT, "expected macro name");
        need(tokens, i++, LBRACE, "expected '{' after macro name");
        int openPattern = i++;
        need(tokens, openPattern, LPAREN, "expected macro parameter pattern");
        int closePattern = close(tokens, openPattern);
        List<Parameter> params = new ArrayList<>();
        for (int p = openPattern + 1; p < closePattern;) {
            need(tokens, p++, DOLLAR, "expected '$' for a macro parameter");
            Token parameter = need(tokens, p++, IDENT, "expected parameter name");
            need(tokens, p++, COLON, "expected ':' for a macro parameter");
            Token kind = need(tokens, p++, IDENT, "expected parameter kind");
            if (!List.of("expr", "ident", "tt").contains(kind.lexeme())) {
                throw fail(kind, "supported macro parameter kinds: expr, ident, tt");
            }
            if (params.stream().anyMatch(existing -> existing.name.equals(parameter.lexeme()))) {
                throw fail(parameter, "duplicate macro parameter");
            }
            params.add(new Parameter(parameter.lexeme(), kind.lexeme()));
            if (p < closePattern) {
                need(tokens, p++, COMMA, "expected ',' between macro parameters");
                if (p == closePattern) throw fail(tokens.get(p - 1), "trailing macro parameter comma");
            }
        }
        i = closePattern + 1;
        need(tokens, i++, FAT_ARROW, "expected '=>' after macro pattern");
        int openBody = i++;
        need(tokens, openBody, LBRACE, "expected '{' around macro expression");
        int closeBody = close(tokens, openBody);
        List<Token> template = List.copyOf(tokens.subList(openBody + 1, closeBody));
        if (template.isEmpty()) throw fail(tokens.get(openBody), "macro expression cannot be empty");
        validateTemplate(template, params);
        i = closeBody + 1;
        if (at(tokens, i, SEMICOLON)) i++;
        need(tokens, i++, RBRACE, "expected '}' after macro rule");
        if (at(tokens, i, SEMICOLON)) i++;
        String hash = fingerprint(mode, params, template);
        return new Parsed(new Definition(name.lexeme(), mode, List.copyOf(params),
                template, new Fingerprint(mode, hash)), i);
    }

    private static void validateTemplate(List<Token> template, List<Parameter> params) {
        if (template.isEmpty()) {
            // Empty replacement is never an expression, and an unused capture
            // must not trigger List.getFirst() / NoSuchElementException.
            throw new IllegalArgumentException("macro expression template must not be empty");
        }
        Map<String, Integer> useCounts = new LinkedHashMap<>();
        params.forEach(param -> useCounts.put(param.name, 0));
        for (int i = 0; i < template.size(); i++) {
            Token token = template.get(i);
            if (token.type() == DOLLAR) {
                Token next = need(template, ++i, IDENT, "expected captured parameter after '$'");
                if (!useCounts.containsKey(next.lexeme())) {
                    throw fail(next, "unknown macro capture: " + next.lexeme());
                }
                if (useCounts.merge(next.lexeme(), 1, Integer::sum) > 1) {
                    throw fail(next, "each capture may be evaluated only once in this first macro version");
                }
                continue;
            }
            if (token.type() == IDENT && !(i > 0 && template.get(i - 1).type() == DOT)
                    && !(i + 1 < template.size() && template.get(i + 1).type() == BANG)) {
                throw fail(token, "introduced identifiers require a future hygienic binding implementation");
            }
            // Deliberately expression-only. In particular, do not introduce lexical bindings,
            // declarations, actor definitions, or local scopes without hygienic symbol IDs.
            if (FORBIDDEN_TEMPLATE_TOKENS.contains(token.type())) {
                throw fail(token, "only expression macro bodies are supported");
            }
        }
        // Unused arguments would be erased before the parser, type checker and
        // ownership checker ever saw their tokens. Until captures are parsed
        // as typed AST fragments, require each capture exactly once: neither
        // duplicating a side effect nor hiding invalid code is permissible.
        for (Parameter parameter : params) {
            if (useCounts.get(parameter.name) == 0) {
                throw fail(template.getFirst(),
                        "unused macro capture $" + parameter.name
                                + " would discard unchecked source; use every capture exactly once");
            }
        }
    }

    private static List<Token> expandTokens(List<Token> input, Map<String, Definition> definitions,
                                             int depth, int[] emitted, int budget) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("macro expansion depth exceeded");
        }
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < input.size();) {
            Token token = input.get(i);
            if (token.type() == IDENT && at(input, i + 1, BANG)
                    && i + 2 < input.size() && opening(input.get(i + 2).type())) {
                Definition definition = definitions.get(token.lexeme());
                if (definition == null) {
                    throw fail(token, "unknown macro: " + token.lexeme());
                }
                int close = close(input, i + 2);
                List<List<Token>> args = splitArguments(input.subList(i + 3, close));
                if (args.size() != definition.params.size()) {
                    throw fail(token, "macro " + definition.name + " expected "
                            + definition.params.size() + " arguments, got " + args.size());
                }
                Map<String, List<Token>> captures = new LinkedHashMap<>();
                for (int p = 0; p < args.size(); p++) {
                    Parameter parameter = definition.params.get(p);
                    List<Token> arg = args.get(p);
                    if (arg.isEmpty()) throw fail(token, "empty macro argument");
                    if (parameter.kind.equals("ident")
                            && (arg.size() != 1 || arg.getFirst().type() != IDENT)) {
                        throw fail(token, "macro parameter $" + parameter.name + " requires an identifier");
                    }
                    if (parameter.kind.equals("expr")) {
                        // Expand nested macro invocations first, then enforce
                        // exactly-one-expression syntax using the ordinary
                        // parser. A token sequence is not an AST expression.
                        // Without this gate, return/assignment/semicolon
                        // fragments could splice unchecked syntax into callers.
                        arg = expandTokens(arg, definitions, depth + 1, emitted, budget);
                        Parser.validateMacroExpressionFragment(arg);
                    }
                    if (parameter.kind.equals("tt")) {
                        if (!(arg.size() == 1 || (opening(arg.getFirst().type())
                                && close(arg, 0) == arg.size() - 1))) {
                            throw fail(token, "macro parameter $" + parameter.name + " requires one token tree");
                        }
                        // Until fragments are parsed as typed AST expressions, a bare
                        // token tree cannot splice control-flow or annotation syntax
                        // into the call site in place of an ordinary expression.
                        if (arg.size() == 1 && !Set.of(IDENT, INT, FLOAT, IMAG, STRING,
                                TRUE, FALSE, NULL).contains(arg.getFirst().type())) {
                            throw fail(token, "unsafe bare token tree: expected an identifier, literal, or grouped expression");
                        }
                        for (Token captured : arg) {
                            if (FORBIDDEN_TEMPLATE_TOKENS.contains(captured.type())
                                    || captured.type() == AT || captured.type() == DOLLAR) {
                                throw fail(captured,
                                        "token-tree expression capture cannot introduce declarations, control flow, or annotations");
                            }
                        }
                    }
                    captures.put(parameter.name, arg);
                }
                List<Token> substitution = new ArrayList<>();
                for (int t = 0; t < definition.template.size(); t++) {
                    Token part = definition.template.get(t);
                    if (part.type() == DOLLAR) {
                        String captureName = definition.template.get(++t).lexeme();
                        Parameter parameter = definition.params.stream()
                                .filter(p -> p.name.equals(captureName))
                                .findFirst().orElseThrow();
                        List<Token> captured = captures.get(captureName);
                        // Expression captures must remain one operand after substitution.
                        if (parameter.kind.equals("expr")) {
                            substitution.add(new Token(LPAREN, "(", token.line(), token.column()));
                        }
                        substitution.addAll(captured);
                        if (parameter.kind.equals("expr")) {
                            substitution.add(new Token(RPAREN, ")", token.line(), token.column()));
                        }
                    } else {
                        substitution.add(new Token(part.type(), part.lexeme(), token.line(), token.column()));
                    }
                }
                result.addAll(expandTokens(substitution, definitions, depth + 1, emitted, budget));
                i = close + 1;
            } else {
                result.add(token);
                i++;
            }
            if (++emitted[0] > budget || result.size() > budget) {
                throw fail(token, "macro expansion token budget exceeded");
            }
        }
        return result;
    }

    private static List<List<Token>> splitArguments(List<Token> contents) {
        if (contents.isEmpty()) return List.of();
        List<List<Token>> args = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < contents.size(); i++) {
            if (opening(contents.get(i).type())) {
                i = close(contents, i);
            } else if (contents.get(i).type() == COMMA) {
                args.add(List.copyOf(contents.subList(start, i)));
                start = i + 1;
            }
        }
        args.add(List.copyOf(contents.subList(start, contents.size())));
        return args;
    }

    private static int close(List<Token> tokens, int start) {
        ArrayDeque<Token.Type> stack = new ArrayDeque<>();
        for (int i = start; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (opening(type)) {
                if (stack.size() >= MAX_MACRO_GROUP_DEPTH) {
                    throw fail(tokens.get(i), "macro delimiter nesting depth exceeded");
                }
                stack.push(closing(type));
            } else if (type == RPAREN || type == RBRACE || type == RBRACKET) {
                if (stack.isEmpty() || stack.pop() != type) {
                    throw fail(tokens.get(i), "mismatched macro delimiters");
                }
                if (stack.isEmpty()) return i;
            }
        }
        throw fail(tokens.get(start), "unterminated macro delimiter group");
    }

    private static boolean opening(Token.Type type) {
        return type == LPAREN || type == LBRACE || type == LBRACKET;
    }

    private static Token.Type closing(Token.Type type) {
        return switch (type) {
            case LPAREN -> RPAREN;
            case LBRACE -> RBRACE;
            case LBRACKET -> RBRACKET;
            default -> throw new IllegalArgumentException("not an opening delimiter: " + type);
        };
    }

    private static String fingerprint(Mode mode, List<Parameter> params, List<Token> template) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hashField(digest, "ores-macro-v3");
            hashField(digest, mode.name());
            hashField(digest, Integer.toString(params.size()));
            for (Parameter p : params) {
                hashField(digest, p.name);
                hashField(digest, p.kind);
            }
            hashField(digest, Integer.toString(template.size()));
            for (Token token : template) {
                hashField(digest, token.type().name());
                hashField(digest, token.lexeme());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Length-delimit hash fields; token lexemes may contain NUL and newline. */
    private static void hashField(MessageDigest digest, String field) {
        // Hash exact UTF-16 units, avoiding collisions from malformed surrogate replacement.
        int n = field.length();
        digest.update((byte) (n >>> 24));
        digest.update((byte) (n >>> 16));
        digest.update((byte) (n >>> 8));
        digest.update((byte) n);
        for (int i = 0; i < n; i++) {
            char codeUnit = field.charAt(i);
            digest.update((byte) (codeUnit >>> 8));
            digest.update((byte) codeUnit);
        }
    }

    private static Token need(List<Token> tokens, int index, Token.Type type, String error) {
        if (!at(tokens, index, type)) {
            throw fail(tokens.get(Math.min(index, tokens.size() - 1)), error);
        }
        return tokens.get(index);
    }

    private static boolean at(List<Token> tokens, int index, Token.Type type) {
        return index >= 0 && index < tokens.size() && tokens.get(index).type() == type;
    }

    private static boolean named(List<Token> tokens, int index, String name) {
        return at(tokens, index, IDENT) && tokens.get(index).lexeme().equals(name);
    }

    private static IllegalArgumentException fail(Token token, String message) {
        return new IllegalArgumentException("Oreslang macro error at " + token.line()
                + ":" + token.column() + ": " + message);
    }
}
