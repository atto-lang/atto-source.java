package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Adversarial actor DATA admission checks. A Sendable spelling, serializer
 * interface, supervisor handle or region wrapper is not compiler-owned proof
 * of ownership/authority. A future codec registry must not weaken these.
 */
final class ActorDataCapabilityContractTest {
    @Test
    void runtimeAuthorityAndOwnedRegionHandlesCannotMasqueradeAsData() {
        for (String type : new String[] {
                "ActorHandle<int>", "ActorControlHandle",
                "ActorGroupJoinCapability", "ContinuationTarget",
                "SyncCell<int>", "Region<int>", "RegionTransfer<int>",
                "OwnedRegion<int>", "Option<ActorHandle<int>>",
                "Result<Region<int>, String>",
                "Supervisor.ActorGroupJoinCapability"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "actor fnc bad(" + type + " value): void { return; }")),
                    type);
        }
    }

    @Test
    void invalidIntrinsicSumArityNeverAdmitsActorData() {
        // Reject both bare nominals and malformed parametrization, whether
        // parser/lookup or actor boundary detects the shape first.
        for (String type : new String[] {
                "Option", "Result", "Result<int>",
                "Option<int, String>", "Result<int, String, bool>",
                "OptionUnwrapError<int>"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "actor fnc bad(" + type + " value): void { return; }")),
                    type);
        }
    }

    @Test
    void ordinaryAsyncTasksCannotLaunderActorAuthorityViaSharedSafe() {
        for (String type : new String[] {
                "ActorHandle<int>", "ActorGroupJoinCapability",
                "Region<int>", "NetworkCodec<int>",
                "Option<RegionTransfer<int>>"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "pub async routine bad(" + type + " value): void { return; }")),
                    type);
        }
    }

    @Test
    void ordinaryDataRemainsAdmissibleToActorMailbox() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Receiver as
                  receive(ActorMail<String> mail): void { return; }
                end
                """)));
    }
}
