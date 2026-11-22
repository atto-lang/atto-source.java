package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Channel I/O direction shorthand (atto-spec io-direction-shorthand-v1) and
 * the integer shifts that replaced {@code <<}/{@code >>}
 * (bitwise-and-stream-operators-v1).
 *
 * <p>{@code sink << value;} is exactly {@code writech sink, value;}.
 * {@code source >> val name;} and {@code val name << source;} are exactly
 * {@code val name = readch source;}: a read always declares a fresh binding.
 * The shorthand therefore suspends (never parks a carrier), applies
 * backpressure and keeps channel ownership rules. Shifts are {@code <<<},
 * {@code >>>} and the infix word {@code ashr}.</p>
 */
@Timeout(60)
final class ChannelFlowOperatorTest {

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "channel-io.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void everyReadAndWriteSpellingRendezvousesThroughACapacityZeroChannel() throws Exception {
        // Capacity 0: every write suspends until a reader arrives, and every
        // read suspends until a writer arrives.
        assertEquals("41|42|43|44", run("""
                async fnc produce(Channel<int> data): void {
                  data << 41;
                  data << 42;
                  writech data, 43;
                  data << 44;
                  return;
                }

                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(0);
                  val Future<void> producer = produce(data);
                  data >> val a;
                  val b << data;
                  data >> let int c;
                  val d: int << data;
                  await producer;
                  stdio.stdout.write(a);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b);
                  stdio.stdout.write("|");
                  stdio.stdout.write(c);
                  stdio.stdout.write("|");
                  stdio.stdout.write(d);
                  return;
                }
                """));
    }

    @Test
    void aWriterSuspendedOnAFullChannelResumesWhenAReaderDrainsIt() throws Exception {
        assertEquals("false|1|2|3", run("""
                async fnc produce(Channel<int> data, Channel<bool> ready): void {
                  data << 1;
                  val bool admitted = try writech data, 99;
                  ready << admitted;
                  data << 2;
                  data << 3;
                  return;
                }

                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  val Channel<bool> ready = Channel.new<bool>(1);
                  val Future<void> producer = produce(data, ready);
                  ready >> val extra;
                  data >> val first;
                  val second << data;
                  data >> val third;
                  await producer;
                  stdio.stdout.write(extra);
                  stdio.stdout.write("|");
                  stdio.stdout.write(first);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second);
                  stdio.stdout.write("|");
                  stdio.stdout.write(third);
                  return;
                }
                """));
    }

    @Test
    void theShorthandWorksInsideAnActorReceiveOnAFieldChannel() throws Exception {
        assertEquals("10|20|30|", run("""
                define actor Relay as
                  let Channel<int> feed;
                  let int last;

                  on_start(): void {
                    self.feed = Channel.new<int>(4);
                    self.last = 0;
                    return;
                  }

                  receive(ActorMail<int> mail): void {
                    self.feed << mail.value * 10;
                    self.feed >> val got;
                    self.last = got;
                    stdio.stdout.write(self.last);
                    stdio.stdout.write("|");
                    if mail.value == 3 then
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val relay = spawn Relay();
                  await relay.get_ready();
                  relay.send(1);
                  relay.send(2);
                  relay.send(3);
                  await relay.get_done();
                  return;
                }
                """));
    }

    @Test
    void shiftsAreTripleArrowsAndInfixAshrPerTheBitwiseConformanceCases() throws Exception {
        // BW-005, BW-006, BW-007, BW-009, BW-010, BW-011 plus a logical/arithmetic contrast.
        assertEquals("32|9223372036854775807|-4|24|1|-1|4|-1", run("""
                pub fnc main(): void {
                  stdio.stdout.write(8 <<< 2);
                  stdio.stdout.write("|");
                  stdio.stdout.write(-1 >>> 1);
                  stdio.stdout.write("|");
                  stdio.stdout.write(-8 ashr 1);
                  stdio.stdout.write("|");
                  stdio.stdout.write(1 + 2 <<< 3);
                  stdio.stdout.write("|");
                  stdio.stdout.write((1 <<< 63) >>> 63);
                  stdio.stdout.write("|");
                  stdio.stdout.write((1 <<< 63) ashr 63);
                  stdio.stdout.write("|");
                  stdio.stdout.write(16 >>> 2);
                  stdio.stdout.write("|");
                  stdio.stdout.write(-1 ashr 1);
                  return;
                }
                """));
    }

    private static List<Ast.Stmt> mainBody(Ast.Program program) {
        return ((Ast.FunctionDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl).findFirst().orElseThrow()).body();
    }

    @Test
    void theShorthandDesugarsToExactlyTheExplicitChannelOperation() {
        Ast.Program program = Parser.parse("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(2);
                  data << 7;
                  data >> val x;
                  data << 8;
                  let y: int << data;
                  return;
                }
                """);
        TypeChecker.check(program);
        List<Ast.Stmt> body = mainBody(program);
        Ast.ChannelOpExpr write = assertInstanceOf(Ast.ChannelOpExpr.class,
                ((Ast.ExprStmt) body.get(1)).expression());
        assertEquals(Ast.ChannelOperation.WRITE, write.operation());
        assertEquals(Ast.WaitMode.BLOCKING, write.mode());
        assertTrue(Ast.IoShorthand.isShorthandWrite(write));

        Ast.BindingStmt sourceFirst = assertInstanceOf(Ast.BindingStmt.class, body.get(2));
        assertEquals(Ast.BindingKind.VAL, sourceFirst.kind());
        assertEquals("x", sourceFirst.name());
        assertNull(sourceFirst.declaredType());
        Ast.ChannelOpExpr read = assertInstanceOf(Ast.ChannelOpExpr.class, sourceFirst.initializer());
        assertEquals(Ast.ChannelOperation.READ, read.operation());
        assertEquals(Ast.WaitMode.BLOCKING, read.mode());

        Ast.BindingStmt declarationFirst = assertInstanceOf(Ast.BindingStmt.class, body.get(4));
        assertEquals(Ast.BindingKind.LET, declarationFirst.kind());
        assertEquals("int", declarationFirst.declaredType().name());
        assertEquals(Ast.ChannelOperation.READ,
                assertInstanceOf(Ast.ChannelOpExpr.class, declarationFirst.initializer()).operation());
    }

    private static void rejected(String source, String fragment) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(String.valueOf(failure.getMessage()).contains(fragment), failure::getMessage);
    }

    @Test
    void misuseFailsClosedBeforeExecution() {
        // `<<` / `>>` are never integer shifts.
        rejected("""
                pub fnc main(): void {
                  val int x = 1;
                  val int y = x << 2;
                  return;
                }
                """, "use '<<<', '>>>' or 'ashr' for bit shifts");
        rejected("""
                pub fnc main(): int {
                  return 8 >> 2;
                }
                """, "use '<<<', '>>>' or 'ashr' for bit shifts");
        rejected("""
                pub fnc main(): void {
                  let int x = 1;
                  x << 2;
                  return;
                }
                """, "Use '<<<' for a bit shift");
        rejected("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  5 << data;
                  return;
                }
                """, "'<<' writes to a Channel<T> sink");
        rejected("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  data << "text";
                  return;
                }
                """, "writech value");
        // A read declares a fresh binding; it never assigns an existing name.
        rejected("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  let int x = 0;
                  data >> x;
                  return;
                }
                """, "'>>' is channel I/O, only valid as a statement");
        // v1 rejects chains.
        rejected("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(2);
                  data << 1 << 2;
                  return;
                }
                """, "Chains and nested uses are rejected");
        // const is compile-time; a runtime read cannot initialize it.
        rejected("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  data >> const x;
                  return;
                }
                """, "E_CONST_EFFECT");
        rejected("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  const x << data;
                  return;
                }
                """, "E_CONST_EFFECT");
        rejected("""
                pub fnc main(): void {
                  val x << 5;
                  return;
                }
                """, "readch requires Channel<T>");
        rejected("""
                pub fnc main(): int {
                  return 1 ashr true;
                }
                """, "bitwise right operand");
    }

    @Test
    void nestedGenericClosersAreNotShifts() {
        OresCompiler.parseAndTypeCheck("""
                pub async routine main(): void {
                  val Channel<Option<int>> data = Channel.new<Option<int>>(1);
                  data << Some(1);
                  data >> val Option<int> got;
                  return;
                }
                """);
    }
}
