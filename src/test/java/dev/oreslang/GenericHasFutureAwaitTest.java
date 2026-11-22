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
                            pub suspends get_future(): Future<int> { return value(); }
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
                    pub get_future(): Future<int> {
                        stdio.stdout.write("P");
                        return value();
                    }
                end
                pub fnc main(): void {
                    let Counter counter = new Counter();
                    val int result = await counter;
                    stdio.stdout.write(result);
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
        assertEquals("P9", stdout.toString(StandardCharsets.UTF_8).trim());
    }


    @Test
    void rejectsMutableReceiverProjectionOnImmutableCapability() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        async fnc source(): int { return 1; }
                        define class Wrong implements HasFuture<int> as
                            pub get_future(mut self)(): Future<int> { return source(); }
                        end
                        """));
    }

    @Test
    void preservesExactlyOneFutureNestingLayer() {
        // Async functions cannot export Future<T> as task payloads; that would
        // cross the async boundary with a future. Test this rule using a
        // statically supplied capability instead of an illegal async producer.
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define trait HasFuture<T> as
                    get_future(): Future<T>;
                end
                fnc read(HasFuture<Future<int>> operation): Future<int> {
                    return await operation;
                }
                """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        fnc invalid(HasFuture<Future<int>> operation): int {
                            return await operation;
                        }
                        """));
    }

    @Test
    void allowsRepeatedAwaitOfNamedCapabilityWithoutMovingItsReceiver() throws Exception {
        String source = """
                define trait HasFuture<T> as
                    get_future(): Future<T>;
                end
                async fnc answer(): int { return 8; }
                define class Repeatable implements HasFuture<int> as
                    pub get_future(): Future<int> {
                        stdio.stdout.write("P");
                        return answer();
                    }
                end
                pub fnc main(): void {
                    val Repeatable operation = new Repeatable();
                    val int first = await operation;
                    stdio.stdout.write(first);
                    val int second = await operation;
                    stdio.stdout.write(second);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "has-future-repeat.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("P8P8", stdout.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void specializesInheritedGenericClassCapability() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define trait HasFuture<T> as
                    get_future(): Future<T>;
                end
                async fnc source(): int { return 33; }
                define class Parent<T> implements HasFuture<T> as
                    pub val Future<T> pending;
                    pub get_future(): Future<T> { return self.pending; }
                end
                define class Child<U> extends Parent<U> as
                end
                fnc read(Child<int> child): int { return await child; }
                fnc make(): int { return await new Child<int>(source()); }
                """));
    }

    @Test
    void inheritedGenericCapabilityRunsThroughGuestScheduler() throws Exception {
        String source = """
                define trait HasFuture<T> as
                    get_future(): Future<T>;
                end
                async fnc answer(): int { return 33; }
                define class Parent<T> implements HasFuture<T> as
                    pub val Future<T> pending;
                    pub get_future(): Future<T> { return self.pending; }
                end
                define class Child extends Parent<int> as
                end
                pub fnc main(): void {
                    val int number = await new Child(answer());
                    stdio.stdout.write(number);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "has-future-inherited.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("33", stdout.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void specializesGenericCapabilityThroughTraitExtension() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define trait HasFuture<T> as
                    get_future(): Future<T>;
                end
                define trait DerivedFuture<U> extends HasFuture<U> as
                end
                async fnc source(): string { return "ok"; }
                define class Derived<V> implements DerivedFuture<V> as
                    pub val Future<V> pending;
                    pub get_future(): Future<V> { return self.pending; }
                end
                fnc read(Derived<string> op): string { return await op; }
                """));
    }


    @Test
    void rejectsInheritedGetterReturningWrongFutureGeneric() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        async fnc integerValue(): int { return 4; }
                        async fnc stringValue(): string { return "wrong"; }
                        define class Parent implements HasFuture<int> as
                            pub get_future(): Future<int> { return integerValue(); }
                        end
                        define class Child extends Parent as
                            pub get_future(): Future<string> { return stringValue(); }
                        end
                        fnc use(Child child): int { return await child; }
                        """));
    }

    @Test
    void rejectsStructuralHasFutureContract() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            @structural fnc get_future(): Future<T>;
                        end
                        """));
    }

    @Test
    void rejectsPrivateProjectionImplementation() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        async fnc pending(): int { return 12; }
                        define class Hidden implements HasFuture<int> as
                            private get_future(): Future<int> { return pending(); }
                        end
                        """));
    }

    @Test
    void rejectsStaticFutureProjection() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        async fnc value(): int { return 7; }
                        define class StaticProjection implements HasFuture<int> as
                            pub static fnc get_future(): Future<int> { return value(); }
                        end
                        """));
        assertTrue(error.getMessage().contains("get_future"), error.getMessage());
    }

    @Test
    void rejectsGenericGetterForNominalFutureCapability() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        async fnc value(): int { return 7; }
                        define class GenericProjection implements HasFuture<int> as
                            pub get_future<U>(): Future<int> { return value(); }
                        end
                        """));
        assertTrue(error.getMessage().contains("get_future"), error.getMessage());
    }

    @Test
    void rejectsPrivateInheritedGetterOverride() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        async fnc value(): int { return 7; }
                        define class Base implements HasFuture<int> as
                            pub get_future(): Future<int> { return value(); }
                        end
                        define class Child extends Base as
                            private get_future(): Future<int> { return value(); }
                        end
                        fnc read(Child child): int { return await child; }
                        """));
        assertTrue(error.getMessage().contains("effective HasFuture"), error.getMessage());
    }

    @Test
    void acceptsDiamondWithTheSameSpecializedFutureType() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define trait HasFuture<T> as
                    get_future(): Future<T>;
                end
                define trait Left<T> extends HasFuture<T> as
                end
                define trait Right<T> extends HasFuture<T> as
                end
                async fnc ready(): int { return 3; }
                define class Diamond implements Left<int>, Right<int> as
                    pub get_future(): Future<int> { return ready(); }
                end
                fnc read(Diamond value): int { return await value; }
                """));
    }

    @Test
    void rejectsDiamondWithConflictingSpecializedFutureTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T> as
                            get_future(): Future<T>;
                        end
                        define trait Left<T> extends HasFuture<T> as
                        end
                        define trait Right<T> extends HasFuture<T> as
                        end
                        async fnc ready(): int { return 3; }
                        define class Ambiguous implements Left<int>, Right<string> as
                            pub get_future(): Future<int> { return ready(); }
                        end
                        fnc read(Ambiguous value): int { return await value; }
                        """));
    }

    @Test
    void rejectsMalformedHasFutureGenericArity() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define trait HasFuture<T, E> as
                            get_future(): Future<T>;
                        end
                        """));
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
