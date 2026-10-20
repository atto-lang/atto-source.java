package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CallbackParameterEffectsTest {
    private static CallbackParameterEffects.Summary inferred(String source,
            String name, int arity, int param) {
        var facts = CallbackParameterEffects.infer(Parser.parse(source));
        return facts.get(new CallbackParameterEffects.Callable("__root__", name, arity, param));
    }

    @Test
    void directCallbackInvocationProducesPositiveInlineMayEffect() {
        var result = inferred("""
                fnc run(Callback cb): void { cb(); return; }
                """, "run", 1, 0);
        assertNotNull(result);
        assertTrue(result.mayInvokeInline());
        assertFalse(result.mayEscape());
        assertFalse(result.maySuspendOrDefer());
        // This is NOT a guarantee of call frequency or success.
    }

    @Test
    void forwardingCallbackRequiresUnknownTiming() {
        var result = inferred("""
                fnc run(Callback cb): void { execute(cb); return; }
                """, "run", 1, 0);
        assertTrue(result.mayEscape());
        assertFalse(result.mayInvokeInline());
    }

    @Test
    void lambdaCaptureMustNotBeInferredInlineOnly() {
        var result = inferred("""
                fnc run(Callback cb): void {
                    let fire = || -> { cb(); };
                    doMore(fire);
                    return;
                }
                """, "run", 1, 0);
        assertTrue(result.mayEscape(), result.toString());
    }

    @Test
    void awaitCannotCertifySameTurnExecution() {
        var result = inferred("""
                async fnc run(Callback cb, Future<int> pending): void {
                    await pending;
                    cb();
                    return;
                }
                """, "run", 2, 0);
        assertTrue(result.maySuspendOrDefer());
        assertTrue(result.mayEscape(), "callback use after await is not guaranteed inline");
    }

    @Test
    void summariesHaveOverloadArityAndParameterIdentity() {
        var facts = CallbackParameterEffects.infer(Parser.parse("""
                fnc run(Callback cb): void { cb(); return; }
                fnc run(Callback cb, int value): void { use(cb); return; }
                """));
        var first = facts.get(new CallbackParameterEffects.Callable("__root__", "run", 1, 0));
        var second = facts.get(new CallbackParameterEffects.Callable("__root__", "run", 2, 0));
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.mayInvokeInline());
        assertTrue(second.mayEscape());
    }
}
