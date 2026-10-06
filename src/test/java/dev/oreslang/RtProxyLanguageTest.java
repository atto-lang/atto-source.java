package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class RtProxyLanguageTest {

    @Test
    void proxyOwnsClassStateAndSerializesFieldAndMethodAccess() throws Exception {
        String output = run("""
                define class Counter as
                  pub let int value = 1;

                  pub get(): int {
                    return self.value;
                  }
                end

                pub routine main(): void {
                  let Counter counter = new Counter();
                  val Proxy<Counter> shared = rt proxy counter;
                  shared.value = 4;
                  stdio.stdout.write(shared.get());
                  return;
                }
                """);

        assertEquals("4", output);
    }

    @Test
    void proxyCreationMovesOriginalOwner() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub let int value = 1;
                        end

                        fnc bad(): int {
                          let Counter counter = new Counter();
                          val Proxy<Counter> shared = rt proxy counter;
                          shared.value = 7;
                          return counter.value;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("moved"), failure.getMessage());
    }

    @Test
    void proxyRejectsPrimitiveTargets() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val p = rt proxy 7;
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("rt proxy"), failure.getMessage());
    }

    @Test
    void proxyCannotLeakMutableField() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Child as
                          pub let int value = 1;
                        end

                        define class Parent as
                          pub let Child child = new Child();
                        end

                        fnc bad(): Child {
                          let Parent parent = new Parent();
                          val Proxy<Parent> shared = rt proxy parent;
                          return shared.child;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("proxy"), failure.getMessage().toLowerCase());
    }

    @Test
    void actorCallableCannotTransportSharedMutex() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub shared actor fnc worker(SharedMutex<int> value): void {
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("SharedMutex"), failure.getMessage());
    }

    @Test
    void privateActorCannotAcceptProxyCapability() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub let int value = 0;
                        end

                        pub private actor fnc worker(Proxy<Counter> value): void {
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("Proxy"), failure.getMessage());
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "rt-proxy.ores")
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
