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

    @Test
    void jvmAliasProducesTheSameHostClassAstAsLegacyJavaImport() {
        var legacy = Parser.parse("""
                import class ArrayList as JList from "java:java.util.ArrayList";
                pub fnc main(): int { val list = new JList(); return list.size(); }
                """);
        var qualified = Parser.parse("""
                import jvm "java.util.ArrayList" as JList;
                pub fnc main(): int { val list = new JList(); return list.size(); }
                """);
        assertEquals(legacy.imports(), qualified.imports(),
                "JVM shorthand must retain existing host capability rules");
        assertDoesNotThrow(() -> TypeChecker.check(qualified));
    }

    @Test
    void jvmAliasExecutesThroughExistingAllowlistAndCapabilityGate() {
        String source = """
                import jvm "java.util.ArrayList" as JList;
                pub fnc main(): int {
                    val list = new JList();
                    list.add(42);
                    return list.size();
                }
                """;
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.util.ArrayList")).build()) {
            assertEquals(1L, context.eval(OresLanguage.ID, source).asLong());
        }
        try (Context context = policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.Math")).build()) {
            assertThrows(PolyglotException.class, () -> context.eval(OresLanguage.ID, source));
        }
    }

    @Test
    void jvmImportRejectsInvalidBinaryNamesAndForeignHost() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                import jvm "java/lang/Math" as Bad;
                pub fnc main(): int { return 1; }
                """));
        assertThrows(RuntimeException.class, () -> Parser.parse("""
                import dotnet "System.Math" as Math;
                pub fnc main(): int { return 1; }
                """));
        assertThrows(RuntimeException.class, () -> Parser.parse("""
                import jvm "java.lang.Math";
                pub fnc main(): int { return 1; }
                """));
    }


    @Test
    void jvmAliasDoesNotGrantAuthorityWhenHostImportCapabilityIsMissing() {
        var program = TypeChecker.check(Parser.parse("""
                import jvm "java.util.ArrayList" as JList;
                pub fnc main(): int { val list = new JList(); return list.size(); }
                """));
        IsolatePolicy withoutHostAuthority = IsolatePolicy.developer();
        assertThrows(SecurityException.class, () ->
                CapabilityChecker.check(program, withoutHostAuthority));
    }

    @Test
    void privateActorCannotBypassHostRestrictionWithJvmAlias() {
        var program = TypeChecker.check(Parser.parse("""
                import jvm "java.util.ArrayList" as JList;
                isoactor fnc worker(): void {
                    val list = new JList();
                    return;
                }
                """));
        IsolatePolicy parentWithHostAuthority = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        SecurityException failure = assertThrows(SecurityException.class, () ->
                CapabilityChecker.check(program, parentWithHostAuthority));
        assertTrue(failure.getMessage().contains("JAVA_INTEROP"));
    }

    @Test
    void jvmImportParsingDoesNotLoadOrInitializeForeignClasses() {
        // Resolving a binary name to an AST is intentionally inert. Lazy host
        // initialization while *linking* is a separate gate (Java PR #1764).
        var program = Parser.parse("""
                import jvm "dev.oreslang.ThisClassDoesNotExist" as Absent;
                pub fnc main(): int { return 42; }
                """);
        assertEquals("java:dev.oreslang.ThisClassDoesNotExist",
                program.imports().getFirst().path());
    }

    @Test
    void jvmAliasCannotGrantAccessToSensitiveJdkClassEvenWhenNamedInHostAllowlist() {
        var parsed = Parser.parse("""
                import jvm "java.lang.System" as JSystem;
                pub fnc main(): int { return 42; }
                """);
        assertEquals("java:java.lang.System", parsed.imports().getFirst().path());
        assertDoesNotThrow(() -> TypeChecker.check(parsed),
                "syntax admission alone must not be confused with host authority");

        IsolatePolicy hostCap = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        assertThrows(SecurityException.class, () -> hostCap.restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of("java.lang.System")),
                "JAVA_INTEROP plus a requested host name cannot authorize System");
    }

}
