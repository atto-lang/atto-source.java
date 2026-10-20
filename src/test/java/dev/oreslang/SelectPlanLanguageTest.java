package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SelectPlanLanguageTest {
    @Test
    void immutableAndMutablePlansRemainReusableAcrossSelectIterations() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> a = Channel.new<int>(3);
                  val Channel<int> b = Channel.new<int>(3);
                  val SelectPlan fixed = SelectPlan.new([SelectCase.read(a)]);
                  writech a, 11;
                  stdio.stdout.write((select first from fixed).unwrap().value);
                  writech a, 12;
                  stdio.stdout.write(":");
                  stdio.stdout.write((select first from fixed).unwrap().value);

                  val DynamicSelectPlan dynamic = DynamicSelectPlan.new();
                  dynamic.put("a", SelectCase.read(a));
                  dynamic.put("b", SelectCase.read(b));
                  writech b, 21;
                  stdio.stdout.write(":");
                  stdio.stdout.write((select first from dynamic).unwrap().value);
                  dynamic.remove("a");
                  writech b, 22;
                  stdio.stdout.write(":");
                  stdio.stdout.write((select first from dynamic).unwrap().value);
                  dynamic.put("b", SelectCase.read(a));
                  writech a, 23;
                  stdio.stdout.write(":");
                  stdio.stdout.write((select first from dynamic).unwrap().value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("11:12:21:22:23", run(program));
    }

    @Test
    void nonblockingAndImmediatePlansExposeConsistentMutableState() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  val DynamicSelectPlan plan = DynamicSelectPlan.new();
                  plan.put("incoming", SelectCase.read(incoming));
                  val Option<SelectResult> missed = try select first from plan;
                  stdio.stdout.write(missed.is_some());
                  writech incoming, 31;
                  val Future<Option<SelectResult>> pending = nb select first from plan;
                  val Option<SelectResult> got = await pending;
                  stdio.stdout.write(":");
                  stdio.stdout.write(got.unwrap().value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(plan.size());
                  stdio.stdout.write(":");
                  stdio.stdout.write(plan.version());
                  plan.remove("incoming");
                  stdio.stdout.write(":");
                  stdio.stdout.write(plan.size());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("false:31:1:1:0", run(program));
    }

    @Test
    void refusesIncorrectDynamicMutationArgumentsAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                  val DynamicSelectPlan plan = DynamicSelectPlan.new();
                  plan.put(123, 456);
                }
                """));
    }

    @Test
    void rejectsNonCaseInputsBeforeRuntime() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                  val SelectSet invalid = SelectSet.new(42);
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                  val SelectPlan invalid = SelectPlan.new([1, 2]);
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                  val Array<int> numbers = [1, 2];
                  val Option<SelectResult> invalid = try select from numbers;
                  return;
                }
                """));
    }

    @Test
    void inlineStaticSelectReusesSafeChannelOperandsAcrossForOfAndLoop() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> inbox = Channel.new<int>(4);
                  for val number of [10, 20, 30] {
                    writech inbox, number;
                    select first {
                      when readch inbox: val message {
                        stdio.stdout.write(message);
                        stdio.stdout.write(":");
                      }
                    }
                  }
                  let int remaining = 2;
                  loop {
                    writech inbox, remaining;
                    try select first {
                      when readch inbox: val message {
                        stdio.stdout.write(message);
                        stdio.stdout.write(":");
                      }
                    }
                    remaining = remaining - 1;
                    if remaining == 0 then break; fi
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("10:20:30:2:1:", run(program));
    }

    @Test
    void perIterationShadowChannelIsNotHoistedAcrossIterations() throws Exception {
        String program = """
                pub fnc main(): void {
                  for val number of [1, 2, 3] {
                    val Channel<int> inbox = Channel.new<int>(1);
                    writech inbox, number;
                    select first {
                      when readch inbox: val message {
                        stdio.stdout.write(message);
                      }
                    }
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("123", run(program));
    }

    @Test
    void mutableChannelBindingIsReevaluatedOnEveryLoopIteration() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val Channel<int> b = Channel.new<int>(1);
                  writech a, 11;
                  writech b, 22;
                  let Channel<int> chosen = a;
                  for val turn of [1, 2] {
                    if turn == 2 then chosen = b; fi
                    select first {
                      when readch chosen: val message {
                        stdio.stdout.write(message);
                        stdio.stdout.write(":");
                      }
                      default: {
                        stdio.stdout.write("miss:");
                      }
                    }
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("11:22:", run(program),
                "hoisting a mutable channel binding would wrongly read from a");
    }

    @Test
    void hoistedLoopDescriptorCannotEscapeIntoAnotherFunctionInvocation() throws Exception {
        String program = """
                pub fnc drain(Channel<int> source): void {
                  for val turn of [1] {
                    select first {
                      when readch source: val message {
                        stdio.stdout.write(message);
                      }
                      default: {
                        stdio.stdout.write("miss");
                      }
                    }
                  }
                  return;
                }

                pub fnc main(): void {
                  val Channel<int> a = Channel.new<int>(1);
                  val Channel<int> b = Channel.new<int>(1);
                  writech a, 3;
                  writech b, 4;
                  drain(a);
                  drain(b);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("34", run(program),
                "loop plan must be scoped to this invocation's Env");
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "select-plan.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
