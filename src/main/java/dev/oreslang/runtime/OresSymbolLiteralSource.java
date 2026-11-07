package dev.oreslang.runtime;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiler-parse-boundary discovery of statically spelled Symbol literals.
 *
 * <p>Only lexical SYMBOL_LITERAL tokens are admitted. This deliberately does
 * not call Symbol.process on runtime names, scan raw comments/strings, or
 * permanently intern dynamic actor input. All names are canonicalized by
 * OresSymbol, and the bounded immutable result belongs to one checked code
 * unit. A value created by a later dynamic source mechanism must not be
 * assumed to be present in this catalog.</p>
 */
public final class OresSymbolLiteralSource {
    private OresSymbolLiteralSource() { }

    public static List<OresSymbol> collect(String source) {
        Set<OresSymbol> seen = new HashSet<>();
        List<OresSymbol> values = new ArrayList<>();
        long bytes = 0;
        for (Token token : new Lexer(source).scan()) {
            if (token.type() != Token.Type.SYMBOL_LITERAL) continue;
            OresSymbol literal = OresSymbol.process(token.lexeme());
            if (!seen.add(literal)) continue;
            if (values.size() >= OresSymbolLiteralImageStore.MAX_LITERALS) {
                throw new IllegalArgumentException("source Symbol literal-count quota exceeded");
            }
            bytes += literal.name().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > OresSymbolLiteralImageStore.MAX_PAYLOAD_UTF8_BYTES) {
                throw new IllegalArgumentException("source Symbol literal UTF-8 quota exceeded");
            }
            values.add(literal);
        }
        return List.copyOf(values);
    }
}
