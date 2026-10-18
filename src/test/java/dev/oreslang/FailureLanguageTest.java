package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.FailureAnalysis;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FailureLanguageTest {
    @Test void signaturesAndMetadataSurviveCompilerPasses() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                fnc value(): int throws raises panics { return 1; }
                define class Example as
                  pub run(): int raises { return 2; }
                end
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Set.of(Ast.FailureEffect.THROWS, Ast.FailureEffect.RAISES, Ast.FailureEffect.PANICS), fn.failureEffects());
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().get(1);
        assertEquals(Set.of(Ast.FailureEffect.RAISES), klass.methods().getFirst().failureEffects());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("fnc f(): void throws throws {}"));
    }

    @Test void throwIsCaughtAndTrapped() throws Exception {
        assertEquals("caught:true", run("""
                fnc fail(): void throws { throw "failed"; }
                trap fnc safe(): int throws { throw "failed"; return 0; }
                pub fnc main(): void {
                  try { fail(); } catch (error) { print("caught:"); }
                  print(safe().is_none());
                }
                """));
    }

    @Test void raiseBypassesCatchAndTrapAndRecoversInDefer() throws Exception {
        assertEquals("cleanup:true:done", run("""
                trap fnc fail(): void raises { raise "raised"; }
                fnc work(): void {
                  defer || -> { print("cleanup:"); print(recover().is_some()); print(":"); };
                  try { fail(); } catch (error) { print("WRONG"); }
                  print("WRONG");
                }
                pub fnc main(): void { work(); print("done"); }
                """));
    }

    @Test void recoveryIsScopedAndConsumedOnce() throws Exception {
        assertEquals("false:true:false:done", run("""
                fnc work(): void {
                  defer || -> { print(recover().is_some()); print(":"); print(recover().is_some()); print(":"); };
                  raise 4;
                }
                pub fnc main(): void { print(recover().is_some()); print(":"); work(); print("done"); }
                """));
    }

    @Test void panicCannotBeConsumedByCatchTrapOrRecovery() {
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                trap fnc fail(): void panics { panic "fatal"; }
                pub fnc main(): void {
                  defer || -> { recover(); };
                  try { fail(); } catch (error) { print("WRONG"); }
                }
                """));
        assertTrue(failure.getMessage().contains("fatal"), failure::getMessage);
    }

    @Test void diagnosticsPropagateRecursionAndHandleOnlyThrow() {
        var diagnostics = FailureAnalysis.analyze(Parser.parse("""
                fnc leaf(): void throws raises panics {}
                fnc a(): void { b(); }
                fnc b(): void { a(); leaf(); }
                fnc caught(): void { try { leaf(); } catch (error) {} }
                trap fnc trapped(): void { leaf(); }
                """));
        assertTrue(diagnostics.stream().anyMatch(d -> d.symbol().contains(".a/") && d.message().contains("throws raises panics")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.symbol().contains(".caught/") && d.message().contains("raises panics") && !d.message().contains("throws")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.symbol().contains(".trapped/") && !d.message().contains("throws")));
    }

    @Test void raiseRecoverySurvivesAwaitAndRunsRemainingDefers() throws Exception {
        assertEquals("true:remaining:done", run("""
                async fnc pause(): void { return; }
                async fnc work(): void raises {
                  defer || -> { print("remaining:"); };
                  defer || -> { print(recover().is_some()); print(":"); };
                  await pause();
                  raise "raised after await";
                }
                pub async fnc main(): void { await work(); print("done"); }
                """));
    }

    @Test void panicInActorBypassesLocalCatchAndRecover() {
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void panics {
                    defer || -> { recover(); };
                    try { panic "actor-fatal"; } catch (error) { print("WRONG"); }
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("fail");
                  await worker.get_done();
                }
                """));
        assertTrue(failure.getMessage().contains("actor-fatal"), failure::getMessage);
    }

    @Test void cleanupFailureAfterRecoveryBecomesThePendingFailure() {
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                fnc work(): void {
                  defer || -> { recover(); panic "cleanup-fatal"; };
                  raise "original";
                }
                pub fnc main(): void { work(); }
                """));
        assertTrue(failure.getMessage().contains("cleanup-fatal"), failure::getMessage);
    }

    @Test void failurePayloadCannotLeakAnActorOwnedObject() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Payload as end
                fnc fail(): void raises { raise new Payload(); }
                """));
    }

    private static String run(String source) throws Exception {
        OresCompiler.parseAndTypeCheck(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(output).build()) {
            context.eval(Source.newBuilder("ores", source, "failure-test.ores").build());
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
