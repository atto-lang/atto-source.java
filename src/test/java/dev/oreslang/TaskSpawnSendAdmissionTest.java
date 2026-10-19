package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.SendSyncTraits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real parser/ownership-checker path for the deny-only AST Send preflight.
 * Passing this suite never constitutes semantic Send permission: the resolved
 * ThreadSafetyTraits authority is still required, and TypeChecker must continue
 * to reject executing unlowered nb Task source.
 */
final class TaskSpawnSendAdmissionTest {
    private static Ast.Program verify(String body) {
        return OwnershipChecker.check(Parser.parse(body));
    }

    private static void reject(String program, String message) {
        IllegalArgumentException err = assertThrows(
                IllegalArgumentException.class, () -> verify(program));
        assertTrue(err.getMessage().contains(message), err::getMessage);
    }

    @Test
    void ordinaryScalarCaptureIsSendWithoutActors() {
        assertDoesNotThrow(() -> verify("""
            pub fnc main(): void {
                let int i = 11;
                do nb { stdio.stdout.log(i); };
                let int again = i;
                return;
            }
            """));
    }

    @Test
    void ownedArrayCaptureRetainsExistingMoveOnlyContract() {
        // Array/List are trusted only for UNIQUE ownership transfer,
        // never Sync shared aliasing or cross-actor export.
        assertDoesNotThrow(() -> verify("""
            pub fnc main(): void {
                let data = arr[1, 2, 3];
                do nb { stdio.stdout.log(data); };
                return;
            }
            """));
        reject("""
            pub fnc main(): void {
                let data = arr[1, 2, 3];
                do nb { stdio.stdout.log(data); };
                let again = data;
                return;
            }
            """, "moved value");
    }

    @Test
    void builtinOptionAndResultValuesTransferByOwnership() {
        assertDoesNotThrow(() -> verify("""
            pub fnc main(Option<int> optional): void {
                do nb { stdio.stdout.log(optional); };
                return;
            }
            """));
        assertDoesNotThrow(() -> verify("""
            pub fnc main(Result<int, string> result): void {
                do task { stdio.stdout.log(result); };
                return;
            }
            """));
    }

    @Test
    void rawPointersAndNativeHandlesCannotBeMovedToTask() {
        for (String type : new String[]{"RawPointer<int>", "NativeHandle<int>", "FfiHandle<int>"}) {
            reject("pub fnc main(" + type + " handle): void {"
                    + " do nb { stdio.stdout.log(handle); }; return; }",
                    "fails conservative Task Send preflight");
        }
    }

    @Test
    void actorRefsAndNestedUnsendCapabilitiesCannotBeLaundered() {
        reject("""
            pub fnc main(ActorRef<int> receiver): void {
                let Task<int> t = task { stdio.stdout.log(receiver); return 1; };
                return;
            }
            """, "fails conservative Task Send preflight");
        reject("""
            pub fnc main(Option<RawPointer<int>> x): void {
                do task { stdio.stdout.log(x); };
                return;
            }
            """, "fails conservative Task Send preflight");
        reject("""
            pub fnc main(Array<NativeHandle<int>> handles): void {
                do task { stdio.stdout.log(handles); };
                return;
            }
            """, "fails conservative Task Send preflight");
    }

    @Test
    void scheduledLambdaParametersAlsoRequireSend() {
        reject("""
            pub fnc main(): void {
                let Task<int> t = (async task |Future<int> f| -> { return 1; })(7);
                return;
            }
            """, "fails conservative Task Send preflight");
        assertDoesNotThrow(() -> verify("""
            pub fnc main(): void {
                let Task<int> t = (async task |int x| -> { return x; })(7);
                return;
            }
            """));
    }

    @Test
    void capturedUnverifiedNominalIsRejectedBeforeImplicitMove() {
        reject("""
            pub fnc main(ThreadBound<int> resource): void {
                let Task<int> t = (async task |int x| -> {
                    stdio.stdout.log(resource);
                    return x;
                })(7);
                return;
            }
            """, "fails conservative Task Send preflight");
    }
    @Test
    @org.junit.jupiter.api.Timeout(5)
    void astSendPreflightRejectsDeepAndWideTypeBombsWithoutPoisoning() {
        SendSyncTraits preflight = new SendSyncTraits(new Ast.Program(java.util.List.of()));

        Ast.TypeRef deep = new Ast.TypeRef("int", java.util.List.of(), false);
        for (int i = 0; i < 12_000; i++) {
            deep = new Ast.TypeRef("Option", java.util.List.of(deep), false);
        }
        assertFalse(preflight.mayBeSendAfterResolution(deep));

        Ast.TypeRef wide = new Ast.TypeRef(
                "Tuple",
                java.util.Collections.nCopies(
                        5_000, new Ast.TypeRef("int", java.util.List.of(), false)),
                false);
        assertFalse(preflight.mayBeSendAfterResolution(wide));

        assertTrue(preflight.mayBeSendAfterResolution(
                new Ast.TypeRef("int", java.util.List.of(), false)),
                "resource-budget rejection must not poison the next preflight query");
    }

    @Test
    void fullCompilerUsesResolvedSendAuthorityBeforeTaskExecutionGate() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(Future<int> pending): void {
                        do nb { stdio.stdout.log(pending); };
                        return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("concrete owned Send"),
                failure::getMessage);
    }

    @Test
    void positiveResolvedCaptureStillDoesNotEnableTaskExecution() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(Option<int> value): void {
                        do nb { stdio.stdout.log(value); };
                        return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("nb Task blocks"),
                failure::getMessage);
    }

    @Test
    void scheduledLambdaParameterUsesResolvedSendAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(Future<int> pending): void {
                        let Task<int> t =
                            (async task |Future<int> f| -> { return 1; })(pending);
                        return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("concrete owned Send"),
                failure::getMessage);
    }

    @Test
    void userShadowedOptionCannotGainIntrinsicTaskSend() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    define class Option<T> as
                        T value;
                    end
                    pub fnc main(Option<int> value): void {
                        do nb { stdio.stdout.log(value); };
                        return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("concrete owned Send"),
                failure::getMessage);
    }

    @Test
    void fullSourceCompilerStillRefusesToExecuteUnloweredTasks() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(): void {
                        let int x = 7;
                        do nb { stdio.stdout.log(x); };
                        return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("nb Task blocks"), failure::getMessage);
    }
}
