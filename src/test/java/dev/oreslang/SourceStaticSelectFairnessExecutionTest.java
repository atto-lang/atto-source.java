package dev.oreslang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SourceStaticSelectFairnessExecutionTest {
    @Test
    void defaultDoesNotConsumeFairnessCreditAcrossFreshStaticSelectSets() throws Exception {
        String source = """
                fnc route(Channel<int> a, Channel<int> b): void {
                  do select {
                    case readch a: val value {
                      stdio.stdout.write("A");
                      stdio.stdout.write(value);
                      stdio.stdout.write("|");
                    }
                    case readch b: val value {
                      stdio.stdout.write("B");
                      stdio.stdout.write(value);
                      stdio.stdout.write("|");
                    }
                    default: {
                      stdio.stdout.write("D|");
                    }
                  }
                  return;
                }

                pub fnc main(): void {
                  val Channel<int> a = Channel.new<int>(2);
                  val Channel<int> b = Channel.new<int>(2);
                  route(a, b);
                  writech a, 10;
                  writech b, 20;
                  route(a, b);
                  writech a, 30;
                  route(a, b);
                  return;
                }
                """;
        SelectNestedReturnContractTest.assertWarnings(source, 0);
        assertEquals("D|A10|B20|", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void consecutiveCommittedSelectionsRotateOnlyAtActualCommit() throws Exception {
        String source = """
                fnc route(Channel<int> a, Channel<int> b): void {
                  do select {
                    case readch a: val value {
                      stdio.stdout.write("A");
                      stdio.stdout.write(value);
                      stdio.stdout.write("|");
                    }
                    case readch b: val value {
                      stdio.stdout.write("B");
                      stdio.stdout.write(value);
                      stdio.stdout.write("|");
                    }
                  }
                  return;
                }

                pub fnc main(): void {
                  val Channel<int> a = Channel.new<int>(3);
                  val Channel<int> b = Channel.new<int>(3);
                  writech a, 1;
                  writech a, 2;
                  writech b, 3;
                  writech b, 4;
                  route(a, b);
                  route(a, b);
                  route(a, b);
                  route(a, b);
                  return;
                }
                """;
        SelectNestedReturnContractTest.assertWarnings(source, 0);
        assertEquals("A1|B3|A2|B4|", SelectNestedReturnContractTest.run(source));
    }
}
