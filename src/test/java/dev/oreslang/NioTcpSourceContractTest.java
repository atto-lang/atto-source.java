package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

final class NioTcpSourceContractTest {
    @Test
    void asyncConnectAndByteOperationsHaveFutureTypes() {
        String program = """
                pub async fnc main(): void {
                  val Future<NioTcpConnection> pending =
                      network.connect_async("127.0.0.1", 32123);
                  val NioTcpConnection socket = await pending;

                  val Future<int> sent =
                      socket.write_bytes_async([1, 2, 255]);
                  val int count = await sent;

                  val Future<List<int>> receiving =
                      socket.read_bytes_async(8);
                  val List<int> bytes = await receiving;

                  val Future<void> closing = socket.close_async();
                  await closing;

                  stdio.stdout.write(count);
                  return;
                }
                """;

        assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void asyncConnectRequiresIntegralPortAndKnownNioMembers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val pending =
                              network.connect_async("127.0.0.1", 1.5);
                          return;
                        }
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc main(): void {
                          val NioTcpConnection socket =
                              await network.connect_async(
                                  "127.0.0.1", 32123);
                          socket.read_line();
                          return;
                        }
                        """));
    }
}
