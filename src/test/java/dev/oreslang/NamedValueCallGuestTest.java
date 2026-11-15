package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** First enabled value-name surface: statically resolved direct function calls. */
final class NamedValueCallGuestTest {
    private static final String DEFINITIONS = """
            fnc combine(int left, int right): int {
              return left * 10 + right;
            }
            """;

    @Test
    void parserRetainsLexicalValueLabels() {
        Ast.Program program = Parser.parse(DEFINITIONS + """
                fnc use(): int { return combine(right: 3, left: 2); }
                """);
        assertNotNull(program);
        assertDoesNotThrow(() -> OwnershipChecker.check(TypeChecker.check(program)));
    }

    @Test
    void executesPositionalNamedReorderedAndMixedDirectCalls() throws Exception {
        assertEquals("232323", run(DEFINITIONS + """
                pub routine main(): void {
                  stdio.stdout.write(combine(2, 3));
                  stdio.stdout.write(combine(right: 3, left: 2));
                  stdio.stdout.write(combine(2, right: 3));
                  return;
                }
                """));
    }

    @Test
    void evaluatesNamedExpressionsExactlyOnceInLexicalSourceOrder() throws Exception {
        assertEquals("BA23", run("""
                fnc mark(string letter, int value): int {
                  stdio.stdout.write(letter);
                  return value;
                }
                fnc combine(int left, int right): int {
                  return left * 10 + right;
                }
                pub routine main(): void {
                  stdio.stdout.write(combine(
                    right: mark("B", 3),
                    left: mark("A", 2)));
                  return;
                }
                """));
    }

    @Test
    void namedArgumentsWorkThroughGenericDirectFunctionInference() throws Exception {
        assertEquals("42", run("""
                fnc choose<T>(T value, int ignored): T { return value; }
                pub routine main(): void {
                  stdio.stdout.write(choose(ignored: 7, value: 42));
                  return;
                }
                """));
    }

    @Test
    void genericInferenceDoesNotReorderNamedArgumentEffects() throws Exception {
        assertEquals("IV42", run("""
                fnc emit(string marker, int value): int {
                  stdio.stdout.write(marker);
                  return value;
                }
                fnc choose<T>(T value, int ignored): T {
                  return value;
                }
                pub routine main(): void {
                  // Main's ownership pass cannot infer T from a call result;
                  // the explicit witness keeps the named-argument effect order check.
                  stdio.stdout.write(choose<int>(
                      ignored: emit("I", 7),
                      value: emit("V", 42)));
                  return;
                }
                """));
    }

    @Test
    void invalidNamesCollisionsAndOmissionsFailAtCompileTime() {
        for (String arguments : new String[]{
                "left: 2, left: 3",
                "left: 2",
                "right: 3, unknown: 2",
                "2, left: 3",
                "right: 3, left: 2, surplus: 4"
        }) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                    Parser.parse(DEFINITIONS + "fnc invalid(): int { return combine("
                            + arguments + "); }")), arguments);
        }
    }

    @Test
    void positionalAfterNamedAndMixedTupleSpreadAreRejected() {
        for (String args : new String[]{
                "right: 3, 2",
                "right: 3, ...(2, 4)",
                "...(2,), right: 3"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(DEFINITIONS
                            + "fnc invalid(): int { return combine(" + args + "); }"), args);
        }
    }

    @Test
    void namedArgumentsOnMethodsAreNotSilentlyReinterpretedAsPositional() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Service as
                  pub combine(int left, int right): int { return left * 10 + right; }
                end
                fnc invalid(Service service): int { return service.combine(right: 3, left: 2); }
                """)));
    }

    @Test
    void repeatedMoveOnlyValueThroughReorderedLabelsIsRejected() {
        String source = """
                define class Box as end
                fnc consume(Box first, Box second): void { return; }
                fnc invalid(Box box): void {
                  consume(second: box, first: box);
                  return;
                }
                """;
        // TypeChecker.check includes the ownership pass; checkTypes is deliberately
        // type-only. Both the integrated and direct ownership entrypoints must reject.
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void namedArgumentsNeverTurnGenericTypeMetadataIntoValueLabels() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc identity<T>(T value): T { return value; }
                fnc valid(): int { return identity<T=int>(value: 42); }
                """)));
    }

    @Test
    void namedCallPreservesExclusiveBorrowAcrossLaterSourceArgument() {
        String source = """
                define class Cell as
                  pub let int value = 1;
                end
                fnc consume(borrow mut Cell owner, int value): int { return value; }
                fnc invalid(): int {
                  val Cell cell = new Cell();
                  return consume(owner: rt mut borrow cell, value: cell.value);
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void reorderedNamedExclusiveBorrowsRejectAliasing() {
        String source = """
                define class Cell as
                  pub let int value = 1;
                end
                fnc write(borrow mut Cell first, borrow mut Cell second): int {
                  return first.value + second.value;
                }
                fnc invalid(): int {
                  val Cell cell = new Cell();
                  return write(second: rt mut borrow cell, first: rt mut borrow cell);
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void capabilityCheckerTraversesNestedNamedValueOperands() {
        var parsed = Parser.parse("""
                fnc sink(int left, int right): int { return left; }
                fnc attempt(): int {
                  return sink(right: process.descriptor, left: 42);
                }
                """);
        assertThrows(SecurityException.class,
                () -> dev.oreslang.runtime.CapabilityChecker.check(
                        parsed, dev.oreslang.runtime.IsolatePolicy.strictFaas()));
    }

    private static String run(String program) throws Exception {
        var parsed = Parser.parse(program);
        OwnershipChecker.check(TypeChecker.check(parsed));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "named-value-call.ores")
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
