package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Same five arithmetic expressions and expected signed i64 outputs as
 * platform-conformance/fixtures/proxy-lb-v1. Real Java/Graal guest execution
 * is checked separately from compilation of the exact zero-arg scalar source.
 *
 * Deliberately does not claim that a proxy, scheduler, DNS, or network source
 * can execute under Java, LLVM, Wasm, Cranelift, or .NET.
 */
@Timeout(30)
final class ProxyLbScalarGuestParityTest {

    private record Case(String expression, String expected) {}

    @Test
    void strictScalarSourceAndRealGraalGuestArithmetic() throws Exception {
        var cases = new Case[] {
            new Case("9*1-1*3", "6"),
            new Case("2*1-1*3", "-1"),
            new Case("3*1-1*3", "0"),
            new Case("1024*16-1024*16", "0"),
            new Case("1024*16-0*16", "16384")
        };
        for (var fixture : cases) {
            // Exact conformance fixture source spelling and LF delimiter.
            String original = "fnc main():int{return "
                    + fixture.expression() + ";}\n";
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(original)));
            // Execute the SAME arithmetic expression through guest stdio to
            // observe its full signed i64 result, not a truncated process code.
            String observable = "pub fnc main():void { stdio.stdout.write("
                    + fixture.expression() + "); return; }\n";
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(observable)));
            var source = Source.newBuilder(OresLanguage.ID, observable,
                    "proxy-lb-scalar-parity.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            var output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                context.eval(source);
            }
            assertEquals(fixture.expected(),
                    output.toString(StandardCharsets.UTF_8),
                    "signed i64 comparator guest mismatch: " + fixture.expression());
        }
    }
}
