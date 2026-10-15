package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SelectAwaitFutureLanguageTest {
    @Test
    void pendingChannelAndCompletedFutureShareOneSourceLevelSelect() throws Exception {
        String source = """
                async fnc compute(): int { return 4; }
                pub routine main(): void {
                  val Channel<int> no_messages = Channel.new<int>(1);
                  val Future<int> future = compute();
                  do select first {
                    when readch no_messages: int value -> {
                      stdio.stdout.write("channel:");
                      stdio.stdout.write(value);
                    }
                    when await future: int result -> {
                      stdio.stdout.write("future:");
                      stdio.stdout.write(result);
                    }
                  }
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("future:4", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void readyChannelWinsPriorityAndFutureRemainsAwaitable() throws Exception {
        String source = """
                async fnc compute(): int { return 9; }
                pub routine main(): void {
                  val Channel<int> inbox = Channel.new<int>(1);
                  writech inbox, 7;
                  val Future<int> future = compute();
                  do select first {
                    when readch inbox: const int value -> {
                      stdio.stdout.write(value);
                    }
                    when await future: const int value -> {
                      stdio.stdout.write("bad");
                    }
                  }
                  stdio.stdout.write(":");
                  stdio.stdout.write(await future);
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("7:9", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void typedFutureBindingIsCheckedBeforeAnyArmCommits() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async fnc compute(): int { return 4; }
                pub routine main(): void {
                  do select {
                    when await compute(): string bad -> { return; }
                  }
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                  do select {
                    when await 42: int bad -> { return; }
                  }
                  return;
                }
                """));
    }

    @Test
    void dynamicSelectCaseFutureSharesResultAndWaiterProtocol() throws Exception {
        String source = """
                async fnc compute(): int { return 17; }
                pub routine main(): void {
                  val Channel<int> empty = Channel.new<int>(1);
                  val Future<int> ready = compute();
                  val SelectSet cases = SelectSet.new([
                    SelectCase.read(empty),
                    SelectCase.future(ready)
                  ]);
                  val Option<SelectResult> choice = select first from cases;
                  val SelectResult selected = choice.unwrap();
                  stdio.stdout.write(selected.index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(selected.value);
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("1:17", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void dynamicFutureCaseRejectsNonFutureAtTypecheck() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                  val bad = SelectCase.future(22);
                  return;
                }
                """));
    }

    @Test
    void legacyUnannotatedReadArmStaysSupported() throws Exception {
        String source = """
                pub routine main(): void {
                  val Channel<int> inbox = Channel.new<int>(1);
                  writech inbox, 22;
                  do select {
                    when readch inbox: const value {
                      stdio.stdout.write(value);
                    }
                  }
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("22", SelectNestedReturnContractTest.run(source));
    }
}
