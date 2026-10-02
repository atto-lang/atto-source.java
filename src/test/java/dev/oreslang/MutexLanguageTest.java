package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MutexLanguageTest {
    @Test
    void mutexGuardTransparentlyProtectsClassStateAndReleasesLexically() throws Exception {
        String program = """
                define module model
                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  pub fnc main() => void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    guard.value = guard.value + 2;
                    stdio.println(guard.value);
                    guard.release();

                    val again = mutex.lock();
                    stdio.println(again.value);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "mutex.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.lines().filter("2"::equals).count() >= 2);
    }

    @Test
    void awaitWhileHoldingGuardIsRejectedStatically() {
        var program = Parser.parse("""
                define module model
                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  async fnc bad() => void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    await mutex.lock_async();
                    guard.value = 1;
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("cannot await while holding a MutexGuard"));
    }

    @Test
    void withLockProvidesMutableProtectedValue() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc good() => void {
                    val mutex = Mutex.new(new Counter());
                    mutex.with_lock(|counter| -> {
                      counter.value = counter.value + 1;
                      return;
                    });
                    return;
                  }
                end
                """)));
    }

    @Test
    void awaitInsideWithLockCriticalSectionIsRejected() {
        var program = Parser.parse("""
                define module model
                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  async fnc bad() => void {
                    val mutex = Mutex.new(new Counter());
                    mutex.with_lock(|counter| -> {
                      await mutex.lock_async();
                      return;
                    });
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("cannot await while holding a MutexGuard"));
    }

    @Test
    void guardCannotBeStoredInAggregate() {
        var program = Parser.parse("""
                define module model
                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad() => void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val escaped = arr[guard];
                    stdio.println(escaped);
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("MutexGuard cannot be stored"));
    }

    @Test
    void guardCannotEscapeThroughClosureCapture() {
        var program = Parser.parse("""
                define module model
                  define class Counter
                    pub let int value = 0;
                  end
                end

                define module app
                  fnc bad() => void {
                    val mutex = Mutex.new(new Counter());
                    val guard = mutex.lock();
                    val callback = || -> {
                      stdio.println(guard.value);
                      return;
                    };
                    callback();
                    return;
                  }
                end
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("closure cannot capture guard-bearing value"));
    }
}
