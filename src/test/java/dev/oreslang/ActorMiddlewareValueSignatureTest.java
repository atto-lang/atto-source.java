package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorMiddlewareValueSignatureTest {
    private static void rejects(String source, String message) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    @Test
    void unaryValueHookIsParsedAndRejectedOnlyAtUnavailableGuestDispatchBoundary() {
        rejects("""
                @ActorMiddleware(Clean)
                define unary actor Worker as
                    run(int value): int { return value * 5; }
                end
                define class Clean as
                    async next_val(MiddlewareContext req, MiddlewareResponseDraft res): MiddlewareDecision<int> {
                        return null;
                    }
                end
                """, "continuation lowering");
    }

    @Test
    void aroundHookMayTakeResponseDraftAndContinuation() {
        rejects("""
                @ActorMiddleware(Clean)
                define stream actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                define class Clean as
                    async next(MiddlewareContext req, MiddlewareResponseDraft res, MiddlewareNext next): MiddlewareOutcome<void> {
                        return null;
                    }
                end
                """, "continuation lowering");
    }

    @Test
    void sameClassMayProvideUnaryAndStreamHooksWithoutAutomaticDualDispatch() {
        rejects("""
                @ActorMiddleware(Clean)
                define unary actor Worker as
                    run(int value): int { return value; }
                end
                define class Clean as
                    async next_val(MiddlewareContext req, MiddlewareResponseDraft res): MiddlewareDecision<int> {
                        return null;
                    }
                    async next(MiddlewareContext req, MiddlewareResponseDraft res, MiddlewareNext next): MiddlewareOutcome<int> {
                        return null;
                    }
                end
                """, "continuation lowering");
    }

    @Test
    void untypedComplementAndBareReturnAreRejected() {
        rejects("""
                @ActorMiddleware(Clean)
                define unary actor Worker as
                    run(int value): int { return value; }
                end
                define class Clean as
                    async next_val(MiddlewareContext req, MiddlewareResponseDraft res): !void {
                        return null;
                    }
                end
                """, "MiddlewareDecision<T>");
        rejects("""
                @ActorMiddleware(Clean)
                define unary actor Worker as
                    run(int value): int { return value; }
                end
                define class Clean as
                    async next_val(MiddlewareContext req, MiddlewareResponseDraft res): MiddlewareDecision {
                        return null;
                    }
                end
                """, "MiddlewareDecision<T>");
    }

    @Test
    void mismatchedUnaryReplyTypeCannotEnterMiddlewarePipeline() {
        rejects("""
                @ActorMiddleware(Clean)
                define unary actor Worker as
                    run(int value): int { return value; }
                end
                define class Clean as
                    async next_val(MiddlewareContext req, MiddlewareResponseDraft res): MiddlewareDecision<String> {
                        return null;
                    }
                end
                """, "reply type must match unary actor");
    }

    @Test
    void streamCannotUseValueOnlyMiddlewareThatWouldBeIgnored() {
        rejects("""
                @ActorMiddleware(Clean)
                define stream actor Worker as
                    receive(ActorMail<int> mail): void { return; }
                end
                define class Clean as
                    async next_val(MiddlewareContext req, MiddlewareResponseDraft res): MiddlewareDecision<int> {
                        return null;
                    }
                end
                """, "stream actors require the next hook");
    }

    @Test
    void overloadedAndStaticHooksAreRejectedAtSourceBoundary() {
        rejects("""
                @ActorMiddleware(Clean)
                define unary actor Worker as
                    run(int value): int { return value; }
                end
                define class Clean as
                    static async fnc next_val(MiddlewareContext req, MiddlewareResponseDraft res): MiddlewareDecision<int> {
                        return null;
                    }
                end
                """, "instance-only");
    }
}
