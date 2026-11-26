package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrapResultSemanticsTest {

    @Test
    void trapCallsTypeAsResultAndAnOptionIsOrdinaryData() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                trap fnc value(): int {
                  return 7;
                }

                trap fnc maybe(bool present): Option<int> {
                  if present; then
                    return Some(9);
                  fi
                  return None;
                }

                trap fnc done(): void {
                  return;
                }

                fnc use(): void {
                  val Result<int, Error> one = value();
                  val Result<Option<int>, Error> nested = maybe(true);
                  val Result<void, Error> finished = done();
                  return;
                }
                """)));
    }

    @Test
    void trapReturnsOkOnSuccessAndErrOnOrdinaryRuntimeFailure() throws Exception {
        String program = """
                define class Animal as
                end

                define class Dog extends Animal as
                end

                define class Cat extends Animal as
                end

                trap fnc as_dog(Animal animal): Dog {
                  return animal as Dog;
                }

                trap fnc maybe(bool present): Option<int> {
                  if present; then
                    return Some(9);
                  fi
                  return None;
                }

                trap fnc done(): void {
                  return;
                }

                pub fnc main(): void {
                  val Result<Dog, Error> good = as_dog(new Dog());
                  val Result<Dog, Error> bad = as_dog(new Cat());
                  val Result<Option<int>, Error> nested_some = maybe(true);
                  val Result<Option<int>, Error> nested_none = maybe(false);
                  val Result<void, Error> finished = done();

                  stdio.stdout.write(good.is_ok());
                  stdio.stdout.write(":");
                  stdio.stdout.write(bad.is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested_some.unwrap().unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested_none.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(finished.is_ok());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true:true:9:true:true", run(program));
    }

    @Test
    void panicStillBypassesTrap() throws Exception {
        String program = """
                trap fnc panics(): int {
                  val Option<int> missing = None;
                  return missing.expect("explicit panic control");
                }

                pub fnc main(): void {
                  val Result<int, Error> ignored = panics();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        PolyglotException thrown = assertThrows(PolyglotException.class, () -> run(program));
        assertTrue(thrown.getMessage().contains("explicit panic control"));
    }

    @Test
    void unsupportedSuspendingTrapFormsFailClosed() {
        IllegalArgumentException async = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        async trap fnc later(): int {
                          return 1;
                        }
                        """));
        assertTrue(async.getMessage().contains("suspending trap"));

        IllegalArgumentException generator = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        generator trap fnc values(): int {
                          yield 1;
                          return;
                        }
                        """));
        assertTrue(generator.getMessage().contains("trap generator"));
    }


    @Test
    void everySynchronousTrappedResultRejectsAssignmentToBareSuccessType() {
        String declarations = """
                trap fnc number(): int { return 7; }
                trap fnc truth(): bool { return true; }
                trap fnc done(): void { return; }
                trap fnc optional(): Option<int> { return None; }
                """;
        String[] invalidBindings = {
                "val int unwrapped = number();",
                "val bool unwrapped = truth();",
                "val int unwrapped = optional();",
                "val Option<int> flattened = optional();",
                "val Result<Result<int, Error>, Error> doubled = number();",
                "val Option<int> wrong_void = done();"
        };
        for (String binding : invalidBindings) {
            String source = declarations + "fnc check(): void { " + binding + " return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), binding);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(declarations + """
                fnc check(): void {
                  val Result<int, Error> number_value = number();
                  val Result<bool, Error> truth_value = truth();
                  val Result<void, Error> finished = done();
                  val Result<Option<int>, Error> nested = optional();
                  return;
                }
                """));
    }

    @Test
    void trappedFunctionValueMustPreserveResultReturn() throws Exception {
        String source = """
                trap fnc value(int x): int { return x + 1; }
                pub fnc main(): void {
                  val Fnc<int, Result<int, Error>> callback = value;
                  val Result<int, Error> direct = value(9);
                  val Result<int, Error> via_callback = callback(9);
                  stdio.stdout.write(direct.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(via_callback.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("10:10", run(source));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                trap fnc value(int x): int { return x; }
                fnc bad(): void {
                  val Fnc<int, int> callback = value;
                  return;
                }
                """));
    }

    @Test
    void qualifiedModuleCallsAreResultTyped() throws Exception {
        String source = """
                define module safely as
                  pub trap fnc value(): int { return 23; }
                end
                pub fnc main(): void {
                  val Result<int, Error> wrapped = safely.value();
                  stdio.stdout.write(wrapped.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("23", run(source));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module safely as
                  pub trap fnc value(): int { return 23; }
                end
                fnc bad(): void {
                  val int raw = safely.value();
                  return;
                }
                """));
    }

    @Test
    void returnedNoneIsOkNoneAndRuntimeFailureIsErr() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                trap fnc optional(bool enabled): Option<int> {
                  if enabled; then
                    return Some(21);
                  fi
                  return None;
                }
                trap fnc failed(Animal animal): Option<int> {
                  val Dog dog = animal as Dog;
                  return None;
                }

                pub fnc main(): void {
                  val Result<Option<int>, Error> none_value = optional(false);
                  val Result<Option<int>, Error> some_value = optional(true);
                  val Result<Option<int>, Error> failed_value = failed(new Cat());
                  stdio.stdout.write(none_value.is_ok());
                  stdio.stdout.write(":");
                  stdio.stdout.write(none_value.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(some_value.unwrap().unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(failed_value.is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:true:21:true", run(source));
    }

    @Test
    void voidTrapReturnsOkEvenWithoutValue() throws Exception {
        String source = """
                trap fnc done(): void {
                  return;
                }
                pub fnc main(): void {
                  val Result<void, Error> completed = done();
                  stdio.stdout.write(completed.is_ok());
                  stdio.stdout.write(":");
                  stdio.stdout.write(completed.is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:false", run(source));
    }

    @Test
    void aTrapCallingAnotherTrapDoesNotDoubleWrap() throws Exception {
        String source = """
                trap fnc inner(): int { return 11; }
                trap fnc outer(): Result<int, Error> {
                  return inner();
                }
                pub fnc main(): void {
                  val Result<int, Error> nested = outer();
                  stdio.stdout.write(nested.is_ok());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:11", run(source));
    }

    @Test
    void ordinaryFunctionsRemainUnwrapped() throws Exception {
        String source = """
                fnc raw(): int { return 3; }
                trap fnc safe(): int { return 7; }
                pub fnc main(): void {
                  val int a = raw();
                  val Result<int, Error> b = safe();
                  stdio.stdout.write(a);
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("3:7", run(source));
    }

    @Test
    void trapDoesNotSwallowResultExpectPanic() throws Exception {
        String source = """
                trap fnc panics(): int {
                  val Result<int, String> invalid = Err("missing");
                  return invalid.expect("explicit panic control");
                }
                pub fnc main(): void {
                  val Result<int, Error> ignored = panics();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException thrown = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(thrown.getMessage().contains("explicit panic control"));
    }

    @Test
    void ordinaryCatchStillHandlesExplicitRuntimeCastFailure() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                pub fnc main(): void {
                  val Animal animal = new Cat();
                  try {
                    val Dog dog = animal as Dog;
                    stdio.stdout.write("unexpected");
                  } catch (err) {
                    stdio.stdout.write("caught");
                  } finally {
                    stdio.stdout.write(":cleanup");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("caught:cleanup", run(source));
    }

    @Test
    void aDeferredOrdinaryCastErrorCannotDowngradeExpectPanic() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                fnc deferred_cast(Animal animal): void {
                  val Dog dog = animal as Dog;
                  return;
                }

                trap fnc panic_then_cleanup(Animal animal): int {
                  defer deferred_cast(animal);
                  val Option<int> absent = None;
                  return absent.expect("explicit panic control");
                }

                pub fnc main(): void {
                  val Result<int, Error> ignored = panic_then_cleanup(new Cat());
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException panic = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(panic.getMessage().contains("explicit panic control"));
    }

    @Test
    void deferredExpectPanicOverridesRecoverableBodyCastError() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                fnc deferred_panic(): void {
                  val Option<int> absent = None;
                  val int invalid = absent.expect("explicit panic control");
                  return;
                }

                trap fnc cast_then_panic(Animal animal): int {
                  defer deferred_panic();
                  val Dog dog = animal as Dog;
                  return 1;
                }

                pub fnc main(): void {
                  val Result<int, Error> ignored = cast_then_panic(new Cat());
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException panic = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(panic.getMessage().contains("explicit panic control"));
    }

    @Test
    void finallyCastErrorCannotDowngradeAnExpectPanic() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                trap fnc panic_with_finally(Animal animal): int {
                  try {
                    val Option<int> absent = None;
                    return absent.expect("explicit panic control");
                  } catch (err) {
                    return 7;
                  } finally {
                    val Dog impossible = animal as Dog;
                  }
                }

                pub fnc main(): void {
                  val Result<int, Error> ignored = panic_with_finally(new Cat());
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException panic = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(panic.getMessage().contains("explicit panic control"));
    }

    @Test
    void suspendableTryFinallyCannotDowngradeExpectPanic() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                async fnc answer(): int { return 1; }
                async fnc panics(): int {
                  val int received = await answer();
                  val Animal animal = new Cat();
                  try {
                    val Option<int> absent = None;
                    return absent.expect("explicit panic control");
                  } catch (err) {
                    return received;
                  } finally {
                    val Dog impossible = animal as Dog;
                  }
                }
                pub fnc main(): void {
                  val int impossible = await panics();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException panic = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(panic.getMessage().contains("explicit panic control"));
    }

    @Test
    void suspendedTryCatchStillRecoversOrdinaryCastAndRunsFinallyOnce() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                async fnc answer(): int { return 11; }
                async fnc recover_cast(): int {
                  val int seed = await answer();
                  val Animal animal = new Cat();
                  let mut int status = 0;
                  try {
                    val Dog dog = animal as Dog;
                    status = 1;
                  } catch (err) {
                    status = seed;
                  } finally {
                    status = status + 1;
                  }
                  return status;
                }
                pub fnc main(): void {
                  stdio.stdout.write(await recover_cast());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("12", run(source));
    }

    @Test
    void suspendedNestedBlockRunsDeferAndFinallyExactlyOnce() throws Exception {
        String source = """
                async fnc answer(): int {
                  rt cooperate;
                  return 11;
                }
                async fnc compute(): int {
                  let mut int status = 0;
                  try {
                    defer stdio.stdout.write("D");
                    val int number = await answer();
                    status = number;
                  } catch (err) {
                    status = 99;
                  } finally {
                    stdio.stdout.write("F");
                  }
                  return status;
                }
                pub fnc main(): void {
                  stdio.stdout.write(await compute());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("DF11", run(source));
    }

    @Test
    void suspendedNestedPanicRunsDeferAndFinallyOnceWithoutCatch() throws Exception {
        String source = """
                async fnc answer(): int {
                  rt cooperate;
                  return 11;
                }
                async fnc compute(): int {
                  val int seed = await answer();
                  try {
                    defer stdio.stdout.write("D");
                    val Option<int> absent = None;
                    return absent.expect("explicit panic control");
                  } catch (err) {
                    stdio.stdout.write("C");
                    return seed;
                  } finally {
                    stdio.stdout.write("F");
                  }
                }
                pub fnc main(): void {
                  val int unused = await compute();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "nested-once.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            PolyglotException panic = assertThrows(
                    PolyglotException.class, () -> context.eval(program));
            assertTrue(panic.getMessage().contains("explicit panic control"));
        }
        assertEquals("DF", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void expressionTrapIsResultTypedAfterCleanupReconciliation() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Result<int, Error> result = trap (40 + 2);
                  stdio.stdout.write(result.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }

    @Test
    void aCaughtThrowIsASanitizedErrorValueWithoutHostInternals() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end
                fnc as_dog(Animal animal): Dog { return animal as Dog; }
                pub fnc main(): void {
                  val Result<Dog, Error> outcome = trap as_dog(new Cat());
                  stdio.stdout.write(outcome.is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(outcome);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        String output = run(source);
        assertTrue(output.startsWith("true:"), output);
        assertTrue(output.contains("Error("), output);
        assertFalse(output.contains("at dev.") || output.contains("at java.")
                || output.contains("Exception:"), "no host stack or exception text: " + output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "trap-option.ores")
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
    @Test
    void checkedUnwrapFailuresAreCaughtAsResultAndNormalValuesSurvive() throws Exception {
        String source = """
                fnc option_case(bool present): int {
                  let mut Option<int> state = None;
                  if present then
                    state = Some(42);
                  fi
                  return state.unwrap();
                }
                fnc result_case(bool present): int {
                  let mut Result<int, String> state = Err("original");
                  if present then
                    state = Ok(42);
                  fi
                  return state.unwrap();
                }
                pub fnc main(): void {
                  val Result<int, Error> a = trap! option_case(false);
                  val Result<int, Error> b = trap! result_case(false);
                  val Result<int, Error> c = trap! option_case(true);
                  val Result<int, Error> d = trap result_case(true);
                  stdio.stdout.write(a.is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(c.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(d.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:true:42:42", run(source));
    }

    @Test
    void checkedUnwrapRunsCleanupAndDoesNotExecuteLaterStatements() throws Exception {
        String source = """
                fnc fail(): int {
                  defer stdio.stdout.write("D");
                  val Option<int> missing = None;
                  val int ignored = missing.unwrap();
                  stdio.stdout.write("unreachable");
                  return ignored;
                }
                pub fnc main(): void {
                  val Result<int, Error> outcome = trap! fail();
                  stdio.stdout.write(outcome.is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("Dtrue", run(source));
    }

    @Test
    void directUnwrapAndNestedTrapsPreserveOneResultLayer() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Option<int> missing = None;
                  val Option<int> present = Some(42);
                  val Result<int, Error> bad = trap! missing.unwrap();
                  val Result<int, Error> nested = trap! (trap! missing.unwrap());
                  val Result<int, Error> good = trap! present.unwrap();
                  val Result<Option<int>, Error> ordinary = trap! missing;
                  stdio.stdout.write(bad.is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested.is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(good.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(ordinary.unwrap().is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:true:42:true", run(source));
    }

    @Test
    void shortCircuitSkipsFailedUnwrapAndEvaluatesLiveRhsOnce() throws Exception {
        String source = """
                fnc rhs(): bool {
                  stdio.stdout.write("R");
                  val Option<bool> missing = None;
                  return missing.unwrap();
                }
                fnc conjunction(bool run): bool { return run && rhs(); }
                fnc disjunction(bool skip): bool { return skip || rhs(); }
                pub fnc main(): void {
                  val Result<bool, Error> a = trap! conjunction(false);
                  val Result<bool, Error> b = trap! disjunction(true);
                  stdio.stdout.write(a.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.unwrap());
                  stdio.stdout.write(":");
                  val Result<bool, Error> c = trap! conjunction(true);
                  val Result<bool, Error> d = trap! disjunction(false);
                  stdio.stdout.write(c.is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(d.is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("false:true:RRtrue:true", run(source));
    }

    @Test
    void catchReceivesOrdinaryUnwrapFailureAndFinallyRunsOnce() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Option<int> missing = None;
                  try {
                    val int value = missing.unwrap();
                    stdio.stdout.write("unreachable");
                  } catch (err) {
                    stdio.stdout.write("C");
                  } finally {
                    stdio.stdout.write("F");
                  }
                  stdio.stdout.write("after");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("CFafter", run(source));
    }


    @Test
    void suspendedOrdinaryUnwrapRunsDeferCatchFinallyOnce() throws Exception {
        String source = """
                async fnc answer(): int {
                  rt cooperate;
                  return 11;
                }
                async fnc compute(): int {
                  val int seed = await answer();
                  try {
                    defer stdio.stdout.write("D");
                    val Option<int> absent = None;
                    return absent.unwrap();
                  } catch (err) {
                    stdio.stdout.write("C");
                    return seed;
                  } finally {
                    stdio.stdout.write("F");
                  }
                }
                pub fnc main(): void {
                  val int value = await compute();
                  stdio.stdout.write(value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("DCF11", run(source));
    }

}
