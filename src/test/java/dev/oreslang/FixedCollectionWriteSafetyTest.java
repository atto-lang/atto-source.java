package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FixedCollectionWriteSafetyTest {
    @Test
    void heterogeneousFixedArrayWritesPreserveEachSlotType() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  let mut slots: FixedArray[int, string] = [1, "two"];
                  slots[0] = "corrupt";
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(int index): void {
                  let mut slots: FixedArray[int, string] = [1, "two"];
                  slots[index] = 9;
                  return;
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  let mut slots: FixedArray[int, string] = [1, "two"];
                  slots[0] = 7;
                  slots[1] = "safe";
                  return;
                }
                """)));
    }

    @Test
    void fixedArrayWritesRejectConstantOutOfRangeIndices() {
        for (String index : new String[]{"-1", "2"}) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    pub fnc main(): void {
                      let mut slots: FixedArray[int, int] = [1, 2];
                    """ + "slots[" + index + "] = 7;\nreturn;\n}")));
        }
    }

}
