package dev.oreslang;

import dev.oreslang.types.PostfixQuestionRoutes;
import org.junit.jupiter.api.Test;

import static dev.oreslang.types.PostfixQuestionRoutes.*;
import static org.junit.jupiter.api.Assertions.*;

final class PostfixQuestionRoutesTest {
    @Test
    void matchingResultErrorsReturnErrBeforeDeclaredThrows() {
        Context context = new Context(ReturnType.RESULT, "app.ParseError",
                true, "app.ParseError", true);
        assertEquals(Route.RETURN_ERR,
                PostfixQuestionRoutes.resolve(Operand.RESULT, "app.ParseError", context));
    }

    @Test
    void mismatchedResultErrorsNeverFallbackToThrow() {
        Context context = new Context(ReturnType.RESULT, "app.OtherError",
                true, "app.ParseError", true);
        assertEquals(Route.REJECT,
                PostfixQuestionRoutes.resolve(Operand.RESULT, "app.ParseError", context));
    }

    @Test
    void typedThrowsRequireErrorDerivedNominalType() {
        Context context = new Context(ReturnType.OTHER, null,
                true, "app.ParseError", true);
        assertEquals(Route.THROW,
                PostfixQuestionRoutes.resolve(Operand.RESULT, "app.ParseError", context));
        Context nonError = new Context(ReturnType.OTHER, null,
                true, "app.ParseError", false);
        assertEquals(Route.REJECT,
                PostfixQuestionRoutes.resolve(Operand.RESULT, "app.ParseError", nonError));
        Context unannotated = new Context(ReturnType.OTHER, null,
                false, "app.ParseError", true);
        assertEquals(Route.REJECT,
                PostfixQuestionRoutes.resolve(Operand.RESULT, "app.ParseError", unannotated));
    }

    @Test
    void unknownOrDifferentErrorTypeIsNeverGuessed() {
        Context context = new Context(ReturnType.RESULT, "app.ParseError",
                false, null, false);
        for (String operand : new String[] {"Unknown", "Any", "app.OtherError", ""}) {
            assertEquals(Route.REJECT,
                    PostfixQuestionRoutes.resolve(Operand.RESULT, operand, context));
        }
        assertEquals(Route.REJECT,
                PostfixQuestionRoutes.resolve(Operand.RESULT, null, context));
    }

    @Test
    void optionCanReturnNoneButCannotInventThrowError() {
        Context optional = new Context(ReturnType.OPTION, null,
                false, null, false);
        Context throwsOnly = new Context(ReturnType.OTHER, null,
                true, "app.Missing", true);
        assertEquals(Route.RETURN_NONE,
                PostfixQuestionRoutes.resolve(Operand.OPTION, null, optional));
        assertEquals(Route.REJECT,
                PostfixQuestionRoutes.resolve(Operand.OPTION, null, throwsOnly));
    }

    @Test
    void FutureStreamObservableAndChannelCannotImplicitlyPropagate() {
        Context result = new Context(ReturnType.RESULT, "app.ParseError",
                true, "app.ParseError", true);
        for (Operand operand : new Operand[] {
                Operand.FUTURE, Operand.STREAM, Operand.OBSERVABLE,
                Operand.CHANNEL, Operand.OTHER}) {
            assertEquals(Route.REJECT,
                    PostfixQuestionRoutes.resolve(operand, "app.ParseError", result));
        }
    }

    @Test
    void nullOrMalformedCompilerRoutingMetadataFailsClosed() {
        Context context = new Context(ReturnType.RESULT, "app.ParseError",
                false, null, false);
        assertThrows(NullPointerException.class,
                () -> PostfixQuestionRoutes.resolve(null, "app.ParseError", context));
        assertThrows(NullPointerException.class,
                () -> PostfixQuestionRoutes.resolve(Operand.RESULT, "app.ParseError", null));
        assertThrows(IllegalArgumentException.class,
                () -> new Context(ReturnType.RESULT, "  ", false, null, false));
    }
}
