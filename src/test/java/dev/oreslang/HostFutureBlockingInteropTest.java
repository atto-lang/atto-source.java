package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Compiled Oreslang guest programs must not receive Java's blocking Future ABI. */
final class HostFutureBlockingInteropTest {
    private static final String FIXTURE = "dev.oreslang.HostFutureInteropFixture";
    private static final IsolatePolicy POLICY = IsolatePolicy.developer()
            .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

    @Test
    void pendingRawFutureCannotEnterGuestCode() {
        rejectsOpaqueFuture("""
                import fnc pendingFuture as raw from "java:dev.oreslang.HostFutureInteropFixture";
                pub fnc main(): any {
                  return raw();
                }
                """);
    }

    @Test
    void completedRawFutureAlsoRejectedRatherThanExposingGetOrJoin() {
        rejectsOpaqueFuture("""
                import fnc completedFuture as raw from "java:dev.oreslang.HostFutureInteropFixture";
                pub fnc main(): any {
                  return raw();
                }
                """);
    }

    @Test
    void constructorReturningFutureTaskCannotBypassResultNormalization() {
        rejectsOpaqueFuture("""
                import class HostFutureInteropFixture as JFuture from "java:dev.oreslang.HostFutureInteropFixture";
                pub fnc main(): int {
                  val created = new JFuture();
                  return 1;
                }
                """);
    }

    @Test
    void realCompletionStageStillCrossesTheHostBoundaryWithoutBlocking() {
        // Java import return signatures currently have dynamic INSTANCE type.
        // They cannot be used as a statically typed Future<T> in an await
        // expression until compiler signature inference is implemented.
        // This exercises the runtime CompletionStage -> OresFuture conversion.
        try (Context context = POLICY.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of(FIXTURE)).build()) {
            var result = context.eval(OresLanguage.ID, """
                    import fnc completedStage as completed from "java:dev.oreslang.HostFutureInteropFixture";
                    pub fnc main(): int {
                      val stage = completed();
                      return 43;
                    }
                    """);
            assertEquals(43, result.asInt());
        }
    }

    private static void rejectsOpaqueFuture(String source) {
        try (Context context = POLICY.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of(FIXTURE)).build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, source));
            assertTrue(failure.getMessage().contains("opaque Java Future"),
                    "must fail at the Future interop boundary: " + failure.getMessage());
        }
    }
}
