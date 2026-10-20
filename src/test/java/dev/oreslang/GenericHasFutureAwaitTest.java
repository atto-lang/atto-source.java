package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class GenericHasFutureAwaitTest {
    private static final String DECLARATIONS = """
            define pub trait HasFuture<T> as
                get_future(): Future<T>;
            end
            async fnc answer(): int { return 42; }
            async fnc label(): string { return "forty-two"; }
            define pub class MyOperation<T> as
                implements HasFuture<T> as
                pub val Future<T> pending;
                pub get_future(): Future<T> {
                    return self.pending;
                }
            end
            """;

    @Test
    void genericAwaitPreservesReturnTypeAndRunsInGuestScheduler() throws Exception {
        String source = DECLARATIONS + """
                pub fnc main(): void {
                    val int direct = await new MyOperation<int>(answer());
                    val HasFuture<string> wrapped = new MyOperation<string>(label());
                    val string second = await wrapped;
                    stdio.stdout.write(direct);
                    stdio.stdout.write(second);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "has-future.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        String result = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("42"), result);
        assertTrue(result.contains("forty-two"), result);
    }

    @Test
    void rejectsIncorrectExpectedGenericResult() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(DECLARATIONS + """
                        fnc invalid(): string {
                            return await new MyOperation<int>(answer());
                        }
                        """));
    }

    @Test
    void rejectsStructuralDuckTypingAndInvalidContract() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class NotAwaitable as
                            pub get_future(): Future<int> { return null; }
                        end
                        fnc bad(NotAwaitable value): int { return await value; }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): int;
                        end
                        """));
    }

    @Test
    void resolvesQualifiedModuleScopedGenericTrait() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define module app
                    define pub trait HasFuture<T> as
                        get_future(): Future<T>;
                    end
                    async fnc value(): int { return 99; }
                    define pub class Wrapper<U> as
                        implements HasFuture<U> as
                        pub val Future<U> pending;
                        pub get_future(): Future<U> { return self.pending; }
                    end
                    fnc read(): int {
                        val HasFuture<int> wrapped = new Wrapper<int>(value());
                        return await wrapped;
                    }
                end
                """));
    }

    @Test
    void rejectsSuspendingFutureProjection() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        async fnc source(): int { return 3; }
                        define class Invalid implements HasFuture<int> as
                            pub suspends get_future(): Future<int> { return source(); }
                        end
                        """));
    }

    @Test
    void rejectsInheritedSuspendingOverride() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        async fnc value(): int { return 17; }
                        define class Base implements HasFuture<int> as
                            pub get_future(): Future<int> { return value(); }
                        end
                        define class Child extends Base as
                            pub async get_future(): Future<int> { return value(); }
                        end
                        fnc read(Child child): int { return await child; }
                        """));
    }

    @Test
    void callsProjectionOncePerAwaitOccurrence() throws Exception {
        String source = """
                define trait HasFuture<T> as
                    get_future(): Future<T>;
                end
                async fnc value(): int { return 9; }
                define class Counter implements HasFuture<int> as
                    pub let int calls = 0;
                    pub get_future(): Future<int> {
                        self.calls = self.calls + 1;
                        return value();
                    }
                end
                pub fnc main(): void {
                    let Counter counter = new Counter();
                    val int result = await counter;
                    stdio.stdout.write(result);
                    stdio.stdout.write(counter.calls);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "hasfuture-once.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("91", stdout.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void acceptsExistingHeaderSyntaxAndNativeFuture() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define trait HasFuture<T> as
                    fnc get_future(): Future<T>;
                end
                async fnc answer(): int { return 1; }
                define class A implements HasFuture<int> as
                    pub get_future(): Future<int> { return answer(); }
                end
                fnc read(): int { return await new A(); }
                """));
    }
}
