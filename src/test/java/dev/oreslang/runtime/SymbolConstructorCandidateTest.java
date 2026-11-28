package dev.oreslang.runtime;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
final class SymbolConstructorCandidateTest {
    @Test void guestFactoryRetainsNominalIdentityAndBounds() {
        assertEquals(OresSymbol.process("x y z zzz"), OresSymbol.constructGuest(List.of("x y z zzz")));
        assertNotSame(OresSymbol.constructGuest(List.of("ready")), OresSymbol.constructGuest(List.of("ready")));
        assertEquals(OresSymbol.process("café"), OresSymbol.constructGuest(List.of("cafe\u0301")));
        for (List<Object> args : List.<List<Object>>of(List.of(), List.of(1L), List.of("x","y"), List.of(""), List.of("a".repeat(257)), List.of(OresSymbol.process("ready"))))
            assertThrows(IllegalArgumentException.class, () -> OresSymbol.constructGuest(args));
    }
    @Test void checkedSourceConstructorAcceptsOnlyString() {
        for (String body : List.of("Symbol('x y z zzz')", "Symbol(\"x y z zzz\")", "Symbol(key)"))
            assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("pub fnc tag(String key): Symbol { return " + body + "; }")));
        for (String body : List.of("Symbol(1)", "Symbol()", "Symbol(\"x\",\"y\")", "Symbol(#ready)", "Symbol<int>(\"x\")"))
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("pub fnc tag(): Symbol { return " + body + "; }")));
    }

    @Test void linkedGuestConstructsRuntimeAndLiteralSymbols() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("atto-symbol-constructor-");
        java.nio.file.Path entry = dir.resolve("symbol.atto");
        try {
            java.nio.file.Files.writeString(entry, """
                fnc tag(String key): Symbol { return Symbol(key); }
                pub fnc main(): int {
                    val Symbol first = tag("x y z zzz");
                    val Symbol second = Symbol('x y z zzz');
                    if first eq #'x y z zzz' then
                        if second eq first then return 42; fi
                    fi
                    return 0;
                }
                """);
            var result = LinkedProgramRunner.runWithExitStatus(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), new java.io.ByteArrayOutputStream(),
                    new java.io.ByteArrayOutputStream());
            assertEquals(42, result.exitStatus());
        } finally {
            java.nio.file.Files.deleteIfExists(entry);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }
}
