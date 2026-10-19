package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DynInheritedObjectSafetyTest {
    @Test void rejectsInheritedGenericMethod() {
        String source = "define interface Base as fnc convert<T>(T x) => int; end "
            + "define interface Child extends Base as end "
            + "fnc use(dyn Child x): void { return; }";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(e.getMessage().contains("generic method"), e::getMessage);
    }
    @Test void rejectsInheritedField() {
        String source = "define interface Base as val count: int; end "
            + "define interface Child extends Base as end "
            + "fnc use(dyn Child x): void { return; }";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(e.getMessage().contains("instance field"), e::getMessage);
    }

}
