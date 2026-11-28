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
}
