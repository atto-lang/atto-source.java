package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Channel data-flow operators. The arrow is where the data goes:
 * {@code ch << v} and {@code v >> ch} write; {@code x << ch} and
 * {@code ch >> x} read. They lower to exactly {@code writech}/{@code readch},
 * so they suspend (never park a carrier), apply backpressure and keep channel
 * ownership rules. Bit shifts are {@code <<<} and {@code >>>}.
 */
@Timeout(60)
final class ChannelFlowOperatorTest {

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "channel-flow.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void bothArrowsWriteAndReadThroughARendezvousChannel() throws Exception {
        // Capacity 0: every write suspends until a reader arrives, and every
        // read suspends until a writer arrives, in both directions.
        assertEquals("41|42|43|44", run("""
                async fnc produce(Channel<int> data): void {
                  data << 41;
                  42 >> data;
                  data << 43;
                  44 >> data;
                  return;
                }

                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(0);
                  val Future<void> producer = produce(data);
                  let int a = 0;
                  let int b = 0;
                  let int c = 0;
                  let int d = 0;
                  a << data;
                  data >> b;
                  c << data;
                  data >> d;
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
                  let bool extra = true;
                  extra << ready;
                  let int first = 0;
                  let int second = 0;
                  let int third = 0;
                  data >> first;
                  data >> second;
                  data >> third;
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
    void flowsWorkInsideAnActorReceiveAndOnActorFields() throws Exception {
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
                    self.feed >> self.last;
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
    void bitShiftsAreTripleArrows() throws Exception {
        assertEquals("16|4|0", run("""
                pub fnc main(): void {
                  stdio.stdout.write(1 <<< 4);
                  stdio.stdout.write("|");
                  stdio.stdout.write(16 >>> 2);
                  stdio.stdout.write("|");
                  stdio.stdout.write(1 >>> 3);
                  return;
                }
                """));
    }

    @Test
    void aFlowLowersToExactlyTheEquivalentChannelOperation() {
        Ast.Program program = Parser.parse("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  data << 7;
                  let int x = 0;
                  x << data;
                  return;
                }
                """);
        TypeChecker.check(program);
        var body = ((Ast.FunctionDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl).findFirst().orElseThrow()).body();
        var write = (Ast.BinaryExpr) ((Ast.ExprStmt) body.get(1)).expression();
        var read = (Ast.BinaryExpr) ((Ast.ExprStmt) body.get(3)).expression();
        Ast.ChannelOpExpr writech = assertInstanceOf(Ast.ChannelOpExpr.class, Ast.ChannelFlow.require(write));
        assertEquals(Ast.ChannelOperation.WRITE, writech.operation());
        assertEquals(Ast.WaitMode.BLOCKING, writech.mode());
        Ast.AssignExpr assign = assertInstanceOf(Ast.AssignExpr.class, Ast.ChannelFlow.require(read));
        Ast.ChannelOpExpr readch = assertInstanceOf(Ast.ChannelOpExpr.class, assign.value());
        assertEquals(Ast.ChannelOperation.READ, readch.operation());
        TypeChecker.check(program);
        assertTrue(Ast.ChannelFlow.require(write) == writech, "re-checking reuses the same lowering");
    }

    private static void rejected(String source, String fragment) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(String.valueOf(failure.getMessage()).contains(fragment), failure::getMessage);
    }

    @Test
    void misuseFailsClosedAtTypeCheck() {
        rejected("""
                pub fnc main(): void {
                  val int x = 1;
                  val int y = x << 2;
                  return;
                }
                """, "use '<<<' or '>>>' for bit shifts");
        rejected("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  data << "text";
                  return;
                }
                """, "writech value");
        rejected("""
                pub async routine main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  5 << data;
                  return;
                }
                """, "reads from a channel into a local, field or index");
    }
}
