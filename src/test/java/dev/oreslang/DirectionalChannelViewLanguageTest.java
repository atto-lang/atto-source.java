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
    void asyncRendezvousKeepsDirectionalViewsAcrossSuspension() throws Exception {
        String source = """
                pub async routine main(): void {
                  val Channel<int> channel = Channel.new<int>(0);
                  val WriteChannel<int> writer = channel.input;
                  val ReadChannel<int> reader = channel.output;
                  val Future<void> pending = nb writech writer, 53;
                  val int received = readch reader;
                  await pending;
                  stdio.stdout.write(received);
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("53", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void staticSelectOnDirectionalViewsUsesTheOriginalChannel() throws Exception {
        String source = """
                pub routine main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  writech channel.input, 41;
                  select first {
                    case readch channel.output: val received {
                      stdio.stdout.write(received);
                    }
                  }
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("41", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void immediateProbesRemainDirectional() throws Exception {
        String source = """
                pub routine main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  val bool wrote = try writech channel.input, 17;
                  val Option<int> read = try readch channel.output;
                  stdio.stdout.write(read.unwrap());
                  return;
                }
                """;
        OresCompiler.parseAndTypeCheck(source);
        assertEquals("17", SelectNestedReturnContractTest.run(source));
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
