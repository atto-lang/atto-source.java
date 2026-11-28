package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure real compiler type checking; does not evaluate or AOT-compile guests. */
final class ByteBitSequenceTypeCheckerTest {
    private static void accepts(String pattern, String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  val data = Bytes.from_list([165, 42]);
                  match data over
                    on %s -> { %s }
                    on _ -> {}
                  end
                  return;
                }
                """.formatted(pattern, body))));
    }

    @Test
    void exactByteAndBitPatternsAreAdmittedOnBuiltinBytes() {
        accepts("bytes[165, 42]", "");
        accepts("bits[1, 0, 1, 0, 0, 1, 0, 1 | _]", "");
        accepts("bytes[]", "");
        accepts("bits[]", "");
    }

    @Test
    void byteScalarBindingsAreIntegers() {
        accepts("bytes[first, _ | _]", "val i: int = first;");
    }

    @Test
    void bitScalarBindingsAreBooleans() {
        accepts("bits[flag | _]", "val b: bool = flag;");
    }

    @Test
    void wrongSubjectTypeMustFailAtTypeCheck() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                    pub routine main(): void {
                      match "GET" over
                        on bytes[71, 69, 84] -> {}
                        on _ -> {}
                      end
                      return;
                    }
                    """)));
        assertTrue(err.getMessage().contains("Bytes"), err::getMessage);
    }

    @Test
    void namedByteTailCannotEscapeWithoutCleanupProof() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                    pub routine main(): void {
                      val data = Bytes.from_list([71, 69, 84, 42]);
                      match data over
                        on bytes[71, 69, 84 | rest] -> {}
                        on _ -> {}
                      end
                      return;
                    }
                    """)));
        assertTrue(err.getMessage().contains("ByteView"), err::getMessage);
    }

    @Test
    void namedNonalignedBitTailCannotEscapeWithoutBitViewLoanProof() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                    pub routine main(): void {
                      val data = Bytes.from_list([165]);
                      match data over
                        on bits[1, 0, 1 | tail] -> {}
                        on _ -> {}
                      end
                      return;
                    }
                    """)));
        assertTrue(err.getMessage().contains("BitView"), err::getMessage);
    }

    @Test
    void invalidBindingsDoNotAcquireAnImplicitCopyOfBytes() {
        accepts("bytes[_, _ | _]", "");
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                    pub routine main(): void {
                      val data = Bytes.from_list([1, 2, 3]);
                      match data over
                        on bytes[1 | copied_tail] -> {}
                        on _ -> {}
                      end
                      return;
                    }
                    """)));
    }
}
