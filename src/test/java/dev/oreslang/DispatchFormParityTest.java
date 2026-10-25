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
    void nbMatchAndNbSwitchFailClosedInsteadOfMeaningAsyncArms() {
        var nbMatch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc f(int x): int {
                return nb match<int> x over
                    on 1 -> { return 1; }
                    on _ -> { return 0; }
                end;
            }
            """));
        assertTrue(nbMatch.getMessage().contains("'nb match' and 'nb switch' are invalid"));

        var nbSwitch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc f(int x): int {
                return nb switch x
                    case 1 -> { return 1; }
                    default -> { return 0; }
                end;
            }
            """));
        assertTrue(nbSwitch.getMessage().contains("'nb match' and 'nb switch' are invalid"));
    }

    @Test
    void valueSwitchStillFailsClosedRatherThanMasqueradingAsStatementSwitch() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc f(int x): int {
                return switch x
                    case 1 -> { return 1; }
                    default -> { return 0; }
                end;
            }
            """));
    }

    @Test
    void valueMatchAndProceduralDoSwitchCanBothSuspendInAsyncCallable() throws Exception {
        String program = """
            async fnc viaMatch(): int {
              val Channel<int> values = Channel.new<int>(0);
              val writer = nb writech values, 41;
              val int result = match<int> true over
                on true -> { return (await (nb readch values)) + 1; }
                on false -> { return 0; }
              end;
              await writer;
              return result;
            }

            async fnc viaDoMatch(): int {
              val Channel<int> values = Channel.new<int>(0);
              val writer = nb writech values, 41;
              do match true over
                on true -> {
                  val int result = await (nb readch values);
                  await writer;
                  return result + 1;
                }
                on false -> { return 0; }
              end
            }

            async fnc viaSwitch(): int {
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
              stdio.println(await viaMatch());
              stdio.println(await viaDoMatch());
              stdio.println(await viaSwitch());
              return;
            }
            """;

        var typed = TypeChecker.check(Parser.parse(program));
        OwnershipChecker.check(typed);

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

        assertEquals(
                "42\n42\n42",
                output.toString(StandardCharsets.UTF_8)
                        .trim()
                        .replace("\r", ""));
    }
}
