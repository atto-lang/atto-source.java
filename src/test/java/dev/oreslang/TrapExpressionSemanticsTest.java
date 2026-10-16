package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trap prefix is the expression counterpart of the trap callable modifier. */
final class TrapExpressionSemanticsTest {
    @Test
    void trapExpressionProducesTypedSomeAndNoneWithoutFlattening() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                fnc number(): int { return 17; }
                fnc empty(): Option<int> { return None; }
                fnc done(): void { return; }
                fnc as_dog(Animal animal): Dog { return animal as Dog; }

                pub fnc main(): void {
                  val Option<int> good = trap number();
                  val Option<Option<int>> nested = trap empty();
                  val Option<void> finished = trap done();
                  val Option<Dog> failed = trap as_dog(new Cat());
                  stdio.stdout.write(good.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(finished.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(failed.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("17:true:true:true", run(source));
    }

    @Test
    void trapWorkerSendReturnsSomeOnAdmissionAndNoneAfterTermination() throws Exception {
        String source = """
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  val Option<void> accepted = trap worker.send(42);
                  await worker.get_done();
                  val Option<void> rejected = trap worker.send(43);
                  stdio.stdout.write(accepted.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(rejected.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:true", run(source));
    }

    @Test
    void trapExpressionCannotSuppressPanic() throws Exception {
        String source = """
                fnc panics(): int {
                  val Option<int> missing = None;
                  return missing.unwrap();
                }
                pub fnc main(): void {
                  val Option<int> caught = trap panics();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(failure.getMessage().contains("Option::unwrap"));
    }

    @Test
    void trapIsAnExpressionNotANewCallableOrImplicitUnwrap() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc number(): int { return 1; }
                fnc broken(): void {
                  val int raw = trap number();
                  return;
                }
                """));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc number(): int { return 1; }
                fnc valid(): void {
                  val Option<int> wrapped = trap (number() + 2);
                  return;
                }
                """));
    }

    @Test
    void trapBoundarySurvivesAwaitAndCapturesLateOrdinaryFailure() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end
                pub async fnc later(Animal animal): Dog {
                  rt cooperate;
                  return animal as Dog;
                }
                pub async routine main(): void {
                  val Option<Dog> good = trap await later(new Dog());
                  val Option<Dog> bad = trap await later(new Cat());
                  stdio.stdout.write(good.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(bad.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:true", run(source));
    }

    @Test
    void trapBoundaryCannotSuppressPanicAfterSuspension() throws Exception {
        String source = """
                pub async fnc later(): int {
                  rt cooperate;
                  val Option<int> missing = None;
                  return missing.unwrap();
                }
                pub async routine main(): void {
                  val Option<int> ignored = trap await later();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(failure.getMessage().contains("Option::unwrap"));
    }

    @Test
    void trapConsumesMoveOnlyOperandsLikeSomeConstruction() {
        String source = """
                define class Payload as
                end
                fnc invalid(): void {
                  val Payload owned = new Payload();
                  val Option<Payload> wrapped = trap owned;
                  val Payload aliased = owned;
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("moved"), failure.getMessage());
    }

    @Test
    void trapCannotSmuggleBorrowIntoOwnedOption() {
        String source = """
                define class Payload as
                end
                fnc invalid(): void {
                  val Payload owned = new Payload();
                  val wrapped = trap (rt borrow owned);
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("trap cannot store a borrow"), failure.getMessage());
    }

    @Test
    void trapPreservesOwnershipForCopyPayloads() throws Exception {
        String source = """
                pub fnc main(): void {
                  val int number = 7;
                  val Option<int> first = trap number;
                  val Option<int> second = trap number;
                  stdio.stdout.write(first.unwrap() + second.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("14", run(source));
    }

    @Test
    void awaitOutsideTrapCannotAccidentallyFlattenOptionFuture() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async fnc operation(): int { return 7; }
                pub async fnc main(): void {
                  val Option<int> bad = await trap operation();
                  return;
                }
                """));
    }

    @Test
    void trapCannotLaunderRestrictedActorCapabilities() {
        String source = """
                pub untrusted actor fnc worker(): void {
                  val hidden = trap process.context_id;
                  return;
                }
                """;
        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(
                        Parser.parse(source),
                        IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"), denied.getMessage());
    }

    @Test
    void trapDoesNotConvertUnknownHostIndexFailureToNone() throws Exception {
        String source = """
                pub fnc main(): void {
                  val numbers = [7];
                  val int index = 99;
                  val Option<int> ignored = trap numbers[index];
                  stdio.stdout.write("must not run");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertThrows(PolyglotException.class, () -> run(source));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "trap-expression.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
