package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the AOT tree shaker does not erase the reachable branch by
 * coercing distinct i64 and binary64 constants through double precision.
 */
final class TreeShakerNumericEqualityTest {
    @Test
    void distinctValuesPastBinary64PrecisionNeverFoldAsEqual() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define module Wrong
                  pub fnc answer(): String { return "wrong"; }
                end

                define module Correct
                  pub fnc answer(): String { return "correct"; }
                end

                type Answer = typeof fnc() => String;
                pub routine main(): void {
                  val Answer selected = 9007199254740993 eq 9007199254740992.0
                      ? Wrong.answer : Correct.answer;
                  stdio.stdout.write(selected());
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        assertTrue(result.retained("Correct.answer"),
                "AOT must retain the reachable exact-comparison branch");
        assertTrue(result.removed("Wrong.answer"),
                "AOT must not preserve an erroneously true rounded comparison");
    }
}
