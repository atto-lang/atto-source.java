package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
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
    void parsesCountedLoopsWithoutChangingOrdinaryForSemantics() {
        Ast.FunctionDecl fn = (Ast.FunctionDecl) Parser.parse("""
                fnc count(): void {
                  rt cooperative for let i = 0; i < 5; i++ { }
                  for let j = 0; j < 5; j++ { }
                  return;
                }
                """).modules().getFirst().declarations().getFirst();
        assertTrue(((Ast.ForStmt) fn.body().get(0)).cooperative());
        assertFalse(((Ast.ForStmt) fn.body().get(1)).cooperative());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  rt cooperative for const item of arr[1, 2, 3] { }
                  return;
                }
                """));
    }

    @Test
    void largeBatchedLoopPreservesUpdateContinueBreakAndOutput() throws Exception {
        assertEquals("49994500|10000|7", run("""
                pub async fnc main(): void {
                  let sum = 0;
                  let count = 0;
                  rt cooperative for let i = 0; i < 10000; i++ {
                    count = count + 1;
                    if i == 500; do
                      continue;
                    fi
                    sum = sum + i;
                  }
                  rt cooperative for let j = 0; j < 20; j++ {
                    if j == 7; do
                      break;
                    fi
                  }
                  stdio.stdout.write(sum);
                  stdio.stdout.write("|");
                  stdio.stdout.write(count);
                  stdio.stdout.write("|");
                  stdio.stdout.write(7);
                  return;
                }
                """));
    }

    @Test
    void cooperativeLibraryMapFilterReducePreserveOrder() throws Exception {
        // Imported module namespaces cannot yet resolve explicit generic call
        // markers; the exported library declarations remain generic.
        Files.copy(Path.of("stdlib/cooperative.ores"), temp.resolve("cooperative.ores"));
        assertEquals("6|8|2|14", run("""
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
                  val mapped = cooperative.map(values, doubleIt);
                  val filtered = cooperative.filter(mapped, atLeastSix);
                  // filter has consumed mapped; reduce will consume filtered.
                  stdio.stdout.write(filtered.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(filtered.get(1));
                  stdio.stdout.write("|");
                  stdio.stdout.write(filtered.size);
                  val reduced = cooperative.reduce(filtered, 0, sum);
                  stdio.stdout.write("|");
                  stdio.stdout.write(reduced);
                  return;
                }
                """));
    }

    @Test
    void largeSequentialReduceAndFilterReuseSameSourceTaskQuantum() throws Exception {
        Files.copy(Path.of("stdlib/cooperative.ores"), temp.resolve("cooperative.ores"));
        String output = run("""
                import module cooperative from './cooperative';
                pub async fnc main(): void {
                  let mut Array<int> values = new Array<int>();
                  for (let i = 0; i < 2000; i = i + 1) do
                    values.add(1);
                  done
                  val Fnc<int, int, int> sum = |int a, int b| -> { return a + b; };
                  val Fnc<int, bool> keep = |int x| -> { return x == 1; };
                  val filtered = cooperative.filter(values, keep);
                  val filteredCount = filtered.size;
                  val result = cooperative.reduce(filtered, 0, sum);
                  stdio.stdout.write(result);
                  stdio.stdout.write("|");
                  stdio.stdout.write(filteredCount);
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("2000|2000|"), output);
        long handoffs = Long.parseLong(output.substring("2000|2000|".length()));
        assertTrue(handoffs > 0, "collection operators should share real scheduler handoffs");
    }

    @Test
    void scanAnyAllAndFlatMapRunInStableOrder() throws Exception {
        Files.copy(Path.of("stdlib/cooperative.ores"), temp.resolve("cooperative.ores"));
        assertEquals("10|true|true|8", run("""
                import module cooperative from './cooperative';
                pub async fnc main(): void {
                  // Collection operators transfer owned inputs into
                  // continuation-backed library tasks.
                  let mut Array<int> values = new Array<int>();
                  let mut Array<int> anyValues = new Array<int>();
                  let mut Array<int> allValues = new Array<int>();
                  let mut Array<int> flatValues = new Array<int>();
                  for (let i = 1; i < 5; i = i + 1) do
                    values.add(i);
                    anyValues.add(i);
                    allValues.add(i);
                    flatValues.add(i);
                  done
                  val Fnc<int, int, int> sum = |int a, int b| -> { return a + b; };
                  val Fnc<int, bool> positive = |int x| -> { return x > 0; };
                  val Fnc<int, bool> greaterThanThree = |int x| -> { return x > 3; };
                  val Fnc<int, Array<int>> two = |int x| -> {
                    let mut Array<int> pair = new Array<int>();
                    pair.add(x);
                    pair.add(x);
                    return pair;
                  };
                  val scanned = cooperative.scan(values, 0, sum);
                  val anything = cooperative.any(anyValues, greaterThanThree);
                  val everything = cooperative.all(allValues, positive);
                  val flat = cooperative.flatMap(flatValues, two);
                  stdio.stdout.write(scanned.get(3));
                  stdio.stdout.write("|");
                  stdio.stdout.write(anything);
                  stdio.stdout.write("|");
                  stdio.stdout.write(everything);
                  stdio.stdout.write("|");
                  stdio.stdout.write(flat.size);
                  return;
                }
                """));
    }

    @Test
    void untrustedCodeCannotBypassContinuationQuotaGuardThroughLoop() {
        var program = TypeChecker.check(Parser.parse("""
                define module app as
                  fnc work(): void {
                    rt cooperative for let i = 0; i < 200; i++ { }
                    return;
                  }
                  pub untrusted actor fnc worker(): void {
                    work();
                    return;
                  }
                end
                """));
        SecurityException denied = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("rt cooperative for"), denied.getMessage());
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
