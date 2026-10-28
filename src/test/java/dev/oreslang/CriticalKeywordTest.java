package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalKeywordTest {
    @Test void criticalModifiersPreserveFileAndInstanceEffects() {
        Ast.Program parsed = Parser.parse("""
                pub critical fnc file_counter(): int { return 1; }
                define actor Foo as
                    suspend critical critical_section(): int { return 3; }
                    receive(ActorMail<String> mail): void { return; }
                end
                """);
        Ast.ModuleDecl root = parsed.modules().getFirst();
        Ast.FunctionDecl file = (Ast.FunctionDecl) root.declarations().get(0);
        Ast.ClassDecl actor = (Ast.ClassDecl) root.declarations().get(1);
        Ast.MethodDecl method = actor.methods().getFirst();
        assertTrue(file.critical());
        assertFalse(file.suspends());
        assertTrue(method.critical());
        assertTrue(method.suspends());
        assertFalse(actor.methods().get(1).critical());
    }

    @Test void criticalIsNotSilentlyAllowedOnDataOrContainers() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical critical fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical generator fnc bad(): int { yield 1; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical define class Foo as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define actor Foo as critical let int value = 1; end"));
    }

    @Test void criticalDeclarationsCompileAndExecute() throws Exception {
        String code = """
                pub critical fnc file_value(): int { return 23; }
                define class Foo as
                  pub critical answer(): int { return 11; }
                end
                pub fnc main(): void {
                    val foo = new Foo();
                    stdio.stdout.write(foo.answer());
                    stdio.stdout.write("|");
                    stdio.stdout.write(file_value());
                    return;
                }
                """;
        dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(code);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        try (org.graalvm.polyglot.Context context =
                     org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                             .allowAllAccess(false).out(stdout).build()) {
            context.eval(org.graalvm.polyglot.Source
                    .newBuilder(OresLanguage.ID, code, "critical-run.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("11|23", stdout.toString().strip());
    }

    @Test void suspendCriticalRetainsEffectAcrossAwaitAndReturnsWithoutHostLocks()
            throws Exception {
        String code = """
                async fnc obtain(): int { return 37; }
                suspend critical fnc guarded(): int {
                    return (await obtain()) + 5;
                }
                async fnc outer(): int { return guarded(); }
                pub fnc main(): void {
                    stdio.stdout.write(await outer());
                    return;
                }
                """;
        dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(code);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        try (org.graalvm.polyglot.Context context =
                     org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                             .allowAllAccess(false).out(stdout).build()) {
            context.eval(org.graalvm.polyglot.Source
                    .newBuilder(OresLanguage.ID, code, "critical-suspend.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("42", stdout.toString().strip());
    }

    @Test void criticalCanAppearAfterVisibilityOrBeforeStatic() {
        Ast.Program parsed = Parser.parse("""
                define class Foo as
                    pub critical run(): int { return 1; }
                    critical static fnc value(): int { return 2; }
                end
                """);
        Ast.ClassDecl owner = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        assertTrue(owner.methods().get(0).critical());
        assertTrue(owner.methods().get(1).critical());
    }

    @Test void uncontendedSuspendCriticalWithNoBodyAwaitStillCompletes() throws Exception {
        // An uncontended suspends critical callable may start inline without
        // forcing a scheduler turn; its return/effect contract remains stable.
        String code = """
                pub critical fnc synchronous(): int { return 6; }
                suspend critical fnc guaranteed_handoff(): int { return 7; }
                async fnc outer(): int { return guaranteed_handoff(); }
                pub fnc main(): void {
                    stdio.stdout.write(synchronous());
                    stdio.stdout.write("|");
                    stdio.stdout.write(await outer());
                    return;
                }
                """;
        dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(code);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        try (org.graalvm.polyglot.Context context =
                     org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                             .allowAllAccess(false).out(stdout).build()) {
            context.eval(org.graalvm.polyglot.Source
                    .newBuilder(OresLanguage.ID, code, "critical-guaranteed-handoff.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("6|7", stdout.toString().strip());
    }


    @Test void criticalQueueTimeoutAnnotationIsStaticallyChecked() {
        String valid = """
                @Critical(queue_timeout_ms=50)
                pub critical suspends fnc permitted(): int { return 7; }
                """;
        Ast.Program checked = dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(valid);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) checked.modules().getFirst()
                .declarations().getFirst();
        var policy = dev.oreslang.ast.CriticalAnnotationPolicy.from(
                fn.annotations(), fn.critical(), fn.name());
        assertEquals(java.time.Duration.ofMillis(50), policy.queueTimeout());

        String immediateOnly = """
                @Critical(queue_timeout_ms=0)
                pub critical fnc immediate(): int { return 1; }
                """;
        assertDoesNotThrow(() -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(immediateOnly));
    }

    @Test void criticalPolicyMustNotSilentlyIgnoreUnsafeFields() {
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        @Critical(queue_timeout_ms=10)
                        pub fnc missing_modifier(): void { return; }
                        """));
        assertDoesNotThrow(
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        @Critical(execution_timeout_ms=50, total_timeout_ms=100)
                        pub critical suspends fnc bounded(): void { return; }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        @Critical(total_timeout_ms=10)
                        pub critical fnc non_preemptible(): void { return; }
                        """));
        assertDoesNotThrow(
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        @Critical(channel_wait_timeout_ms=10)
                        pub critical suspends fnc allowed_channel(): void { return; }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        @Critical(channel_wait_timeout_ms=10)
                        pub critical fnc no_suspending_effect(): void { return; }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        @Critical(queue_timeout_ms=10, queue_timeout_ms=20)
                        pub critical fnc duplicate(): void { return; }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        @Critical(queue_timeout_ms="ten")
                        pub critical fnc non_numeric(): void { return; }
                        """));
    }

    @Test void criticalChannelDeadlineIsPreservedInCheckedSourceSignature() {
        Ast.Program program = dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                @Critical(queue_timeout_ms=50, channel_wait_timeout_ms=15)
                pub critical suspends fnc receive(Channel<int> input): int {
                    return await (nb readch input);
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().getFirst();
        var policy = dev.oreslang.ast.CriticalAnnotationPolicy.from(
                fn.annotations(), fn.critical(), fn.suspends() || fn.async(), fn.name());
        assertEquals(java.time.Duration.ofMillis(50), policy.queueTimeout());
        assertEquals(java.time.Duration.ofMillis(15), policy.channelWaitTimeout());
    }

    @Test void sourceCriticalExecutionAndTotalBudgetsAreStaticallyPreserved() {
        Ast.Program checked = dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                @Critical(queue_timeout_ms=25, execution_timeout_ms=70,
                          total_timeout_ms=90, channel_wait_timeout_ms=40)
                pub critical suspends fnc bounded(Channel<int> input): int {
                    return await (nb readch input);
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) checked.modules().getFirst()
                .declarations().getFirst();
        var policy = dev.oreslang.ast.CriticalAnnotationPolicy.from(
                fn.annotations(), fn.critical(), fn.suspends() || fn.async(), fn.name());
        assertEquals(java.time.Duration.ofMillis(25), policy.queueTimeout());
        assertEquals(java.time.Duration.ofMillis(40), policy.channelWaitTimeout());
        assertEquals(java.time.Duration.ofMillis(70), policy.executionTimeout());
        assertEquals(java.time.Duration.ofMillis(90), policy.totalTimeout());
    }

    @Test void criticalPolicyAppliesToInstanceMethodsAsWellAsFileFunctions() {
        Ast.Program checked = dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                define class Box as
                    @Critical(queue_timeout_ms=15)
                    pub critical get(): int { return 1; }
                end
                """);
        Ast.ClassDecl klass = (Ast.ClassDecl) checked.modules().getFirst()
                .declarations().getFirst();
        var method = klass.methods().getFirst();
        var policy = dev.oreslang.ast.CriticalAnnotationPolicy.from(
                method.annotations(), method.critical(), method.name());
        assertEquals(java.time.Duration.ofMillis(15), policy.queueTimeout());
    }

}
