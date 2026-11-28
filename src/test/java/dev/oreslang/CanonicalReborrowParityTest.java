package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CanonicalReborrowParityTest {
    @Test
    void sharedReborrowPreservesTheReferentTypeAcrossCallFrames() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define class ReborrowData as pub val Array<int> values; end
                fnc sum(borrow ReborrowData left, borrow ReborrowData right):int {
                  return left.values.get(0) + right.values.get(0);
                }
                fnc nested(borrow ReborrowData parent):int {
                  val Array<int> numbers = new Array<int>(); numbers.add(29);
                  val ReborrowData child = new ReborrowData(numbers);
                  return sum(rt borrow parent, rt borrow child);
                }
                pub fnc main():int {
                  val Array<int> numbers = new Array<int>(); numbers.add(13);
                  val ReborrowData parent = new ReborrowData(numbers);
                  return nested(rt borrow parent);
                }
                """));
    }

    @Test
    void sharedLoanCannotBecomeExclusiveThroughCanonicalReborrow() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc edit(borrow mut int n):void { return; }
                        fnc invalid(borrow int n):void { edit(rt mut borrow n); return; }
                        pub fnc main():int { return 0; }
                        """));
        assertTrue(failure.getMessage().contains("cannot mutably reborrow a shared borrow"));
    }

    @Test
    void reborrowCannotEscapeAsAnOwnedValue() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class ReborrowData as pub val int value = 1; end
                        fnc invalid(borrow ReborrowData n):ReborrowData { return rt borrow n; }
                        pub fnc main():int { return 0; }
                        """));
    }
}
