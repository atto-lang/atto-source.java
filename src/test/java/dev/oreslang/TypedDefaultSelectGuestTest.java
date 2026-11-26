package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** First executable value-select subset, no event or counted support. */
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
          "select<int>(0) { when readch incoming: int n -> { return n; } }",
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

    private static String run(String program) throws Exception {
        var out=new ByteArrayOutputStream();
        try(var context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()){
            context.eval(Source.newBuilder(OresLanguage.ID,program,"typed-default-select.atto")
              .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
