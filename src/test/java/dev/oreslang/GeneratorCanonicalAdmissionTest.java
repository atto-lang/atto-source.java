package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class GeneratorCanonicalAdmissionTest {
    @Test
    void actorKindModifiersCannotBypassBareGeneratorAdmission() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "shared gen* values(): Iterator<int> { yield 1; return; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "untrusted gen* values(): Iterator<int> { yield 1; return; }"));
    }

    @Test
    void bareGeneratorRequiresStarWhileLegacyGeneratorFncRemainsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub generator values(): Iterator<int> {
                  yield 1;
                  return;
                }
                """));

        OresCompiler.parseAndTypeCheck("""
                pub generator* values(): Iterator<int> {
                  yield 1;
                  return;
                }
                """);

        OresCompiler.parseAndTypeCheck("""
                pub generator fnc legacy(): int {
                  yield 1;
                  return;
                }
                """);
    }

    @Test
    void suspendGeneratorCannotExposeSyncIteratorProtocol() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                "pub suspend gen* values(): Iterator<int> { yield 1; return; }"));
    }

    @Test
    void suspendGeneratorActivationIsLazyAndAllowedFromSyncCaller() {
        OresCompiler.parseAndTypeCheck("""
                pub suspend gen* values(): AsyncIterator<int> {
                  rt cooperate;
                  yield 42;
                  return;
                }
                pub fnc make(): AsyncIterator<int> {
                  return values();
                }
                """);
    }

    @Test
    void sourceAsyncIteratorCloseIsAwaitableAndDoesNotRequireBlockingClose() {
        OresCompiler.parseAndTypeCheck("""
                pub async gen* values(): AsyncIterator<int> {
                  yield 1;
                  return;
                }
                pub async fnc main(): void {
                  val AsyncIterator<int> iterator = values();
                  await iterator.close();
                  return;
                }
                """);
    }

    @Test
    void qualifiedSuspendGeneratorCannotEraseSuspensionAsFunctionValue() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module streams as
                  pub suspend gen* values(): AsyncIterator<int> {
                    yield 1;
                    return;
                  }
                end
                pub async fnc main(): void {
                  streams.values;
                  return;
                }
                """));
    }
}
