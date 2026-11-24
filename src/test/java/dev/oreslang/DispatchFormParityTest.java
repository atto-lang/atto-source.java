package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class DispatchFormParityTest {

    private static void check(String program) {
        var typed = TypeChecker.check(Parser.parse(program));
        OwnershipChecker.check(typed);
    }

    private static String run(String program) throws Exception {
        check(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "dispatch-form-parity.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8)
                .trim()
                .replace("\r", "");
    }

    @Test
    void doSwitchAndLegacySwitchRetainDistinctAstProvenance() {
        var program = Parser.parse("""
            fnc legacy(int x): int {
                switch x
                    case 1 -> { return 1; }
                    default -> { return 2; }
                end
            }
            fnc canonical(int x): int {
                do switch x
                    case 1 -> { return 1; }
                    default -> { return 2; }
                end
            }
            """);

        var declarations = program.modules().getFirst().declarations();
        var legacy = (Ast.FunctionDecl) declarations.get(0);
        var canonical = (Ast.FunctionDecl) declarations.get(1);
        var legacySwitch = (Ast.SwitchStmt) legacy.body().getFirst();
        var canonicalSwitch = (Ast.SwitchStmt) canonical.body().getFirst();

        assertFalse(legacySwitch.procedural());
        assertTrue(canonicalSwitch.procedural());
    }

    @Test
    void valueMatchAndSwitchProduceValuesAndDoMatchJoins() throws Exception {
        String program = """
            fnc viaMatch(int x): int {
              return match<int> x over
                on 1 -> { 10; }
                on _ -> { 20; }
              end;
            }

            fnc viaSwitch(int x): int {
              return switch x
                case 1 -> { 30; }
                default -> { 40; }
              end;
            }

            fnc viaDoMatch(int x): int {
              do match x over
                on 1 -> { ^ void; }
                on _ -> { return void; }
              end
              return 50;
            }

            fnc viaDoSwitch(int x): int {
              do switch x
                case 1 -> { return 70; }
                default -> { return 80; }
              end
            }

            pub routine main(): void {
              stdio.println(viaMatch(1));
              stdio.println(viaSwitch(1));
              stdio.println(viaDoMatch(1));
              stdio.println(viaDoSwitch(1));
              return;
            }
            """;

        assertEquals("10\n30\n50\n70", run(program));
    }

    @Test
    void doMatchCleansUpBeforeJoiningAndRejectsNonVoidArmResults() throws Exception {
        assertEquals("arm:cleanup:42", run("""
            pub routine main(): void {
              do match true over
                on true -> {
                  defer stdio.stdout.write("cleanup:");
                  stdio.stdout.write("arm:");
                  ^ void;
                }
                on false -> {}
              end
              stdio.stdout.write(42);
              return;
            }
            """));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            fnc invalid(): int {
              do match true over
                on true -> { return 42; }
                on false -> {}
              end
              return 1;
            }
            """)));
    }

    @Test
    void legacyReturnSpellingInsideValueArmsIsArmLocalNotOuterReturn() throws Exception {
        String program = """
            fnc viaMatch(): int {
              val int value = match<int> true over
                on true -> { return 41; }
                on false -> { return 0; }
              end;
              return value + 1;
            }

            fnc viaSwitch(): int {
              val int value = switch 1
                case 1 -> { return 41; }
                default -> { return 0; }
              end;
              return value + 1;
            }

            pub routine main(): void {
              stdio.println(viaMatch());
              stdio.println(viaSwitch());
              return;
            }
            """;

        assertEquals("42\n42", run(program));
    }

    @Test
    void allFourFormsMaySuspendOnlyInSuspendingCallable() throws Exception {
        String program = """
            async fnc valueMatch(): int {
              val Channel<int> values = Channel.new<int>(0);
              val writer = nb writech values, 41;
              val int result = match<int> true over
                on true -> { (await (nb readch values)) + 1; }
                on false -> { 0; }
              end;
              await writer;
              return result;
            }

            async fnc valueSwitch(): int {
              val Channel<int> values = Channel.new<int>(0);
              val writer = nb writech values, 41;
              val int result = switch 7
                case 7 -> { (await (nb readch values)) + 1; }
                default -> { 0; }
              end;
              await writer;
              return result;
            }

            async fnc doMatch(): int {
              val Channel<int> values = Channel.new<int>(0);
              val writer = nb writech values, 41;
              let int result = 0;
              do match true over
                on true -> {
                  result = await (nb readch values);
                  await writer;
                  return;
                }
                on false -> { ^ void; }
              end
              return result + 1;
            }

            async fnc doSwitch(): int {
              val Channel<int> values = Channel.new<int>(0);
              val writer = nb writech values, 41;
              do switch 7
                case 7 -> {
                  val int result = await (nb readch values);
                  await writer;
                  return result + 1;
                }
                default -> { return 0; }
              end
            }

            pub async routine main(): void {
              stdio.println(await valueMatch());
              stdio.println(await valueSwitch());
              stdio.println(await doMatch());
              stdio.println(await doSwitch());
              return;
            }
            """;

        assertEquals("42\n42\n42\n42", run(program));
    }

    @Test
    void awaitInDispatchOutsideSuspendingCallableIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            async fnc ready(): int { return 1; }
            fnc bad(): int {
              return switch 1
                case 1 -> { await ready(); }
                default -> { 0; }
              end;
            }
            """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            async fnc ready(): int { return 1; }
            fnc bad(): int {
              return match<int> true over
                on true -> { await ready(); }
                on false -> { 0; }
              end;
            }
            """)));
    }

    @Test
    void unselectedValueArmsNeitherRunSideEffectsNorSuspend() throws Exception {
        String program = """
            async fnc viaMatch(): int {
              val Channel<int> blocked = Channel.new<int>(0);
              return match<int> true over
                on true -> { 42; }
                on false -> { await (nb readch blocked); }
              end;
            }

            fnc failIfSelected(): int {
              rt fail("unselected switch arm executed");
              return 0;
            }

            async fnc viaSwitch(): int {
              val Channel<int> blocked = Channel.new<int>(0);
              return switch 1
                case 1 -> { 42; }
                case 2 -> { failIfSelected(); }
                default -> { await (nb readch blocked); }
              end;
            }

            pub async routine main(): void {
              stdio.println(await viaMatch());
              stdio.println(await viaSwitch());
              return;
            }
            """;

        assertEquals("42\n42", run(program));
    }

    @Test
    void switchSubjectsAreEvaluatedExactlyOnce() throws Exception {
        String program = """
            fnc viaValueSwitch(): int {
              let int n = 0;
              val int result = switch (n = n + 1)
                case 0 -> { 100; }
                case 1 -> { 20; }
                case 2 -> { 300; }
                default -> { 400; }
              end;
              return result + n;
            }

            fnc viaDoSwitch(): int {
              let int n = 0;
              do switch (n = n + 1)
                case 0 -> { return 100 + n; }
                case 1 -> { return 20 + n; }
                case 2 -> { return 300 + n; }
                default -> { return 400 + n; }
              end
            }

            pub routine main(): void {
              stdio.println(viaValueSwitch());
              stdio.println(viaDoSwitch());
              return;
            }
            """;

        assertEquals("21\n21", run(program));
    }

    @Test
    void incompatibleValueArmTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            fnc bad(): int {
              return match true over
                on true -> { 1; }
                on false -> { "wrong"; }
              end;
            }
            """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            fnc bad(): int {
              return switch 1
                case 1 -> { 1; }
                default -> { "wrong"; }
              end;
            }
            """)));
    }

    @Test
    void expressionDispatchMustBeExhaustive() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            fnc bad(bool x): int {
              return match<int> x over
                on true -> { 1; }
              end;
            }
            """)));

        var error = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc bad(int x): int {
              return switch x
                case 1 -> { 1; }
              end;
            }
            """));
        assertTrue(error.getMessage().contains("value switch requires a default arm"));
    }

    @Test
    void valueSwitchRejectsNonConstantDuplicateAndIncompatibleCases() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            fnc id(int x): int { return x; }
            fnc bad(int x): int {
              return switch x
                case id(1) -> { 1; }
                default -> { 0; }
              end;
            }
            """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            fnc bad(int x): int {
              return switch x
                case 1 -> { 1; }
                case 1 -> { 2; }
                default -> { 0; }
              end;
            }
            """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            fnc bad(int x): int {
              return switch x
                case "one" -> { 1; }
                default -> { 0; }
              end;
            }
            """)));

        var foldedValueDuplicate = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc bad(int x): int {
                      return switch x
                        case 1 + 1 -> { 1; }
                        case 2 -> { 2; }
                        default -> { 0; }
                      end;
                    }
                    """)));
        assertTrue(foldedValueDuplicate.getMessage().contains("duplicate value switch case"));

        var foldedStatementDuplicate = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc bad(int x): int {
                      switch x
                        case 1 + 1 -> { return 1; }
                        case 2 -> { return 2; }
                        default -> { return 0; }
                      end
                    }
                    """)));
        assertTrue(foldedStatementDuplicate.getMessage().contains("duplicate switch case"));

    }

    @Test
    void liveBorrowAcrossAwaitInsideDispatchIsRejected() {
        String program = """
            define class Box as
              pub let int value = 7;
            end

            async fnc bad(): void {
              val Box box = new Box();
              val borrow Box view = rt borrow box;
              val Channel<int> ch = Channel.new<int>(1);
              val pending = nb readch ch;
              do match true over
                on true -> {
                  await pending;
                  stdio.println(view.value);
                  return;
                }
                on false -> { return; }
              end
            }
            """;

        assertThrows(IllegalArgumentException.class, () -> {
            var typed = TypeChecker.check(Parser.parse(program));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void nbMatchAndNbSwitchFailClosedInsteadOfMeaningAsyncArms() {
        var nbMatch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc f(int x): int {
                return nb match<int> x over
                    on 1 -> { 1; }
                    on _ -> { 0; }
                end;
            }
            """));
        assertTrue(nbMatch.getMessage().contains("'nb match' and 'nb switch' are invalid"));

        var nbSwitch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc f(int x): int {
                return nb switch x
                    case 1 -> { 1; }
                    default -> { 0; }
                end;
            }
            """));
        assertTrue(nbSwitch.getMessage().contains("'nb match' and 'nb switch' are invalid"));
    }
}
