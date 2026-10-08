package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(25)
final class CooperativeForLanguageTest {
    @TempDir Path temp;

    @Test
    void ordinaryCountedLoopRequiresNoSchedulingKeyword() {
        Ast.FunctionDecl fn = (Ast.FunctionDecl) Parser.parse("""
                fnc count(): void {
                  for let j = 0; j < 5; j++ { }
                  return;
                }
                """).modules().getFirst().declarations().getFirst();
        assertInstanceOf(Ast.ForStmt.class, fn.body().getFirst());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc invalid(): void {
                  rt cooperative for let j = 0; j < 5; j++ { }
                  return;
                }
                """));
    }

    @Test
    void largePlainLoopCooperatesAndKeepsEverySideEffectExactlyOnce() throws Exception {
        String output = run("""
                pub async fnc main(): void {
                  let sum = 0;
                  let count = 0;
                  for let i = 0; i < 10000; i++ {
                    count = count + 1;
                    if i == 500; do
                      continue;
                    fi
                    sum = sum + i;
                  }
                  stdio.stdout.write(sum);
                  stdio.stdout.write("|");
                  stdio.stdout.write(count);
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("49994500|10000|"), output);
        assertTrue(Long.parseLong(output.substring("49994500|10000|".length())) > 0,
                "regular loops in source tasks must cooperate automatically");
    }

    @Test
    void normalLoopBreakAndContinueKeepUpdateSemantics() throws Exception {
        assertEquals("18", run("""
                pub async fnc main(): void {
                  let result = 0;
                  for let j = 0; j < 100; j++ {
                    if j == 3; do continue; fi
                    if j == 7; do break; fi
                    result = result + 1;
                  }
                  stdio.stdout.write(result + 12);
                  return;
                }
                """));
    }

    @Test
    void libraryCollectionOperatorsUseOrdinaryForAndPreserveOrder() throws Exception {
        Files.copy(Path.of("stdlib/cooperative.ores"), temp.resolve("cooperative.ores"));
        assertEquals("6|8|2|20", run("""
                import module cooperative from './cooperative';
                pub async fnc main(): void {
                  let mut Array<int> values = new Array<int>();
                  values.add(1);
                  values.add(2);
                  values.add(3);
                  values.add(4);
                  val Fnc<int, int> doubleIt = |int x| -> { return x * 2; };
                  val Fnc<int, bool> atLeastSix = |int x| -> { return x >= 6; };
                  val Fnc<int, int, int> sum = |int a, int b| -> { return a + b; };
                  val mapped = cooperative.map<int, int>(values, doubleIt);
                  val filtered = cooperative.filter<int>(mapped, atLeastSix);
                  val reduced = cooperative.reduce<int, int>(mapped, 0, sum);
                  stdio.stdout.write(filtered.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(filtered.get(1));
                  stdio.stdout.write("|");
                  stdio.stdout.write(filtered.size);
                  stdio.stdout.write("|");
                  stdio.stdout.write(reduced);
                  return;
                }
                """));
    }

    @Test
    void ordinaryLoopsRemainStaticallyAdmissibleForUntrustedActors() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  pub untrusted actor fnc worker(): void {
                    for let i = 0; i < 100; i++ { }
                    return;
                  }
                end
                """)));
    }

    private String run(String source) throws Exception {
        Path file = temp.resolve("main.ores");
        Files.writeString(file, source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                file, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), out, new ByteArrayOutputStream());
        return out.toString(StandardCharsets.UTF_8).strip();
    }
}
