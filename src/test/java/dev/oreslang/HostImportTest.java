package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class HostImportTest {
    @Test
    void javaSourceAuthorityRemainsAHostInternalCapabilityPair() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                IsolatePolicy.developer().withCapabilities(
                        IsolatePolicy.Capability.JAVA_SOURCE_INTEROP));
        assertTrue(failure.getMessage().contains("JAVA_SOURCE_INTEROP requires JAVA_INTEROP"));
        assertDoesNotThrow(() -> IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.JAVA_SOURCE_INTEROP));
    }

    @Test
    void parserAndTypecheckerStillValidateInternalJavaImportShapes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import module Math from "java:java.lang.Math";
                pub fnc main(): void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import class Nope from "java:java.lang.Math";
                pub fnc main(): void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import class Math from "java:java/lang/Math";
                pub fnc main(): void { return; }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                import class ArrayList as JArrayList from "java:java.util.ArrayList";
                pub fnc main(): void {
                  val values = new JArrayList();
                  return;
                }
                """)));
    }

    @Test
    void hostClassAllowlistStillRequiresHostInternalAuthorityAndBlocksSensitiveClasses() {
        assertThrows(SecurityException.class, () ->
                IsolatePolicy.developer().restrictedContextBuilder(
                        ExecutionProfile.serverJit(), Set.of("java.lang.Math")));

        IsolatePolicy hostPolicy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        assertThrows(SecurityException.class, () -> hostPolicy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.System")));
        assertThrows(SecurityException.class, () -> hostPolicy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Class")));
        assertThrows(SecurityException.class, () -> hostPolicy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.io.File")));
    }

    @Test
    void guestJavaImportIsDeniedEvenWhenHostGrantsInteropAndExactClassAllowlist() {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.util.ArrayList")).build()) {
            PolyglotException denied = assertThrows(PolyglotException.class, () -> context.eval(
                    OresLanguage.ID,
                    """
                    import class ArrayList as JArrayList from "java:java.util.ArrayList";
                    pub fnc main(): int {
                      val values = new JArrayList();
                      return values.size();
                    }
                    """));
            assertTrue(denied.getMessage().contains("guest Oreslang cannot import Java/FFI host classes"),
                    denied.getMessage());
        }
    }

    @Test
    void guestFfiNamespaceIsDeniedEvenWhenHostPolicyCarriesFfiAuthority() {
        var program = Parser.parse("""
                pub fnc main(): void {
                  ffi.open();
                  return;
                }
                """);
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FFI);
        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, policy));
        assertTrue(denied.getMessage().contains("guest FFI namespace is disabled"));
    }

    @Test
    void runtimePrivateActorPolicyStillStripsHostInteropAuthority() throws Exception {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(
                        IsolatePolicy.Capability.JAVA_INTEROP,
                        IsolatePolicy.Capability.JAVA_SOURCE_INTEROP);

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            var ref = runtime.<String>spawnPrivate(factoryContext -> {
                if (factoryContext.policy().allows(IsolatePolicy.Capability.JAVA_INTEROP)) {
                    throw new AssertionError("private actor retained JAVA_INTEROP");
                }
                if (factoryContext.policy().allows(IsolatePolicy.Capability.JAVA_SOURCE_INTEROP)) {
                    throw new AssertionError("private actor retained JAVA_SOURCE_INTEROP");
                }
                return (message, context) -> context.self().stop();
            });

            ref.send("check");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void capabilityCheckerRejectsJavaImportsBeforeActorCapabilityInheritanceMatters() {
        var program = TypeChecker.check(Parser.parse("""
                import class ArrayList as JArrayList from "java:java.util.ArrayList";

                isoactor fnc worker(): void {
                  val values = new JArrayList();
                  return;
                }
                """));

        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, policy));
        assertTrue(failure.getMessage().contains("guest Java/FFI imports are disabled"));
    }
}
