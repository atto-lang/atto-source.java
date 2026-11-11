package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared five-backend canonical .atto fixtures, not a native guest AOT claim.
 * The JIT/JIT-disabled exact stdout witness is AotDispatchGuestV1Test.
 */
final class DispatchV1CorpusCompilerTest {
    private static String fixture(String name) throws Exception {
        Path source = Path.of("contracts", "dispatch-v1", name + ".atto");
        assertTrue(Files.isRegularFile(source), "canonical fixture missing: " + source);
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    @Test
    void directFunctionChecksAgainstCanonicalSource() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("direct_call_42")));
    }

    @Test
    void instanceMethodChecksAgainstCanonicalSource() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("instance_call_42")));
    }

    @Test
    void qualifiedStaticMethodChecksAgainstCanonicalSource() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("static_qualified_42")));
    }

    @Test
    void wrongArityIsRejectedByRealCheckedCompiler() throws Exception {
        String source = fixture("wrong_arity_reject");
        assertDoesNotThrow(() -> Parser.parse(source),
                "arity negative must reach semantic checking, not just a syntax rejection");
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "wrong source call arity must not produce a checked program");
        assertThrows(RuntimeException.class,
                () -> TypeChecker.check(Parser.parse(source)),
                "direct type-checker gate must also reject wrong arity");
    }
}
