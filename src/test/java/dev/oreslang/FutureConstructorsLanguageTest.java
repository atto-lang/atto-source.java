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
    void synchronousGuestMainCanCreateAndAwaitFutureWithoutUnknownClass() throws Exception {
        String sourceText = program("""
                  pub fnc main(): void {
                    let Future<int> pending = new Future<int>(new Producer());
                    let int value = await pending;
                    stdio.stdout.write(value * 8);
                    return;
                  }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, sourceText, "future-sync-main.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("40"),
                "the direct NewExpr evaluator must lower the checked Future");
    }

    @Test
    void sealedFutureTypeCannotBeShadowedByUserClass() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module app
                  define class Future as
                  end
                  pub fnc main(): void { return; }
                end
                """));
        assertTrue(error.getMessage().contains("Future is a sealed intrinsic type"),
                () -> "built-in Future shadowing escaped compiler gate: " + error.getMessage());
    }

    @Test
    void sealedFutureTypeCannotBeShadowedByInterface() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module app
                  define interface Future
                    fnc run() => void;
                  end
                  pub fnc main(): void { return; }
                end
                """));
        assertTrue(error.getMessage().contains("Future is a sealed intrinsic type"),
                () -> "interface shadowing escaped Future name protection: " + error.getMessage());
    }

    @Test
    void voidAsyncProviderUsesFutureVoidCompletion() throws Exception {
        String sourceText = """
                define module app
                  define class NoValueProducer as
                    pub async run(): void { return; }
                  end
                  pub fnc main(): void {
                    let Future<void> future = new Future<void>(new NoValueProducer());
                    await future;
                    stdio.stdout.write("completed");
                    return;
                  }
                end
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "future-void.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("completed"));
    }

    @Test
    void constructorRejectsSynchronousRun() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
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
        assertTrue(error.getMessage().contains("Future<T> provider must have public async run()"),
                () -> "wrong synchronous-provider diagnostic: " + error.getMessage());
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
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
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
        assertTrue(error.getMessage().contains("Future<T> async provider must be stateless"),
                () -> "stateful provider failed before the intended ownership gate: " + error.getMessage());
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

    @Test
    void constructorRejectsWideningThatRequiresRuntimeResultAdaptation() {
        // The constructor currently aliases the provider method's existing
        // Future<int>, so it cannot claim to have produced Future<float>
        // merely because an int expression can be assigned to a float.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                program("""
                  pub fnc main(): void {
                    let Future<float> f = new Future<float>(new Producer());
                    return;
                  }
                """)));
        assertTrue(error.getMessage().contains("Future<T> constructor requires exact async run() result type"),
                () -> "wrong diagnostic: " + error.getMessage());
    }

    @Test
    void constructorRejectsAnyTypedResultWithoutPayloadMapping() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                program("""
                  pub fnc main(): void {
                    let Future<Any> f = new Future<Any>(new Producer());
                    return;
                  }
                """)));
        assertTrue(error.getMessage().contains("Future<T> provider and result types must be concrete"),
                () -> "wrong diagnostic: " + error.getMessage());
    }


    @Test
    void constructorRejectsNestedAnyFutureResult() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program("""
                  pub fnc main(): void {
                    let Future<Option<Any>> f = new Future<Option<Any>>(new Producer());
                    return;
                  }
                """)));
        assertTrue(error.getMessage().contains("Future<T> provider and result types must be concrete"),
                () -> "nested Any escaped Future constructor gate: " + error.getMessage());
    }

    @Test
    void constructorRejectsNamedUnknownFutureResult() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program("""
                  pub fnc main(): void {
                    let Future<Unknown> f = new Future<Unknown>(new Producer());
                    return;
                  }
                """)));
        assertTrue(error.getMessage().contains("Future<T> provider and result types must be concrete"),
                () -> "named Unknown escaped Future constructor gate: " + error.getMessage());
    }


    @Test
    void constructorRejectsErasedProviderGenericArgument() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                define module app
                  define class ErasedProducer<T> as
                    pub async run(): int { return 5; }
                  end
                  pub fnc main(): void {
                    let Future<int> f = new Future<int>(new ErasedProducer<Any>());
                    return;
                  }
                end
                """));
        assertTrue(error.getMessage().contains("requires an owned nominal async provider"),
                () -> "erased provider generic escaped Future constructor gate: " + error.getMessage());
    }


}
