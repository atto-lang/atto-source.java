package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class HostImportTest {
    @Test
    void javaSourceAuthorityRequiresHostInteropAuthority() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                IsolatePolicy.developer().withCapabilities(
                        IsolatePolicy.Capability.JAVA_SOURCE_INTEROP));
        assertTrue(failure.getMessage().contains("JAVA_SOURCE_INTEROP requires JAVA_INTEROP"));
        assertDoesNotThrow(() -> IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.JAVA_SOURCE_INTEROP));
    }

    @Test
    void validatesJavaImportShapesAliasesAndCollisions() {
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

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                import class {ArrayList, LinkedList} as JList from "java:java.util.ArrayList";
                pub fnc main(): void { return; }
                """));
    }

    @Test
    void hostClassAllowlistRequiresDedicatedCapabilityAndAdversarialRejectsIt() {
        assertThrows(SecurityException.class, () ->
                IsolatePolicy.developer().restrictedContextBuilder(
                        ExecutionProfile.serverJit(), Set.of("java.lang.Math")));

        assertThrows(IllegalArgumentException.class, () ->
                IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP));
    }

    @Test
    void revokingHostAuthorityCannotLeaveJavaSourceAuthorityBehind() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.JAVA_SOURCE_INTEROP);
        assertThrows(IllegalArgumentException.class, () ->
                policy.withoutCapabilities(IsolatePolicy.Capability.JAVA_INTEROP));
        IsolatePolicy revoked = policy.withoutCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.JAVA_SOURCE_INTEROP);
        assertFalse(revoked.allows(IsolatePolicy.Capability.JAVA_INTEROP));
        assertFalse(revoked.allows(IsolatePolicy.Capability.JAVA_SOURCE_INTEROP));
        assertTrue(policy.allows(IsolatePolicy.Capability.JAVA_INTEROP));
        assertTrue(policy.allows(IsolatePolicy.Capability.JAVA_SOURCE_INTEROP));
    }

    @Test
    void sensitiveJdkClassesRequireExtraCapabilitiesOrStayBlocked() {
        IsolatePolicy javaOnly = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        assertThrows(SecurityException.class, () -> javaOnly.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.System")));
        assertThrows(SecurityException.class, () -> javaOnly.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Class")));
        assertThrows(SecurityException.class, () -> javaOnly.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.io.File")));

        IsolatePolicy filePolicy = javaOnly.withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);
        assertThrows(SecurityException.class, () -> filePolicy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.io.File")));
    }

    @Test
    void aliasesConstructsAndInvokesAllowlistedJavaClasses() {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.util.ArrayList")).build()) {
            Value value = context.eval(OresLanguage.ID, """
                    import class ArrayList as JArrayList from "java:java.util.ArrayList";
                    pub fnc main(): int {
                      val values = new JArrayList();
                      values.add(19);
                      values.add(23);
                      return values.size();
                    }
                    """);
            assertEquals(2L, value.asLong());
        }
    }

    @Test
    void aliasesNamedStaticFunctionsWithoutRenamingTheHostMember() {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            Value value = context.eval(OresLanguage.ID, """
                    import fnc abs as jabs from "java:java.lang.Math";
                    pub fnc main(): int { return jabs(-42); }
                    """);
            assertEquals(42L, value.asLong());
        }
    }

    @Test
    void exactAllowlistAndNoAccessInheritanceBlockEscalation() {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            assertThrows(PolyglotException.class, () -> context.eval(OresLanguage.ID, """
                    import class System from "java:java.lang.System";
                    pub fnc main(): int { return 1; }
                    """));
        }

        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            assertThrows(PolyglotException.class, () -> context.eval(OresLanguage.ID, """
                    import class Math as JMath from "java:java.lang.Math";
                    pub fnc main(): any { return JMath.getClass(); }
                    """));
        }
    }

    @Test
    void runtimePrivateActorPolicyAlsoStripsJavaInterop() throws Exception {
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
    void privateActorsCannotUseJavaInteropEvenWhenParentCan() {
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
        assertTrue(failure.getMessage().contains("JAVA_INTEROP"));
    }
    // The counter is initialized independently. Taking LazyHostProbe.class or
    // allowlisting its binary name does not initialize LazyHostProbe itself.
    private static final java.util.concurrent.atomic.AtomicInteger HOST_INITIALIZATIONS =
            new java.util.concurrent.atomic.AtomicInteger();

    public static final class LazyHostProbe {
        static { HOST_INITIALIZATIONS.incrementAndGet(); }
        public static int ping() { return 42; }
    }

    @Test
    void hostClassAndFunctionImportNeverInitializeClassBeforeExplicitUse() {
        String host = "dev.oreslang.HostImportTest$LazyHostProbe";
        assertEquals(0, HOST_INITIALIZATIONS.get());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of(host)).build()) {
            Value value = context.eval(OresLanguage.ID, """
                    import class LazyHostProbe from "java:dev.oreslang.HostImportTest$LazyHostProbe";
                    import fnc ping from "java:dev.oreslang.HostImportTest$LazyHostProbe";
                    pub fnc main(): int { return 7; }
                    """);
            assertEquals(7L, value.asLong());
            assertEquals(0, HOST_INITIALIZATIONS.get(),
                    "link and import must not resolve or initialize a host class");
        }
        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of(host)).build()) {
            Value value = context.eval(OresLanguage.ID, """
                    import fnc ping from "java:dev.oreslang.HostImportTest$LazyHostProbe";
                    pub fnc main(): int { return ping(); }
                    """);
            assertEquals(42L, value.asLong());
            assertEquals(1, HOST_INITIALIZATIONS.get(),
                    "first explicit call initializes the allowlisted host class once");
        }
    }

    @Test
    void missingHostFunctionIsRejectedOnlyWhenCalledNotDuringLink() {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            assertEquals(7L, context.eval(OresLanguage.ID, """
                    import fnc missingMethod from "java:java.lang.Math";
                    pub fnc main(): int { return 7; }
                    """).asLong());
        }
        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, """
                            import fnc missingMethod from "java:java.lang.Math";
                            pub fnc main(): int { return missingMethod(); }
                            """));
            assertTrue(failure.getMessage().contains("does not export invocable static member"),
                    failure.getMessage());
        }
    }

}
