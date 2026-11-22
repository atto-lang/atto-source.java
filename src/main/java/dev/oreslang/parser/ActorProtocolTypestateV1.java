package dev.oreslang.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, source-token-based protocol grammar and typed declaration IR.
 * This is a compiler preflight, NOT actor execution or a minting API for real
 * protocol capabilities. No codegen path may erase this gate.
 */
public final class ActorProtocolTypestateV1 {
    private ActorProtocolTypestateV1() { }

    public static final int MAX_PROTOCOLS = 16;
    public static final int MAX_STATES = 64;
    public static final int MAX_TRANSITIONS = 256;

    public enum Mode { UNARY, STREAM }

    public record Transition(String from, String message, String parameterType,
                             String to, Mode mode, String responseType) { }

    public record Protocol(String name, String initial, List<String> states,
                           List<String> terminalStates, List<Transition> transitions) {
        public Protocol {
            states = List.copyOf(states);
            terminalStates = List.copyOf(terminalStates);
            transitions = List.copyOf(transitions);
        }
    }

    public record ActorBinding(String actor, String protocol) { }

    /** A typed, purely static send obligation. It conveys NO runtime authority. */
    public record CheckedSend(Mode mode, String responseType,
                              List<String> nextStates, boolean consumesAuthority) {
        public CheckedSend { nextStates = List.copyOf(nextStates); }
    }

    public record Catalog(List<Protocol> protocols, List<ActorBinding> actorBindings) {
        public Catalog {
            protocols = List.copyOf(protocols);
            actorBindings = List.copyOf(actorBindings);
        }

        public CheckedSend checkSend(String protocolName, List<String> possibleStates,
                                     String message) {
            Protocol protocol = protocols.stream().filter(p -> p.name().equals(protocolName))
                    .findFirst().orElseThrow(() -> error("E_PROTOCOL_UNKNOWN", protocolName));
            if (possibleStates == null || possibleStates.isEmpty() || possibleStates.size() > MAX_STATES)
                throw error("E_PROTOCOL_UNPROVEN_STATE", "state set is empty or over budget");
            LinkedHashSet<String> next = new LinkedHashSet<>();
            Mode mode = null;
            String response = null;
            for (String state : possibleStates) {
                if (!protocol.states().contains(state))
                    throw error("E_PROTOCOL_UNPROVEN_STATE", "unknown state " + state);
                Transition selected = null;
                for (Transition candidate : protocol.transitions()) {
                    if (candidate.from().equals(state) && candidate.message().equals(message)) {
                        selected = candidate;
                        break;
                    }
                }
                if (selected == null)
                    throw error("E_PROTOCOL_ILLEGAL_MESSAGE_STATE", message + " in " + state);
                if (mode != null && mode != selected.mode())
                    throw error("E_PROTOCOL_RESPONSE_MODE", "mixed unary/stream response cardinality");
                if (response != null && !response.equals(selected.responseType()))
                    throw error("E_PROTOCOL_RESPONSE_TYPE", "mixed reply/item types across possible states");
                mode = selected.mode();
                response = selected.responseType();
                next.add(selected.to());
            }
            List<String> sorted = new ArrayList<>(next);
            Collections.sort(sorted);
            return new CheckedSend(mode, response, sorted, true);
        }
    }

    private static IllegalArgumentException error(String code, String why) {
        return new IllegalArgumentException(code + ": " + why);
    }

    private static boolean word(Token t, String value) {
        return t.type() == Token.Type.IDENT && t.lexeme().equals(value);
    }

    /** Detect contextual 'define protocol', without globally reserving protocol. */
    public static boolean containsProtocol(List<Token> tokens) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).type() == Token.Type.DEFINE && word(tokens.get(i + 1), "protocol"))
                return true;
        }
        return false;
    }

    public static Catalog parseSource(String source) {
        return parseTokens(new Lexer(source).scan());
    }

    public static Catalog parseTokens(List<Token> tokens) {
        if (tokens == null) throw error("E_PROTOCOL_SYNTAX", "missing tokens");
        List<Protocol> protocols = new ArrayList<>();
        Set<String> protocolNames = new LinkedHashSet<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).type() != Token.Type.DEFINE || !word(tokens.get(i + 1), "protocol"))
                continue;
            if (protocols.size() == MAX_PROTOCOLS)
                throw error("E_PROTOCOL_LIMIT", "too many protocol declarations");
            Cursor cursor = new Cursor(tokens, i + 2);
            Protocol parsed = parseProtocol(cursor);
            if (!protocolNames.add(parsed.name()))
                throw error("E_PROTOCOL_DUPLICATE", "duplicate protocol " + parsed.name());
            protocols.add(parsed);
            i = cursor.index - 1;
        }
        List<ActorBinding> bindings = new ArrayList<>();
        Set<String> actorNames = new LinkedHashSet<>();
        // Source-local bindings are discovered only for declarations that name
        // one of the statically parsed protocols. Other ordinary interfaces
        // remain owned by the existing class/interface type checker.
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).type() != Token.Type.DEFINE || tokens.get(i + 1).type() != Token.Type.ACTOR)
                continue;
            Token actor = tokens.get(i + 2);
            if (actor.type() != Token.Type.IDENT) continue;
            int limit = Math.min(tokens.size(), i + 66);
            for (int j = i + 3; j < limit && tokens.get(j).type() != Token.Type.AS; j++) {
                if (tokens.get(j).type() != Token.Type.IMPLEMENTS || j + 1 >= limit) continue;
                Token target = tokens.get(j + 1);
                if (target.type() != Token.Type.IDENT || !protocolNames.contains(target.lexeme())) continue;
                if (!actorNames.add(actor.lexeme()))
                    throw error("E_PROTOCOL_DUPLICATE", "duplicate actor binding " + actor.lexeme());
                bindings.add(new ActorBinding(actor.lexeme(), target.lexeme()));
            }
        }
        return new Catalog(protocols, bindings);
    }

    private static final class Cursor {
        final List<Token> tokens;
        int index;
        Cursor(List<Token> tokens, int index) { this.tokens = tokens; this.index = index; }
        Token peek() {
            if (index >= tokens.size())
                throw error("E_PROTOCOL_SYNTAX", "unexpected end of protocol");
            return tokens.get(index);
        }
        boolean take(Token.Type type) {
            if (peek().type() == type) { index++; return true; }
            return false;
        }
        boolean takeWord(String value) {
            if (word(peek(), value)) { index++; return true; }
            return false;
        }
        void require(Token.Type type, String context) {
            if (!take(type)) throw error("E_PROTOCOL_SYNTAX", "expected " + context + " at "
                    + peek().line() + ":" + peek().column());
        }
        void requireWord(String value) {
            if (!takeWord(value)) throw error("E_PROTOCOL_SYNTAX", "expected '" + value + "'");
        }
        String ident() {
            Token t = peek();
            if (t.type() != Token.Type.IDENT || !t.lexeme().matches("[A-Za-z_][A-Za-z_0-9]{0,79}"))
                throw error("E_PROTOCOL_SYNTAX", "expected bounded nominal identifier");
            index++;
            return t.lexeme();
        }
    }

    private static Protocol parseProtocol(Cursor c) {
        String name = c.ident();
        c.require(Token.Type.AS, "'as' after protocol name");
        c.requireWord("initial");
        String initial = c.ident();
        List<String> states = new ArrayList<>();
        List<String> terminal = new ArrayList<>();
        List<Transition> transitions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        while (!c.take(Token.Type.END)) {
            if (states.size() >= MAX_STATES)
                throw error("E_PROTOCOL_LIMIT", "state count exceeds " + MAX_STATES);
            if (c.takeWord("terminal")) {
                c.requireWord("state");
                String state = c.ident();
                if (!seen.add(state)) throw error("E_PROTOCOL_DUPLICATE", "duplicate state " + state);
                states.add(state);
                terminal.add(state);
                continue;
            }
            c.requireWord("state");
            String state = c.ident();
            if (!seen.add(state)) throw error("E_PROTOCOL_DUPLICATE", "duplicate state " + state);
            states.add(state);
            c.require(Token.Type.AS, "'as' after state");
            while (!c.take(Token.Type.END)) {
                c.requireWord("on");
                if (transitions.size() >= MAX_TRANSITIONS)
                    throw error("E_PROTOCOL_LIMIT", "transition count exceeds " + MAX_TRANSITIONS);
                String message = c.ident();
                String parameterType = null;
                if (c.take(Token.Type.LPAREN)) {
                    if (!c.take(Token.Type.RPAREN)) {
                        parameterType = c.ident();
                        c.require(Token.Type.RPAREN, "')' after payload type");
                    }
                }
                c.require(Token.Type.ARROW, "'->' after message");
                String successor = c.ident();
                Mode mode;
                if (c.takeWord("replies")) mode = Mode.UNARY;
                else if (c.takeWord("streams")) mode = Mode.STREAM;
                else throw error("E_PROTOCOL_RESPONSE_MODE", "expected replies or streams cardinality");
                String responseType = c.ident();
                transitions.add(new Transition(state, message, parameterType, successor, mode, responseType));
            }
        }
        if (states.isEmpty() || terminal.isEmpty() || transitions.isEmpty())
            throw error("E_PROTOCOL_INCOMPLETE", "protocol requires states, a terminal and transitions");
        if (!seen.contains(initial) || terminal.contains(initial))
            throw error("E_PROTOCOL_INITIAL", "initial state is unknown or terminal");
        Set<String> keys = new LinkedHashSet<>();
        for (Transition t : transitions) {
            if (!seen.contains(t.to()))
                throw error("E_PROTOCOL_UNKNOWN_STATE", "unknown successor " + t.to());
            if (!keys.add(t.from() + "\u0000" + t.message()))
                throw error("E_PROTOCOL_DUPLICATE", "duplicate state/message transition");
            if (terminal.contains(t.from()))
                throw error("E_PROTOCOL_TERMINAL", "terminal state has outgoing message");
        }
        Set<String> reachable = new LinkedHashSet<>();
        reachable.add(initial);
        boolean changed;
        do {
            changed = false;
            for (Transition t : transitions)
                if (reachable.contains(t.from())) changed |= reachable.add(t.to());
        } while (changed);
        if (reachable.size() != states.size())
            throw error("E_PROTOCOL_UNREACHABLE", "unreachable state");
        Set<String> canTerminate = new LinkedHashSet<>(terminal);
        do {
            changed = false;
            for (Transition t : transitions)
                if (canTerminate.contains(t.to())) changed |= canTerminate.add(t.from());
        } while (changed);
        if (canTerminate.size() != states.size())
            throw error("E_PROTOCOL_UNTERMINATED", "state has no path to terminal");
        return new Protocol(name, initial, states, terminal, transitions);
    }
}
