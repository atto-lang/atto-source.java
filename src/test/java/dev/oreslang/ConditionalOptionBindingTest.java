package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conditional as const/let is an Option presence test, not an implicit
 * truthiness conversion. Phase 1 accepts Copy payloads only.
 */
final class ConditionalOptionBindingTest {
    @Test
    void copyOptionBindingsTypeCheckAndRunInNormalInterpreter() throws Exception {
        String program = """
                fnc increment(Option<int> maybe): int {
                  if maybe as let value then
                    value = value + 1;
                    return value;
                  else
                    return 0;
                  fi
                }

                fnc guard(Option<int> maybe): bool {
                  if maybe as const value && value eq 15 then
                    return true;
                  else
                    return false;
                  fi
                }

                fnc shorthand(Option<int> maybe): bool {
                  if (maybe as const value) eq 15 then
                    return true;
                  else
                    return false;
                  fi
                }

                fnc typed(Option<int> maybe): bool {
                  if (maybe as const value) is type int then
                    return true;
                  else
                    return false;
                  fi
                }

                fnc optionBool(Option<bool> maybe): bool {
                  if maybe as const value then
                    return value;
                  else
                    return true;
                  fi
                }

                pub fnc main(): void {
                  stdio.println(increment(Some(15)));
                  stdio.println(increment(None));
                  stdio.println(guard(Some(15)));
                  stdio.println(guard(Some(0)));
                  stdio.println(shorthand(Some(15)));
                  stdio.println(shorthand(None));
                  stdio.println(typed(Some(5)));
                  stdio.println(typed(None));
                  stdio.println(optionBool(Some(false)));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals(List.of(
                "16", "0", "true", "false", "true", "false",
                "true", "false", "false"), run(program));
    }

    @Test
    void guardProducerMayConsumeItsMoveOnlyArgumentExactlyOnce() {
        String valid = """
                define class Box as
                end

                fnc consume(Box box): Option<int> {
                  return Some(15);
                }

                fnc good(Box box): int {
                  if consume(box) as let value && value eq 15 then
                    return value;
                  else
                    return 0;
                  fi
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));

        String invalid = """
                define class Box as
                end

                fnc consume(Box box): Option<int> {
                  return Some(15);
                }

                fnc bad(Box box): int {
                  if consume(box) as const value then
                    consume(box);
                    return value;
                  else
                    return 0;
                  fi
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(error.getMessage().contains("moved") || error.getMessage().contains("move"),
                error.getMessage());
    }

    @Test
    void movedGuardArgumentsCannotReviveOnElseOrElifFailureEdges() {
        String header = """
                define class Box as
                end

                fnc consume(Box box): Option<int> {
                  return Some(15);
                }
                """;

        // A producer consumes its argument whether it returns Some or None.
        // The false edge must retain that ownership effect.
        rejects(header + """
                fnc bad(Box box): void {
                  if consume(box) as const found then
                    return;
                  else
                    consume(box);
                  fi
                  return;
                }
                """, "moved");

        // elif is evaluated only after the preceding guard failed, but the
        // preceding guard's move has already happened.
        rejects(header + """
                fnc bad(Box box): void {
                  if consume(box) as const first then
                    return;
                  elif consume(box) as let second then
                    return;
                  fi
                  return;
                }
                """, "moved");

        // The short-circuited RHS may consume the owner. A sound checker
        // conservatively treats the owner as moved on either failure edge
        // until path-sensitive ownership effects are implemented.
        rejects(header + """
                fnc bad(bool enabled, Box box): void {
                  if enabled && (consume(box) as const found) then
                    return;
                  else
                    consume(box);
                  fi
                  return;
                }
                """, "moved");

        // An independent owner remains usable in the else path.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(header + """
                fnc good(Box first, Box second): void {
                  if consume(first) as let value then
                    return;
                  else
                    consume(second);
                  fi
                  return;
                }
                """)));
    }

    @Test
    void shorthandComparisonRhsUsesOuterOwnershipScopeNotConditionalBinder() {
        String prelude = """
                define class Box as
                end

                fnc consume(Box box): int {
                  return 15;
                }
                """;

        // Both TypeChecker and the interpreter resolve the comparison RHS
        // against the outer Box, while the successful binding is Copy int.
        // Ownership checking must also consume the outer Box here.
        rejects(prelude + """
                fnc bad(Box value, Option<int> maybe): void {
                  if (maybe as const value) eq consume(value) then
                    return;
                  fi
                  consume(value);
                  return;
                }
                """, "moved");

        // The negated comparison and mutable spelling have the same
        // surrounding-scope RHS resolution and must not mask that move.
        rejects(prelude + """
                fnc bad(Box value, Option<int> maybe): void {
                  if (maybe as let value) neq consume(value) then
                    return;
                  fi
                  consume(value);
                  return;
                }
                """, "moved");

        // The RHS may consume an outer owner exactly once.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(prelude + """
                fnc good(Box value, Option<int> maybe): void {
                  if (maybe as const value) eq consume(value) then
                    return;
                  fi
                  return;
                }
                """)));

        // When the user intends to compare the new binding, && explicitly
        // carries it into the RHS success scope instead.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc local(Option<int> maybe): bool {
                  if maybe as const value && value eq 15 then
                    return true;
                  fi
                  return false;
                }
                """)));
    }

    @Test
    void shorthandComparisonAndAndGuardHaveDistinctShadowingScopesAtRuntime() throws Exception {
        String program = """
                pub fnc main(): void {
                  val int value = 3;
                  val Option<int> maybe = Some(15);
                  if (maybe as const value) eq value then
                    stdio.println("unexpected-inner");
                  else
                    stdio.println("outer");
                  fi
                  if maybe as const value && value eq 15 then
                    stdio.println("inner");
                  fi
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals(List.of("outer", "inner"), run(program));
    }

    @Test
    void scopeMutabilityAndCarrierTypeFailClosed() {
        rejects("""
                fnc bad(Option<int> maybe): void {
                  if maybe as const value then
                    value = 2;
                  fi
                }
                """, "cannot reassign");

        rejects("""
                fnc bad(Option<int> maybe): int {
                  if maybe as let value then
                    return value;
                  else
                    return value;
                  fi
                }
                """, "unknown name");

        rejects("""
                fnc bad(int number): bool {
                  if number as const value then
                    return true;
                  else
                    return false;
                  fi
                }
                """, "requires Option");

        rejects("""
                define class Box as
                end
                fnc bad(Option<Box> maybe): bool {
                  if maybe as let value then
                    return true;
                  else
                    return false;
                  fi
                }
                """, "non-Copy");

        rejects("""
                fnc bad(Option<int> maybe): int {
                  val int number = maybe as const value;
                  return number;
                }
                """, "only valid in an if predicate");
    }

    @Test
    void optionalProducerIsEvaluatedOnceEvenWhenGuardFails() throws Exception {
        String program = """
                fnc produce(Option<int> value): Option<int> {
                  stdio.println("called");
                  return value;
                }

                pub fnc main(): void {
                  if produce(Some(9)) as const n && n eq 15 then
                    stdio.println("unexpected");
                  else
                    stdio.println("false");
                  fi

                  if (produce(None) as const n) eq 15 then
                    stdio.println("unexpected");
                  else
                    stdio.println("none");
                  fi
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals(List.of("called", "false", "called", "none"), run(program));
    }

    @Test
    void bindingsOutsideIfAreNotAdmittedUntilFlowLoweringExists() {
        rejects("""
                fnc bad(Option<int> source): int {
                  return (source as const value) eq 15 ? 1 : 0;
                }
                """, "only supported in if");

        rejects("""
                fnc bad(Option<int> source): void {
                  for (; source as let value; ) {
                    return;
                  }
                }
                """, "only supported in if");
    }

    @Test
    void duplicateConditionalNamesAreRejectedEvenForIdenticalCopyValues() {
        rejects("""
                fnc invalid(Option<int> a, Option<int> b): int {
                  if a as const value && b as let value then
                    return value;
                  else
                    return 0;
                  fi
                }
                """, "duplicate binding");
    }

    @Test
    void shortCircuitSkipsSideEffectingGuardOnNone() throws Exception {
        String program = """
                fnc inspect(int candidate): bool {
                  stdio.println("rhs");
                  return candidate eq 15;
                }

                pub fnc main(): void {
                  if None as const value && inspect(value) then
                    stdio.println("unexpected");
                  else
                    stdio.println("missing");
                  fi
                  if Some(15) as const value && inspect(value) then
                    stdio.println("present");
                  fi
                  return;
                }
                """;
        // A type-inferred None lacks a concrete T; annotate it through a
        // declared Option<int> to keep the Copy payload proof explicit.
        String typed = program.replace("if None as const value", "if missing as const value")
                .replace("pub fnc main(): void {",
                        "pub fnc main(): void {\n  val Option<int> missing = None;");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(typed)));
        assertEquals(List.of("missing", "rhs", "present"), run(typed));
    }

    @Test
    void guardsDoNotLeakBindingsAcrossShortCircuitOrOuterScope() {
        rejects("""
                fnc bad(Option<int> maybe): int {
                  if maybe as let value && value eq 15 then
                    return value;
                  fi
                  return value;
                }
                """, "unknown name");

        rejects("""
                fnc bad(Option<int> maybe): bool {
                  if maybe then
                    return true;
                  else
                    return false;
                  fi
                }
                """, "expected");
    }

    private static void rejects(String source, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static List<String> run(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "conditional-option-binding.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
    }
}
