package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WebSocketGuestBoundaryTest {
    @Test void supervisorSurfaceHasTypedFutureAndRetirementReceipt() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
            pub async routine main(): void {
              val server = websocket.listen("EchoActor", 18761, "https://atto.local");
              await server.serve_one();
              await server.get_retired();
              server.close();
              return;
            }
            """)));
    }
    @Test void listenerCannotCrossActorMailboxBoundary() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            define actor Invalid as
              receive(ActorMail<WebSocketServer> mail): void { return; }
            end
            """)));
    }
    @Test void arbitraryTransportMemberDoesNotFallBackToUnknown() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            pub routine main(): void { websocket.raw_socket(); return; }
            """)));
    }
    @Test void listenChecksArguments() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            pub routine main(): void { websocket.listen("Actor", "port", "origin"); return; }
            """)));
    }
}
