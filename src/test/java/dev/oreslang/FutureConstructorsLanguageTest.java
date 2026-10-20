package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guest-language acceptance for the first Future<T> constructor slice.
 * Host-only OresFutureConstructorsTest does not cover compiler admission.
 */
final class FutureConstructorsLanguageTest {
    private static String program(String body) {
        return """
                define module app
                  define pub class Producer as
                    pub async run(): int {
                      return 5;
                    }
                  end
                """ + body + "\nend\n";
    }

    @Test
    void typedAsyncRunConstructorProducesAwaitableGuestFuture() throws Exception {
        String sourceText = program("""
                  pub async fnc main(): void {
                    let Future<int> pending = new Future<int>(new Producer());
                    let int value = await pending;
                    stdio.println(value * 8);
                    return;
                  }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "future-from-async-run.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("40"));
    }

    @Test
    void constructorRejectsSynchronousRun() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module app
                  define class Producer as
                    pub run(): int { return 5; }
                  end
                  pub fnc main(): void {
                    let Future<int> f = new Future<int>(new Producer());
                    return;
                  }
                end
                """));
    }

    @Test
    void constructorRejectsIncompatibleGenericResult() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                program("""
                  pub fnc main(): void {
                    let Future<String> f = new Future<String>(new Producer());
                    return;
                  }
                """)));
    }

    @Test
    void constructorRejectsMissingGenericParameter() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                program("""
                  pub fnc main(): void {
                    let Future<int> f = new Future<>(new Producer());
                    return;
                  }
                """)));
    }

    @Test
    void constructorRejectsMissingProducer() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                program("""
                  pub fnc main(): void {
                    let Future<int> f = new Future<int>();
                    return;
                  }
                """)));
    }

    @Test
    void constructorRejectsDirectFutureInsteadOfProducer() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                program("""
                  pub async fnc fetch(): int { return 5; }
                  pub fnc main(): void {
                    let Future<int> f = new Future<int>(fetch());
                    return;
                  }
                """)));
    }
    @Test
    void statelessProviderGateRejectsMutableReceiverCrossTaskAlias() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module app
                  define class Stateful as
                    pub let int value = 5;
                    pub async run(): int { return self.value; }
                  end
                  pub fnc main(): void {
                    let Future<int> pending = new Future<int>(new Stateful());
                    return;
                  }
                end
                """));
    }

    @Test
    void constructorRejectsPrivateAsyncRun() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module app
                  define class PrivateProducer as
                    async run(): int { return 5; }
                  end
                  pub fnc main(): void {
                    let Future<int> pending = new Future<int>(new PrivateProducer());
                    return;
                  }
                end
                """));
    }

    @Test
    void typedAsyncRunConstructorPreservesStringResult() throws Exception {
        String sourceText = """
                define module app
                  define class StringProducer as
                    pub async run(): String { return "accepted"; }
                  end
                  pub async fnc main(): void {
                    let Future<String> pending = new Future<String>(new StringProducer());
                    let String result = await pending;
                    stdio.println(result);
                    return;
                  }
                end
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "future-string-run.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("accepted"));
    }

}
