package dev.oreslang.types;

import java.util.Objects;

/**
 * Compiler-only RFC routing for postfix Result/Option '?' propagation.
 *
 * NOT wired to the source AST or evaluator yet: callers must never treat this
 * helper as evidence of guest-source '?' support. It is intentionally small,
 * closed, deterministic, and independent from Java exception handling.
 */
public final class PostfixQuestionRoutes {
    private PostfixQuestionRoutes() { }

    public enum Operand {
        RESULT, OPTION, FUTURE, STREAM, OBSERVABLE, CHANNEL, OTHER
    }

    public enum ReturnType {
        RESULT, OPTION, OTHER
    }

    public enum Route {
        RETURN_ERR, RETURN_NONE, THROW, REJECT
    }

    public record Context(
            ReturnType returnType,
            String returnErrorType,
            boolean declaresThrows,
            String thrownType,
            boolean errorDerivesFromError) {
        public Context {
            Objects.requireNonNull(returnType, "return type");
            if (returnErrorType != null && returnErrorType.isBlank())
                throw new IllegalArgumentException("empty Result error type");
            if (thrownType != null && thrownType.isBlank())
                throw new IllegalArgumentException("empty thrown error type");
        }
    }

    /**
     * Exact nominal identity only, never string-widen/Any/erasure or implicit
     * exception conversion. A Result-return callable always chooses its return
     * route before considering a throws annotation; a mismatch is rejected.
     */
    public static Route resolve(Operand operand, String operandErrorType, Context context) {
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(context, "context");
        if (operand == Operand.OPTION)
            return context.returnType() == ReturnType.OPTION
                    ? Route.RETURN_NONE : Route.REJECT;
        if (operand != Operand.RESULT
                || operandErrorType == null || operandErrorType.isBlank()
                || operandErrorType.equals("Any") || operandErrorType.equals("Unknown"))
            return Route.REJECT;

        if (context.returnType() == ReturnType.RESULT) {
            return operandErrorType.equals(context.returnErrorType())
                    ? Route.RETURN_ERR : Route.REJECT;
        }
        return context.declaresThrows()
                && context.errorDerivesFromError()
                && operandErrorType.equals(context.thrownType())
                ? Route.THROW : Route.REJECT;
    }
}
