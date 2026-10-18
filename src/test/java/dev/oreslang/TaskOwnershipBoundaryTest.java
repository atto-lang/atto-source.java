package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static Task ownership checks are independently exercised while source
 * execution remains gated by TypeChecker.
 */
final class TaskOwnershipBoundaryTest {
    private static Ast.Program checked(String body) {
        return OwnershipChecker.check(Parser.parse(
                "pub fnc main(): void { " + body + " return; }"));
    }

    private static void reject(String body, String reason) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> checked(body));
        assertTrue(failure.getMessage().contains(reason), failure::getMessage);
    }

    @Test
    void copyableCaptureIsNotMoved() {
        assertDoesNotThrow(() -> checked("""
                let int n = 5;
                let Task<int> task = task { return n; };
                let int again = n;
                """));
    }

    @Test
    void borrowedReferencesCannotEscapeIntoTaskOrDiscardedTask() {
        for (String launch : new String[]{
                "let Task<int> t = nb { return borrowed; };",
                "let Task<int> t = task { return borrowed; };",
                "do nb { let item = borrowed; };",
                "do task { let item = borrowed; };"
        }) reject("""
                let int n = 7;
                let borrowed = rt borrow n;
                """ + launch, "capture borrowed");
    }

    @Test
    void affineCollectionMovesAtSpawnBeforeAwait() {
        reject("""
                let data = arr[1, 2, 3];
                let Task<int> job = task {
                    stdio.stdout.log(data);
                    return 1;
                };
                let again = data;
                """, "moved value");
    }

    @Test
    void competingTaskCapturesOfTheSameAffineValueFail() {
        reject("""
                let data = arr[1, 2, 3];
                let Task<int> first = task { stdio.stdout.log(data); return 1; };
                let Task<int> second = task { stdio.stdout.log(data); return 2; };
                """, "moved value");
    }

    @Test
    void nestedTaskCannotHideCaptureFromItsEnclosingLambda() {
        reject("""
                let data = arr[1, 2, 3];
                let job = (|int a| -> task {
                    stdio.stdout.log(data);
                    return a;
                })(5);
                let again = data;
                """, "moved value");
    }

    @Test
    void asyncTaskLambdaCapturesMustTransferAtDefinition() {
        reject("""
                let data = arr[1, 2, 3];
                let job = (async task |int a| -> {
                    stdio.stdout.log(data);
                    return a;
                })(5);
                let again = data;
                """, "moved value");
    }

    @Test
    void nestedTaskBorrowIsNotProtectedByShortLivedOuterLambda() {
        reject("""
                let int value = 5;
                let borrowed = rt borrow value;
                let job = (|int a| -> task {
                    return borrowed;
                })(4);
                """, "capture borrowed");
    }

    @Test
    void borrowedArgumentCannotBecomeAnAsyncTaskParameter() {
        reject("""
                let int number = 5;
                let borrowed = rt borrow number;
                let job = (async task |int a| -> a)(borrowed);
                """, "cannot be borrowed");
    }

    @Test
    void taskAwaitCannotBypassLiveLexicalBorrow() {
        reject("""
                let int number = 5;
                let borrowed = rt borrow number;
                let Task<int> job = task { return 1; };
                let int result = await job;
                """, "borrow is live");
    }

    @Test
    void awaitableOwnedTaskLambdaParametersRemainValid() {
        assertDoesNotThrow(() -> checked("""
                let Task<int> task = (async task |int a, string b| -> {
                    return a;
                })(5, "xyz");
                """));
    }

    @Test
    void wrongTaskIifeArityIsRejectedBeforeScheduling() {
        reject("""
                let job = (async task |int a, string b| -> a)(5);
                """, "expected 2 arguments");
    }
}
