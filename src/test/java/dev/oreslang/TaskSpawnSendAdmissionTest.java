package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real parser/ownership-checker path, not a synthetic Send trait fixture.
 * The TypeChecker must continue to reject unsupported general task {} source.
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
                do task { stdio.stdout.log(i); };
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
                do task { stdio.stdout.log(data); };
                return;
            }
            """));
        reject("""
            pub fnc main(): void {
                let data = arr[1, 2, 3];
                do task { stdio.stdout.log(data); };
                let again = data;
                return;
            }
            """, "moved value");
    }

    @Test
    void builtinOptionAndResultValuesTransferByOwnership() {
        assertDoesNotThrow(() -> verify("""
            pub fnc main(Option<int> optional): void {
                do task { stdio.stdout.log(optional); };
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
                    + " do task { stdio.stdout.log(handle); }; return; }",
                    "requires proven Send");
        }
    }

    @Test
    void actorRefsAndNestedUnsendCapabilitiesCannotBeLaundered() {
        reject("""
            pub fnc main(ActorRef<int> receiver): void {
                let Task<int> t = task { stdio.stdout.log(receiver); return 1; };
                return;
            }
            """, "requires proven Send");
        reject("""
            pub fnc main(Option<RawPointer<int>> x): void {
                do task { stdio.stdout.log(x); };
                return;
            }
            """, "requires proven Send");
        reject("""
            pub fnc main(Array<NativeHandle<int>> handles): void {
                do task { stdio.stdout.log(handles); };
                return;
            }
            """, "requires proven Send");
    }

    @Test
    void scheduledLambdaParametersAlsoRequireSend() {
        reject("""
            pub fnc main(): void {
                let Task<int> t = (async task |Future<int> f| -> { return 1; })(7);
                return;
            }
            """, "requires proven Send");
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
            """, "requires proven Send");
    }
    @Test
    void fullSourceCompilerStillRefusesToExecuteUnloweredTasks() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                    pub fnc main(): void {
                        let int x = 7;
                        do task { stdio.stdout.log(x); };
                        return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("nb Task blocks"), failure::getMessage);
    }
}
