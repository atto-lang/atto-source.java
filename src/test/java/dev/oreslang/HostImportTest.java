package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class HostImportTest {
    @Test
    void validatesJavaImportShapesAndCollisions() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import module Math from "java:java.lang.Math";
                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import class Nope from "java:java.lang.Math";
                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import class Math from "java:java/lang/Math";
                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import fnc main from "java:java.lang.Math";
                pub fnc main() => void { return; }
                """)));
    }

    @Test
    void hostClassAllowlistRequiresFfiAndIsDisabledForAdversarialContexts() {
        assertThrows(SecurityException.class, () ->
                IsolatePolicy.developer().restrictedContextBuilder(
                        ExecutionProfile.serverJit(), Set.of("java.lang.Math")));

        assertThrows(SecurityException.class, () ->
                IsolatePolicy.strictFaas()
                        .withCapabilities(IsolatePolicy.Capability.FFI)
                        .restrictedContextBuilder(
                                ExecutionProfile.serverJit(), Set.of("java.lang.Math")));
    }

    @Test
    void importsAllowlistedStaticFunctionAndClassNamespace() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.FFI);

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            Value direct = context.eval(OresLanguage.ID, """
                    import fnc abs from "java:java.lang.Math";
                    pub fnc main() => int { return abs(-42); }
                    """);
            assertEquals(42L, direct.asLong());
        }

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            Value namespaced = context.eval(OresLanguage.ID, """
                    import class Math from "java:java.lang.Math";
                    pub fnc main() => int { return Math.max(40, 2); }
                    """);
            assertEquals(40L, namespaced.asLong());
        }
    }

    @Test
    void wildcardJavaImportStaysNamespaced() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.FFI);
        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            Value value = context.eval(OresLanguage.ID, """
                    import * as JMath from "java:java.lang.Math";
                    pub fnc main() => int { return JMath.min(7, 3); }
                    """);
            assertEquals(3L, value.asLong());
        }
    }

    @Test
    void classLookupFilterAndHostAccessBlockUnapprovedOrInheritedReflection() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.FFI);

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            assertThrows(PolyglotException.class, () -> context.eval(OresLanguage.ID, """
                    import class System from "java:java.lang.System";
                    pub fnc main() => int { return 1; }
                    """));
        }

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            assertThrows(PolyglotException.class, () -> context.eval(OresLanguage.ID, """
                    import class Math from "java:java.lang.Math";
                    pub fnc main() => any { return Math.getClass(); }
                    """));
        }
    }
}
