package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StaticGeneratorFunctionTest {
    @Test
    void staticSyncAndSuspendGeneratorsExecuteWithoutReceiverLoans() throws Exception {
        assertEquals("126", run("""
                define class Sequences as
                  pub static gen* values(): Iterator<int> {
                    yield 20;
                    yield 22;
                    return;
                  }
                  pub static async gen* events(): AsyncIterator<int> {
                    yield 20;
                    yield 22;
                    return;
                  }
                  pub static suspend gen* cooperative(): AsyncIterator<int> {
                    rt cooperate;
                    yield 20;
                    yield 22;
                    return;
                  }
                end

                pub async routine main(): void {
                  let int total = 0;
                  for const value of Sequences.values() {
                    total = total + value;
                  }
                  for await const value of Sequences.events() {
                    total = total + value;
                  }
                  for await const value of Sequences.cooperative() {
                    total = total + value;
                  }
                  stdio.stdout.write(total);
                  return;
                }
                """));
    }

    @Test
    void legacyStaticFncGeneratorSpellingsRemainExecutable() throws Exception {
        assertEquals("126", run("""
                define class Sequences as
                  pub static fnc gen* values(): Iterator<int> {
                    yield 20;
                    yield 22;
                    return;
                  }
                  pub static async fnc gen* events(): AsyncIterator<int> {
                    yield 20;
                    yield 22;
                    return;
                  }
                  pub static suspend fnc gen* cooperative(): AsyncIterator<int> {
                    rt cooperate;
                    yield 20;
                    yield 22;
                    return;
                  }
                end
                pub async routine main(): void {
                  let int total = 0;
                  for const value of Sequences.values() { total = total + value; }
                  for await const value of Sequences.events() { total = total + value; }
                  for await const value of Sequences.cooperative() { total = total + value; }
                  stdio.stdout.write(total);
                  return;
                }
                """));
    }

    @Test
    void staticGeneratorReturnValueIsRejectedAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bad as
                  pub static gen* values(): Iterator<int> {
                    yield 1;
                    return 2;
                  }
                end
                """));
    }


    @Test
    void suspendStaticGeneratorActivationIsLazyForSyncCaller() {
        OresCompiler.parseAndTypeCheck("""
                define class Sequences as
                  pub static suspend gen* cooperative(): AsyncIterator<int> {
                    rt cooperate;
                    yield 42;
                    return;
                  }
                end
                pub fnc make(): AsyncIterator<int> {
                  return Sequences.cooperative();
                }
                """);
    }

    @Test
    void asyncStaticGeneratorYieldStarUsesAsyncOwnershipProtocol() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async gen* upstream(): AsyncIterator<int> {
                  yield 42;
                  return;
                }
                define class Sequences as
                  pub static async gen* values(): AsyncIterator<int> {
                    yield* upstream();
                    return;
                  }
                end
                """));
    }

    @Test
    void staticGeneratorFunctionValuesRemainDirectCallOnly() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Sequences as
                  pub static gen* values(): Iterator<int> {
                    yield 42;
                    return;
                  }
                end
                pub fnc bad(): void {
                  Sequences.values;
                  return;
                }
                """));
    }

    @Test
    void staticGeneratorProtocolMismatchFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Sequences as
                  pub static gen* values(): AsyncIterator<int> {
                    yield 42;
                    return;
                  }
                end
                """));
    }

    @Test
    void staticBareGeneratorRequiresStarButLegacyGeneratorFncRemainsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad as
                  pub static generator values(): Iterator<int> {
                    yield 1;
                    return;
                  }
                end
                """));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define class Good as
                  pub static generator* values(): Iterator<int> {
                    yield 1;
                    return;
                  }
                  pub static generator fnc legacy(): int {
                    yield 2;
                    return;
                  }
                end
                """));
    }

    @Test
    void instanceGeneratorMethodsRemainRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad as
                  pub gen* values(): Iterator<int> {
                    yield 42;
                    return;
                  }
                end
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "static-generators.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
