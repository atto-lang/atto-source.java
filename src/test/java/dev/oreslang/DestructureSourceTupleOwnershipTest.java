package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Destructuring a source-spelled Tuple[...] binds each element at ITS OWN
 * type: a scalar element stays Copy even when a sibling element (Bytes,
 * Channel, List) is move-only.
 */
final class DestructureSourceTupleOwnershipTest {

    @Test
    void scalarElementStaysCopyBesideMoveOnlySibling() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc f(Tuple[int, Bytes] t): int {
                  let mut List<int> xs = [];
                  [const n, const b] = t;
                  xs.add(n);
                  xs.add(n);
                  return n + xs.size + b.size();
                }
                pub fnc main(): void { stdio.println(f((1, Bytes.empty()))); return; }
                """));
    }

    @Test
    void moveOnlyElementIsStillMovedOnce() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc keep(Bytes b): int { return b.size(); }
                        pub fnc f(Tuple[int, Bytes] t): int {
                          [const n, const b] = t;
                          val int first = keep(b);
                          return first + keep(b) + n;
                        }
                        pub fnc main(): void { stdio.println(f((1, Bytes.empty()))); return; }
                        """));
        assertTrue(failure.getMessage().contains("use of moved value 'b'"), failure.getMessage());
    }
}
