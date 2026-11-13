package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * State-loan storage regression tests: use the actual private Truffle
 * state-view implementation, but no fake guest or fabricated actor turn.
 * Caller-turn admission is checked separately by actor guest suites.
 */
final class ActorSealedStateAtomicCommitTest {
    private static final class Harness {
        private final Object object;
        private final Class<?> viewClass;
        private final Method open;
        private final Method get;
        private final Method set;
        private final Method commit;
        private final Method revoke;
        private final Field fields;

        private Harness() throws Exception {
            Ast.Program parsed = Parser.parse("""
                    define actor Counter as
                      state {
                        revision: int = 0;
                        epoch: int = 1;
                      }
                    end
                    """);
            Ast.ClassDecl actor = parsed.modules().stream()
                    .flatMap(m -> m.declarations().stream())
                    .filter(Ast.ClassDecl.class::isInstance)
                    .map(Ast.ClassDecl.class::cast)
                    .findFirst().orElseThrow();

            Class<?> evaluator = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Evaluator");
            Class<?> objectClass = Class.forName("dev.oreslang.nodes.OresEvalRootNode$OresObject");
            Constructor<?> objectCtor = objectClass.getDeclaredConstructor(
                    evaluator, Ast.ClassDecl.class, Map.class);
            objectCtor.setAccessible(true);
            object = objectCtor.newInstance(null, actor,
                    new LinkedHashMap<>(Map.of("revision", 0L, "epoch", 1L)));

            fields = objectClass.getDeclaredField("fields");
            fields.setAccessible(true);
            viewClass = Class.forName("dev.oreslang.nodes.OresEvalRootNode$ActorStateLoanView");
            open = viewClass.getDeclaredMethod("open", objectClass, boolean.class);
            get = viewClass.getDeclaredMethod("get", String.class);
            set = viewClass.getDeclaredMethod("set", String.class, Object.class);
            commit = viewClass.getDeclaredMethod("commit");
            revoke = viewClass.getDeclaredMethod("revoke");
            for (Method method : new Method[]{open, get, set, commit, revoke}) {
                method.setAccessible(true);
            }
        }

        private Object loan(boolean writable) throws Exception {
            return open.invoke(null, object, writable);
        }

        @SuppressWarnings("unchecked")
        private Map<String,Object> state() throws Exception {
            return (Map<String,Object>) fields.get(object);
        }

        private static RuntimeException runtimeFailure(Method method, Object target,
                                                       Object... args) {
            try {
                method.invoke(target, args);
            } catch (InvocationTargetException expected) {
                if (expected.getCause() instanceof RuntimeException runtime) return runtime;
                throw new AssertionError("unexpected reflective target", expected.getCause());
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            throw new AssertionError("expected state-loan rejection");
        }
    }

    @Test
    void stagedWritesAreInvisibleUntilOnePublication() throws Exception {
        Harness h = new Harness();
        Object loan = h.loan(true);
        Map<String,Object> before = h.state();
        h.set.invoke(loan, "revision", 9L);
        h.set.invoke(loan, "epoch", 3L);
        assertEquals(9L, h.get.invoke(loan, "revision"));
        assertEquals(Map.of("revision", 0L, "epoch", 1L), h.state());
        assertSame(before, h.state(), "original actor-state snapshot still published");
        h.commit.invoke(loan);
        assertEquals(Map.of("revision", 9L, "epoch", 3L), h.state());
        assertNotSame(before, h.state(), "all staged values published by one map swap");
        h.revoke.invoke(loan);
    }

    @Test
    void failedCallbackRevokeDiscardsBothStagedWrites() throws Exception {
        Harness h = new Harness();
        Object loan = h.loan(true);
        h.set.invoke(loan, "revision", 4L);
        h.set.invoke(loan, "epoch", 8L);
        // Models the runtime finally path when callback throws before commit.
        h.revoke.invoke(loan);
        assertEquals(Map.of("revision", 0L, "epoch", 1L), h.state());
        assertThrows(IllegalStateException.class,
                () -> { throw Harness.runtimeFailure(h.commit, loan); });
    }

    @Test
    void readonlyLoanCanReadButNeverStageOrCommitWrites() throws Exception {
        Harness h = new Harness();
        Object read = h.loan(false);
        assertEquals(1L, h.get.invoke(read, "epoch"));
        assertInstanceOf(IllegalArgumentException.class,
                Harness.runtimeFailure(h.set, read, "revision", 1L));
        h.commit.invoke(read);
        assertEquals(Map.of("revision", 0L, "epoch", 1L), h.state());
        h.revoke.invoke(read);
    }

    @Test
    void revokedLoanCannotReadOrWriteState() throws Exception {
        Harness h = new Harness();
        Object loan = h.loan(true);
        h.revoke.invoke(loan);
        assertInstanceOf(IllegalStateException.class,
                Harness.runtimeFailure(h.get, loan, "revision"));
        assertInstanceOf(IllegalStateException.class,
                Harness.runtimeFailure(h.set, loan, "revision", 2L));
    }

    @Test
    void undeclaredStateFieldCannotEnterPublishedSnapshot() throws Exception {
        Harness h = new Harness();
        Object loan = h.loan(true);
        assertInstanceOf(IllegalArgumentException.class,
                Harness.runtimeFailure(h.set, loan, "injected", 100L));
        assertEquals(Map.of("revision", 0L, "epoch", 1L), h.state());
        h.revoke.invoke(loan);
    }
}
