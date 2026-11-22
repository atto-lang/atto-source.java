package dev.oreslang.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static dev.oreslang.parser.Token.Type.*;

/**
 * Compiler-only CPU/GPU execution-surface recognizer.
 *
 * <p>This intentionally does NOT relax Parser.parseProgram's fail-closed
 * admission or produce runnable AST nodes. It disambiguates the historical
 * anonymous-kernel spelling from the proposed eager-region spelling before
 * typed effects and backend lowering are implemented. No context-dependent
 * overload on the expected return type is allowed.</p>
 */
public final class ExecutionSurfaceV1 {
    private ExecutionSurfaceV1() { }

    public enum Form {
        EAGER_REGION, ANONYMOUS_GPU_KERNEL, NAMED_GPU_KERNEL, GPU_INTRINSIC
    }

    public enum Domain { CPU, GPU }

    /** Token indices are bounded to source; parent refers to a previous entry. */
    public record Entry(Domain domain, Form form, boolean nonblocking,
                        int startToken, int endToken, int parentIndex) {
        public Entry {
            Objects.requireNonNull(domain);
            Objects.requireNonNull(form);
            if (startToken < 0 || endToken < startToken || parentIndex < -1)
                throw new IllegalArgumentException("invalid execution-surface span");
        }
    }

    public static List<Entry> inspect(String source) {
        Objects.requireNonNull(source, "source");
        List<Token> tokens = new Lexer(source).scan();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token.Type kind = tokens.get(i).type();
            if (kind != CPU && kind != GPU) continue;
            Domain domain = kind == GPU ? Domain.GPU : Domain.CPU;
            boolean nb = i > 0 && tokens.get(i - 1).type() == NB;
            int beginning = nb ? i - 1 : i;
            int next = i + 1;
            if (next >= tokens.size()) throw syntax(tokens.get(i), "missing execution-domain body");

            Form form;
            int last;
            switch (tokens.get(next).type()) {
                case LBRACE -> {
                    form = Form.EAGER_REGION;
                    last = close(tokens, next, LBRACE, RBRACE);
                }
                case LPAREN -> {
                    if (domain != Domain.GPU || nb)
                        throw syntax(tokens.get(i), "nonblocking/CPU kernel callable not supported");
                    int endParams = close(tokens, next, LPAREN, RPAREN);
                    if (endParams + 1 >= tokens.size()
                            || tokens.get(endParams + 1).type() != LBRACE)
                        throw syntax(tokens.get(i), "anonymous gpu callable requires a body");
                    form = Form.ANONYMOUS_GPU_KERNEL;
                    last = close(tokens, endParams + 1, LBRACE, RBRACE);
                }
                case FNC, ROUTINE -> {
                    if (domain != Domain.GPU || nb)
                        throw syntax(tokens.get(i), "invalid execution-domain callable declaration");
                    form = Form.NAMED_GPU_KERNEL;
                    last = i + 1; // enclosing routine grammar still belongs to Parser
                }
                case DOT -> {
                    if (domain != Domain.GPU || nb || next + 1 >= tokens.size()
                            || tokens.get(next + 1).type() != IDENT)
                        throw syntax(tokens.get(i), "malformed gpu intrinsic");
                    form = Form.GPU_INTRINSIC;
                    last = next + 1;
                }
                default -> throw syntax(tokens.get(i),
                        "reserved cpu/gpu requires a typed region or GPU kernel");
            }

            int parent = -1;
            int bestWidth = Integer.MAX_VALUE;
            for (int j = 0; j < entries.size(); j++) {
                Entry e = entries.get(j);
                int span = e.endToken() - e.startToken();
                if (e.form() != Form.EAGER_REGION || e.startToken() >= beginning
                        || e.endToken() < last || span >= bestWidth) continue;
                parent = j;
                bestWidth = span;
            }
            entries.add(new Entry(domain, form, nb, beginning, last, parent));
        }
        return List.copyOf(entries);
    }

    private static int close(List<Token> tokens, int start,
                             Token.Type open, Token.Type closing) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            Token.Type token = tokens.get(i).type();
            if (token == open) depth++;
            if (token == closing && --depth == 0) return i;
        }
        throw syntax(tokens.get(start), "unclosed CPU/GPU execution surface");
    }

    private static IllegalArgumentException syntax(Token token, String reason) {
        return new IllegalArgumentException(reason + " at "
                + token.line() + ":" + token.column());
    }
}
