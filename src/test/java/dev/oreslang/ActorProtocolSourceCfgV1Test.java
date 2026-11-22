package dev.oreslang;

import dev.oreslang.parser.ActorProtocolSourceCfgV1;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Atto-source Parser AST -> bounded affine CFG model. Fixtures are
 * intentionally NOT executable guest protocol intrinsics or runtime receipts.
 */
final class ActorProtocolSourceCfgV1Test {
    private static final String PRELUDE = """
            define protocol Connection as
              initial Idle
              state Idle as
                on Connect -> Active replies Ack
                on Close -> Closed replies Ack
              end
              state Active as
                on Subscribe -> Active streams Event
                on Close -> Closed replies Ack
              end
              terminal state Closed
            end
            define class Ack as end
            define class Event as end
            define class Connect implements UnaryRequest<Ack> as end
            define class Subscribe implements StreamRequest<Event> as end
            define class Close implements UnaryRequest<Ack> as end
            define actor Client implements Connection as end
            """;

    private static ActorProtocolSourceCfgV1.Projection analyze(
            String function, String name, Map<String,String> trusted) {
        return ActorProtocolSourceCfgV1.verifySource(
                PRELUDE + "\n" + function, name, trusted);
    }
    private static void denied(String source, String fn, String expected) {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                analyze(source, fn, Map.of("handle", "Connection")));
        assertTrue(ex.getMessage().contains(expected), ex::getMessage);
    }

    @Test void realTakeBangAstTransfersAndReturnsHandle() {
        var p = analyze("""
                define fnc move_handle(ProtocolHandle<Connection> handle): ProtocolHandle<Connection> {
                  const ProtocolHandle<Connection> moved = take! handle;
                  return moved;
                }
                ""","move_handle",Map.of("handle","Connection"));
        assertEquals(1, p.proof().verifiedBlocks());
        assertEquals(4,p.proof().verifiedOperations());
    }

    @Test void realNominalRequestAstConsumesHandleIntoCancelledLease() {
        var p = analyze("""
                define fnc cancel_request(ProtocolHandle<Connection> handle, Connect msg): void {
                  const UnaryLease<Ack> lease = handle.request(msg);
                  lease.cancel();
                  return;
                }
                ""","cancel_request",Map.of("handle","Connection"));
        assertEquals(1,p.proof().verifiedBlocks());
        assertEquals(4,p.proof().verifiedOperations());
    }

    @Test void realAstUseAfterTakeIsRejected() {
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle): ProtocolHandle<Connection> {
                  const ProtocolHandle<Connection> moved = take! handle;
                  return handle;
                }
                ""","invalid","E_PROTOCOL_HANDLE_NOT_LIVE");
    }

    @Test void realAstCannotReturnWithLiveUnsettledLease() {
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle, Connect msg): void {
                  const UnaryLease<Ack> lease = handle.request(msg);
                  return;
                }
                ""","invalid","E_PROTOCOL_LEASE_ESCAPE");
    }

    @Test void realAstRejectsUnaryStreamKindSwap() {
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle, Connect msg): void {
                  const StreamLease<Ack> lease = handle.request(msg);
                  lease.cancel();
                  return;
                }
                ""","invalid","E_PROTOCOL_SOURCE_TYPE");
    }

    @Test void realAstCannotForgeProtocolAuthorityByDeclaringParameterType() {
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle, ProtocolHandle<Connection> forged): void {
                  return;
                }
                ""","invalid","E_PROTOCOL_SOURCE_AUTHORITY");
    }

    @Test void sourceAwaitIsExplicitlyUnsupportedUntilCompleteOutcomeEdges() {
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle, Connect msg): void {
                  const UnaryLease<Ack> lease = handle.request(msg);
                  await lease;
                  return;
                }
                ""","invalid","E_PROTOCOL_SOURCE_EFFECT");
    }

    @Test void realAstRejectsImplicitReturnsAndUnverifiedMemberEffects() {
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle): void {
                  handle.close();
                  return;
                }
                ""","invalid","E_PROTOCOL_SOURCE_EFFECT");
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle): void {
                  const ProtocolHandle<Connection> moved = take! handle;
                }
                ""","invalid","E_PROTOCOL_SOURCE_IMPLICIT_EXIT");
    }

    @Test void branchReturnsAreRepresentedAsSeparateCfgEdges() {
        var p = analyze("""
                define fnc branch_return(ProtocolHandle<Connection> handle, bool flag): ProtocolHandle<Connection> {
                  if flag {
                    const ProtocolHandle<Connection> moved = take! handle;
                    return moved;
                  } else {
                    return handle;
                  }
                }
                ""","branch_return",Map.of("handle","Connection"));
        assertEquals(3,p.proof().verifiedBlocks());
    }

    @Test void branchJoiningPossibleMoveMustNotUseInvalidatedHandle() {
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle, bool flag): ProtocolHandle<Connection> {
                  if flag {
                    const ProtocolHandle<Connection> moved = take! handle;
                    return moved;
                  } else {
                    const ProtocolHandle<Connection> other = take! handle;
                    return other;
                  }
                  return handle;
                }
                ""","invalid","E_PROTOCOL_SOURCE_UNREACHABLE");
    }

    @Test void declarationCannotSpoofCompilerOwnedProtocolHandles() {
        String forged = "define class ProtocolHandle as end\n" + PRELUDE + "\n"
                + """
                  define fnc move_handle(ProtocolHandle<Connection> handle): ProtocolHandle<Connection> {
                    const ProtocolHandle<Connection> moved = take! handle;
                    return moved;
                  }
                  """;
        var fail = assertThrows(IllegalArgumentException.class,
                () -> ActorProtocolSourceCfgV1.verifySource(forged,
                        "move_handle", Map.of("handle", "Connection")));
        assertTrue(fail.getMessage().contains("E_PROTOCOL_SOURCE_FORGED_HANDLE_TYPE"),
                fail::getMessage);
    }

    @Test void unsupportedExceptionAndDeferredCleanupNeverDisappear() {
        denied("""
                define fnc invalid(ProtocolHandle<Connection> handle): void {
                  defer handle.close();
                  return;
                }
                ""","invalid","E_PROTOCOL_SOURCE_EFFECT");
    }
}
