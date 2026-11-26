package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Restricted executable typed-default and single-channel counted selection. */
@Timeout(20)
final class TypedDefaultSelectGuestTest {
    @Test void bareTypedSelectAndLegacyAliasReturnTrackedFuture() throws Exception {
        assertEquals("42|42",run("""
                pub async fnc main(): void {
                  val Future<int> first = select<int> over
                    default: -> { return 42; }
                  end;
                  val Future<int> second = nb select<int> { default: -> { return 42; } };
                  stdio.stdout.write(await first);
                  stdio.stdout.write("|");
                  stdio.stdout.write(await second);
                  return;
                }
                """));
    }
    @Test void explicitAwaitUsesTypedResultWithoutContextualFutureAnnotation() throws Exception {
        assertEquals("42",run("""
                pub async fnc main(): void {
                  const int base = 40;
                  val int result = await select<int> { default: -> { return base + 2; } };
                  stdio.stdout.write(result);
                  return;
                }
                """));
    }
    @Test void selectedDefaultIsScheduledBeforeAwaitAndCapturesScalarAtSubmission() throws Exception {
        assertEquals("41|99",run("""
                pub async fnc main(): void {
                  let int n = 41;
                  val Channel<int> ready = Channel.new<int>(1);
                  val Future<int> pending = select<int> over
                    default: -> { writech ready, n; return n; }
                  end;
                  n = 99;
                  val int event = readch ready;
                  stdio.stdout.write(event);
                  stdio.stdout.write("|");
                  stdio.stdout.write(n);
                  await pending;
                  return;
                }
                """));
    }
    @Test void typedDefaultFutureSettlesAfterLexicalCleanup() throws Exception {
        assertEquals("21F42",run("""
                pub async fnc main(): void {
                  val Future<int> pending = select<int> {
                    default: -> {
                      try {
                        defer stdio.stdout.write("1");
                        defer stdio.stdout.write("2");
                        rt cooperate;
                        return 42;
                      } finally {
                        stdio.stdout.write("F");
                      }
                    }
                  };
                  stdio.stdout.write(await pending);
                  return;
                }
                """));
    }

    @Test void typedDefaultRejectsCallerBorrowAndMissingReturnPath() {
        for(String body:new String[]{
          "val Future<int> result = select<int> { default: -> { if true { return 42; } } };",
          "val Future<int> result = select<int> { default: -> { break; return 42; } };"
        }) assertThrows(IllegalArgumentException.class,
            () -> OresCompiler.parseAndTypeCheck("pub async fnc main(): void { "+body+" return; }"),body);
    }

    @Test void explicitTypeCannotBeReplacedByContextOrImplicitAwait() {
        for(String body:new String[]{
          "val bool result = await select<int> { default: -> { return 42; } };",
          "val Future<bool> result = select<int> { default: -> { return true; } };",
          "val int result = select<int> { default: -> { return 42; } };",
          "val Future<int> result = select<int> { default: -> { return true; } };",
          "val Future<int> result = select<int> { default: -> {} };",
          "val Future<int> result = try select<int> { default: -> { return 42; } };",
          "val Future<int> result = select<int>(3) { default: -> { return 42; } };"
        }) assertThrows(IllegalArgumentException.class,
          () -> OresCompiler.parseAndTypeCheck("pub async fnc main(): void { "+body+" return; }"),body);
    }
    @Test void countedSingleReadGuestReturnsExactProjectedArray() throws Exception {
        assertEquals("6|10|26|99",run("""
                pub async fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(4);
                  writech incoming, 3; writech incoming, 5;
                  writech incoming, 13; writech incoming, 99;
                  val Future<Array<int>> pending = select<int>(3) over
                    when readch incoming: int value -> { return value * 2; }
                  end;
                  val Array[int] result = await pending;
                  stdio.stdout.write(result[0]); stdio.stdout.write("|");
                  stdio.stdout.write(result[1]); stdio.stdout.write("|");
                  stdio.stdout.write(result[2]); stdio.stdout.write("|");
                  stdio.stdout.write(readch incoming);
                  return;
                }
                """));
    }

    @Test void countedSingleReadAliasAndDirectAwaitExecute() throws Exception {
        assertEquals("42|42",run("""
                pub async fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(2);
                  writech incoming, 21; writech incoming, 40;
                  val Future<Array<int>> first = nb select<int>(1) {
                    when readch incoming: int value -> { return value * 2; }
                  };
                  val Array[int] result = await first;
                  val Array[int] second = await select<int>(1) over
                    when readch incoming: int value -> { return value + 2; }
                  end;
                  stdio.stdout.write(result[0]); stdio.stdout.write("|");
                  stdio.stdout.write(second[0]);
                  return;
                }
                """));
    }

    @Test void countedUnsupportedSourceFormsAndContextFailClosed() {
        for (String expression : new String[]{
          "select<int>(-1) { when readch incoming: int n -> { return n; } }",
          "select<int>(17) { when readch incoming: int n -> { return n; } }",
          "select<int>(1+2) { when readch incoming: int n -> { return n; } }",
          "select<int>(1) { when readch incoming: int n -> { return n; } default: -> { return 0; } }",
          "select<int>(1) { when readch incoming: int n -> { return n + other; } }"
        }) assertThrows(IllegalArgumentException.class, () ->
            OresCompiler.parseAndTypeCheck("pub async fnc main(): void { val Channel<int> incoming = Channel.new<int>(4); val int other = 1; val Future<Array<int>> result = "+expression+"; return; }"),expression);
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(4);
                  val Future<int> result = select<int>(1) { when readch incoming: int n -> { return n; } };
                  return;
                }
                """));
    }

    // Exact bytes from atto-platform-conformance #608, head
    // 62471273cf3d62abdf24bbfbdf312645a08d578e. Keep unsupported targets separate.
    @Test void portableFourPositiveCountedExactBlobsReturn42() throws Exception {
        for (String name : new String[]{"counted-three", "counted-alias", "counted-one", "counted-zero"}) {
            String code = java.nio.file.Files.readString(java.nio.file.Path.of("tests/fixtures/select-source-v2", name + ".atto"));
            try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
                assertEquals(42L, context.eval(Source.newBuilder(OresLanguage.ID, code, name+".atto")
                        .mimeType(OresLanguage.MIME_TYPE).buildLiteral()).asLong(), name);
            }
        }
    }

    @Test void zeroCountNeverCommitsAndReturnsFreshEmptyArray() throws Exception {
        assertEquals("0|0|42",run("""
                pub async fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 42;
                  val Array[int] first = await select<int>(0) {
                    when readch incoming: int n -> { return n * 2; }
                  };
                  val Array[int] second = await select(0) over
                    when readch incoming: int n -> { return n; }
                  end;
                  stdio.stdout.write(first.size); stdio.stdout.write("|");
                  stdio.stdout.write(second.size); stdio.stdout.write("|");
                  stdio.stdout.write(readch incoming);
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  val Channel<bool> incoming = Channel.new<bool>(1);
                  val Future<Array<int>> bad = select<int>(0) { when readch incoming: int n -> { return n; } };
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  val Future<Array<int>> bad = select<int>(0) { when readch missing: int n -> { return n; } };
                  return;
                }
                """));
    }

    @Test void zeroCountHasTerminalControlHandleBeforeAnyAwait() throws Exception {
        assertEquals("false|#success|0|42",run("""
                pub async fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 42;
                  val Future<Array<int>> pending = select<int>(0) {
                    when readch incoming: int n -> { return n * 2; }
                  };
                  stdio.stdout.write(pending.cancel());
                  stdio.stdout.write("|");
                  stdio.stdout.write(await pending.get_done());
                  stdio.stdout.write("|");
                  val Array[int] result = await pending;
                  stdio.stdout.write(result.size);
                  stdio.stdout.write("|");
                  stdio.stdout.write(readch incoming);
                  return;
                }
                """));
    }

    @Test void portableFiveNegativeCountedBlobsRejectBeforeExecution() throws Exception {
        for (String name : new String[]{"reject-counted-negative", "reject-counted-bool",
                "reject-counted-scalar", "reject-counted-default", "reject-counted-write"}) {
            String code = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "tests/fixtures/select-source-v2", name + ".atto"));
            assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(code), name);
        }
    }

    @Test void dynamicCountSnapshotAndZeroGuestExecute() throws Exception {
        assertEquals("6|10|26|99|0|42",run("""
                pub async fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(4);
                  writech incoming, 3; writech incoming, 5;
                  writech incoming, 13; writech incoming, 99;
                  let int count = 3;
                  val Future<Array<int>> pending = select<int>(count) {
                    when readch incoming: int n -> { return n * 2; }
                  };
                  count = 1;
                  val Array[int] result = await pending;
                  stdio.stdout.write(result[0]); stdio.stdout.write("|");
                  stdio.stdout.write(result[1]); stdio.stdout.write("|");
                  stdio.stdout.write(result[2]); stdio.stdout.write("|");
                  stdio.stdout.write(readch incoming); stdio.stdout.write("|");
                  writech incoming, 42; count = 0;
                  val Array[int] empty = await select(count) {
                    when readch incoming: int n -> { return n; }
                  };
                  stdio.stdout.write(empty.size); stdio.stdout.write("|");
                  stdio.stdout.write(readch incoming);
                  return;
                }
                """));
    }

    @Test void dynamicCountInvalidBeforeTaskAdmission() {
        for (String value : new String[]{"-1", "17", "9223372036854775807"}) {
            var ex = assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> run(
                "pub async fnc main(): void { val int count = "+value+"; val Channel<int> incoming = Channel.new<int>(1); val Future<Array<int>> result = select<int>(count) { when readch incoming: int n -> { return n; } }; return; }"));
            assertTrue(ex.getMessage().contains("SelectCountInvalid"), ex.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  val bool count = true;
                  val Channel<int> incoming = Channel.new<int>(1);
                  val Future<Array<int>> bad = select<int>(count) { when readch incoming: int n -> { return n; } };
                  return;
                }
                """));
    }

    @Test void countedProjectionBudgetAndWrongPayloadRejected() {
        String projection = "n" + " + 1".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                "pub async fnc main(): void { val Channel<int> incoming = Channel.new<int>(4); val Future<Array<int>> result = select<int>(1) { when readch incoming: int n -> { return "+projection+"; } }; return; }"));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  val Channel<bool> incoming = Channel.new<bool>(4);
                  val Future<Array<int>> result = select<int>(1) { when readch incoming: int n -> { return n; } };
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        var out=new ByteArrayOutputStream();
        try(var context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()){
            context.eval(Source.newBuilder(OresLanguage.ID,program,"typed-default-select.atto")
              .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
