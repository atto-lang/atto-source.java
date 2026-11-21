package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DirectionalChannelViewLanguageTest {
    @Test
    void channelInputAndOutputShareOneExecutableChannel() throws Exception {
        String source = """
                pub routine main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  val WriteChannel<int> input = channel.input;
                  val ReadChannel<int> output = channel.output;
                  writech input, 23;
                  val int value = readch output;
                  stdio.stdout.write(value);
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("23", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void directionIsEnforcedBeforeRuntime() {
        IllegalArgumentException write = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                          val Channel<int> channel = Channel.new<int>(1);
                          writech channel.output, 1;
                          return;
                        }
                        """));
        assertTrue(write.getMessage().contains("WriteChannel"), write::getMessage);

        IllegalArgumentException read = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                          val Channel<int> channel = Channel.new<int>(1);
                          val int value = readch channel.input;
                          return;
                        }
                        """));
        assertTrue(read.getMessage().contains("ReadChannel"), read::getMessage);
    }

    @Test
    void selectCasePreservesDirectionalViews() throws Exception {
        String source = """
                pub routine main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  writech channel.input, 31;
                  val SelectSet cases = SelectSet.new([
                    SelectCase.read(channel.output)
                  ]);
                  val Option<SelectResult> choice = select first from cases;
                  val SelectResult selected = choice.unwrap();
                  stdio.stdout.write(selected.value);
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("31", SelectNestedReturnContractTest.run(source));

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                          val Channel<int> channel = Channel.new<int>(1);
                          val bad = SelectCase.read(channel.input);
                          return;
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                          val Channel<int> channel = Channel.new<int>(1);
                          val bad = SelectCase.write(channel.output, 1);
                          return;
                        }
                        """));
    }
}
