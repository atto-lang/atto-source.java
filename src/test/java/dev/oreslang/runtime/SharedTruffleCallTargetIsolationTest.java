package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Execute the same cached Truffle source in two independently owned contexts. */
final class SharedTruffleCallTargetIsolationTest {
    private static final String PROGRAM = "pub routine main(): void { return; }";

    @Test
    void sharedRootCannotStoreAMutableContextBoundEvaluator() {
        assertFalse(Arrays.stream(OresEvalRootNode.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getSimpleName().equals("Evaluator")),
                "shared Truffle roots must not keep an evaluator from the first Context");
        assertTrue(Arrays.stream(OresEvalRootNode.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> Modifier.isFinal(field.getModifiers())),
                "shared Truffle roots should contain immutable program identity only");
    }

    @Test
    void sameSourceAndEngineDoNotShareMutableEvaluatorLifecycle() {
        Source source = Source.newBuilder(OresLanguage.ID, PROGRAM, "shared-root.ores")
                .mimeType(OresLanguage.MIME_TYPE).cached(true).buildLiteral();
        try (Engine engine = Engine.newBuilder(OresLanguage.ID).build()) {
            Context first = Context.newBuilder(OresLanguage.ID).engine(engine).build();
            Context second = Context.newBuilder(OresLanguage.ID).engine(engine).build();
            boolean firstClosed = false;
            try {
                assertSame(first.getEngine(), second.getEngine());
                assertNotSame(first, second);
                assertDoesNotThrow(() -> first.eval(source));
                assertDoesNotThrow(() -> second.eval(source));
                first.close();
                firstClosed = true;
                assertDoesNotThrow(() -> second.eval(source),
                        "retiring one Context must not invalidate another evaluator");
            } finally {
                if (!firstClosed) first.close(true);
                second.close(true);
            }
        }
    }
}
