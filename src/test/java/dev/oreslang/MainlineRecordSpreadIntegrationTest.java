package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-feature gates for the product spread/mainline constructor-safety merge.
 * Keep constructor partial-self protection and record slot expansion independent:
 * labeled arguments must not permit an unfinished object to escape.
 */
final class MainlineRecordSpreadIntegrationTest {
    @Test
    void directRecordSpreadCoexistsWithSafeClassInitializer() {
        String source = """
                fnc encode(int a, int b): int { return a * 100 + b; }
                define class Safe as
                  pub final int seed: 19 + 4;
                end
                pub routine main(): void {
                  let Safe box = new Safe();
                  const args = (b: 23, a: 19);
                  stdio.stdout.write(encode(...args));
                  stdio.stdout.write(":");
                  stdio.stdout.write(box.seed);
                  return;
                }
                """;
        // Both gates must accept the same unit; neither may replace the other's
        // declaration, call-plan or constructor initialization environment.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void eagerClassInitializerCannotHideUnprovenRecordSpreadCall() {
        String source = """
                fnc encode(int a, int b): int { return a * 100 + b; }
                define class Unsafe as
                  final int value: encode(...(b: 23, a: 19));
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void constructorCannotPublishUninitializedSelfInsideNamedRecordSpread() {
        String source = """
                fnc publish(Unsafe owner): void { return; }
                define class Unsafe as
                  val int value;
                  constructor(int input) {
                    publish(...(owner: self));
                    self.value = input;
                  }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("partially initialized constructor self"),
                failure.getMessage());
    }
}
