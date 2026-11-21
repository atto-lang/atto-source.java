package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

/**
 * Soundness-first inferred data obligations for generics that flow into
 * ordinary Channel<T> payloads. Explicit generic-bound syntax is a later
 * surface feature; unsafe concrete substitutions must already fail closed.
 */
final class ChannelGenericDataConstraintTest {
    private static IllegalArgumentException rejected(String source) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    private static void assertDataOnlyRejected(String source) {
        IllegalArgumentException error = rejected(source);
        assertTrue(
                error.getMessage().contains("data-only"),
                () -> "expected data-only diagnostic, got: " + error.getMessage());
    }

    @Test
    void genericFunctionInternalChannelRejectsFutureSubstitution() {
        assertDataOnlyRejected("""
                fnc channel_user<T>(T value): void {
                  val Channel<T> values = Channel.new<T>(1);
                  return;
                }

                fnc bad(Future<int> pending): void {
                  channel_user(pending);
                  return;
                }
                """);
    }

    @Test
    void transitiveGenericWrapperInheritsLaterDeclaredChannelObligation() {
        assertDataOnlyRejected("""
                fnc wrapper<T>(T value): void {
                  channel_user(value);
                  return;
                }

                fnc channel_user<T>(T value): void {
                  val Channel<T> values = Channel.new<T>(1);
                  return;
                }

                fnc bad(Future<int> pending): void {
                  wrapper(pending);
                  return;
                }
                """);
    }

    @Test
    void genericReturnChannelRejectsUnsafeBindingEvenWhenResultIsDiscarded() {
        assertDataOnlyRejected("""
                fnc make_channel<T>(T value): Channel<T> {
                  return Channel.new<T>(1);
                }

                fnc bad(Future<int> pending): void {
                  make_channel(pending);
                  return;
                }
                """);
    }

    @Test
    void classChannelFieldConstrainsConcreteNominalInstantiation() {
        assertDataOnlyRejected("""
                define class Box<T> as
                  val Channel<T> values;
                end

                fnc bad(): void {
                  new Box<Future<int>>();
                  return;
                }
                """);
    }

    @Test
    void nestedNominalWrapperPropagatesClassChannelRequirement() {
        assertDataOnlyRejected("""
                define class Box<T> as
                  val Channel<T> values;
                end

                define class Envelope<T> as
                  val Box<T> box;
                end

                fnc bad(Envelope<Future<int>> value): void {
                  return;
                }
                """);
    }

    @Test
    void objectAndNestedFutureSubstitutionsStayRejected() {
        assertDataOnlyRejected("""
                fnc channel_user<T>(T value): void {
                  val Channel<T> values = Channel.new<T>(1);
                  return;
                }

                fnc bad(Object opaque): void {
                  channel_user(opaque);
                  return;
                }
                """);

        assertDataOnlyRejected("""
                fnc channel_user<T>(T value): void {
                  val Channel<T> values = Channel.new<T>(1);
                  return;
                }

                fnc bad(Option<Future<int>> pending): void {
                  channel_user(pending);
                  return;
                }
                """);
    }

    @Test
    void instanceMethodInternalChannelConstrainsClassGenericAtCall() {
        assertDataOnlyRejected("""
                define class Worker<T> as
                  use(T value): void {
                    val Channel<T> values = Channel.new<T>(1);
                    return;
                  }
                end

                fnc bad(Worker<Future<int>> worker, Future<int> pending): void {
                  worker.use(pending);
                  return;
                }
                """);
    }

    @Test
    void inferredConstructorBindingHonorsConstructorChannelObligation() {
        assertDataOnlyRejected("""
                define class Maker<T> as
                  constructor(T seed) {
                    val Channel<T> values = Channel.new<T>(1);
                    return;
                  }
                end

                fnc bad(Future<int> pending): void {
                  new Maker<>(pending);
                  return;
                }
                """);
    }

    @Test
    void scalarAndNestedDataSubstitutionsRemainLegal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc channel_user<T>(T value): void {
                  val Channel<T> values = Channel.new<T>(1);
                  return;
                }

                fnc ok(): void {
                  channel_user(7);
                  val Option<int> maybe = Some(9);
                  channel_user(maybe);
                  val Result<int, string> result = Ok(11);
                  channel_user(result);
                  return;
                }
                """)));
    }

    @Test
    void safeOverloadDoesNotInheritDifferentArityConstraint() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc route<T>(T value): void {
                  val Channel<T> values = Channel.new<T>(1);
                  return;
                }

                fnc route<T>(T left, T right): void {
                  return;
                }

                fnc ok(Future<int> pending): void {
                  route(pending, pending);
                  return;
                }
                """)));
    }

    @Test
    void genericClassWithDataBindingRemainsUsableAsAType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box<T> as
                  val Channel<T> values;
                end

                fnc consume(Box<int> box): void {
                  return;
                }
                """)));
    }
}
