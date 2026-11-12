package dev.oreslang.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Conservative lexical admission for out-of-line critical actor methods.
 * This does NOT implement type checking, borrowing, runtime actor context, or
 * executable state loans. TypeChecker must continue to fail closed.
 */
final class CriticalActorLoanAdmission {
    private static final Set<String> FORBIDDEN_KINDS = Set.of(
            "AWAIT", "SPAWN", "STOP", "RT", "SUPER", "UNSAFE",
            "YIELD", "DEFER", "TRY", "CATCH", "FINALLY", "THROW",
            "RAISE", "PANIC", "RECOVER", "WHILE", "FOR", "LOOP",
            "BREAK", "CONTINUE", "SELECT", "READCH", "WRITECH",
            "ASYNC", "SUSPENDS", "GENERATOR", "NEW", "GPU", "CPU",
            "QUANTUM", "FNC", "ROUTINE");

    private record Loan(int first, int open, int close, int writeCall,
                        int binding, String alias) {
        boolean encloses(int index) {
            return index > open && index < close;
        }
    }

    private CriticalActorLoanAdmission() { }

    static void check(List<String> lexemes, List<String> kinds, String receiver) {
        if (lexemes.size() != kinds.size()) {
            throw invalid("lexeme/kind provenance mismatch");
        }
        List<Loan> loans = new ArrayList<>();
        int lastLoanEnd = -1;
        for (int i = 0; i < lexemes.size(); i++) {
            if (!is(lexemes, i, "using")) continue;
            // This lexical gate admits only the explicit nonescaping loan form.
            if (i <= lastLoanEnd || !is(lexemes, i + 1, "state")
                    || !kind(kinds, i + 2, "DOT") || !is(lexemes, i + 3, "write")
                    || !kind(kinds, i + 4, "LPAREN")
                    || !is(lexemes, i + 5, "critical")
                    || !kind(kinds, i + 6, "PIPE")
                    || !kind(kinds, i + 7, "IDENT")
                    || !kind(kinds, i + 8, "PIPE")
                    || !kind(kinds, i + 9, "ARROW")
                    || !kind(kinds, i + 10, "LBRACE")) {
                throw invalid("only 'using state.write(critical |alias| -> { ... });' is admitted");
            }
            String alias = lexemes.get(i + 7);
            if (alias.equals(receiver) || alias.equals("state")
                    || alias.equals("critical") || alias.equals("using")) {
                throw invalid("critical loan alias shadows actor authority");
            }
            int depth = 0;
            int close = -1;
            for (int j = i + 10; j < kinds.size(); j++) {
                if (kind(kinds, j, "LBRACE")) depth++;
                if (kind(kinds, j, "RBRACE")) {
                    if (--depth == 0) {
                        close = j;
                        break;
                    }
                }
            }
            if (close < 0 || !kind(kinds, close + 1, "RPAREN")
                    || !kind(kinds, close + 2, "SEMICOLON")) {
                throw invalid("critical loan must close before method continuation");
            }
            loans.add(new Loan(i, i + 10, close, i + 4, i + 7, alias));
            lastLoanEnd = close + 2;
        }
        if (loans.isEmpty()) {
            throw invalid("critical method needs an explicit state loan");
        }
        for (int i = 0; i < lexemes.size(); i++) {
            String token = lexemes.get(i);
            String kind = kinds.get(i);
            final int pos = i;
            if (FORBIDDEN_KINDS.contains(kind)
                    || (kind.equals("IDENT") && (token.equals("external")
                            || token.equals("mtd")))) {
                throw invalid("effectful or callable declaration in critical body: " + token);
            }
            if (token.equals(receiver) && kind.equals("IDENT")) {
                throw invalid("critical body cannot access unlowered actor receiver");
            }
            if (token.equals("using") && loans.stream().noneMatch(l -> l.first == pos)) {
                throw invalid("unrecognized or nested using scope");
            }
            if (token.equals("state") && loans.stream().noneMatch(l -> l.first + 1 == pos)) {
                throw invalid("state authority used outside its checked loan initializer");
            }
            if (token.equals("critical") && loans.stream().noneMatch(l -> l.first + 5 == pos)) {
                throw invalid("nested or escaped critical authority");
            }
            if (kind.equals("RETURN") && loans.stream().anyMatch(l -> l.encloses(pos))) {
                throw invalid("return may not escape a live state loan");
            }
            if (kind.equals("ARROW") && loans.stream().noneMatch(l -> l.first + 9 == pos)) {
                throw invalid("unchecked nested closure inside critical body");
            }
            // Until call-effects are checked by MIR, no guest calls in critical.
            if (kind.equals("LPAREN") && i > 0 && kinds.get(i - 1).equals("IDENT")
                    && loans.stream().noneMatch(l -> l.writeCall == pos)) {
                throw invalid("unchecked call inside critical body");
            }
            for (Loan loan : loans) {
                if (!token.equals(loan.alias) || !kind.equals("IDENT")) continue;
                if (i == loan.binding) continue;
                if (!loan.encloses(i) || !kind(kinds, i + 1, "DOT")
                        || !kind(kinds, i + 2, "IDENT")
                        || kind(kinds, i + 3, "LPAREN")) {
                    throw invalid("state-loan alias escaped or used as a value");
                }
            }
        }
    }

    private static boolean is(List<String> values, int index, String expected) {
        return index >= 0 && index < values.size() && values.get(index).equals(expected);
    }

    private static boolean kind(List<String> values, int index, String expected) {
        return is(values, index, expected);
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("CRITICAL_ACTOR_LOAN_STATIC_REJECTED: " + reason);
    }
}
