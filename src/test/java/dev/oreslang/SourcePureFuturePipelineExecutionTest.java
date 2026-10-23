package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guest execution assertions for the restricted initial lowering path.
 * The parser-only frontend checks are not substitutes for these tests.
 */
final class SourcePureFuturePipelineExecutionTest {
    @Test
    void pureFutureChainRunsInOrderAndFlattensToOneResultFuture() throws Exception {
        assertEquals("44", run("""
                async fnc source(): int { return 21; }
                fnc increment(int value): int { return value + 1; }
                fnc twice(int value): int { return value * 2; }
                pub async routine main(): void {
                  val Future<int> pending = source() |> increment() |> twice();
                  val int result = await pending;
                  stdio.println(result);
                  return;
                }
                """, "pure-future-pipe-chain.ores").trim());
    }

    @Test
    void directFutureArgumentIsNotImplicitlyLifted() throws Exception {
        assertEquals("7", run("""
                async fnc source(): int { return 21; }
                fnc observe(Future<int> pending): int { return 7; }
                pub async routine main(): void {
                  stdio.println(observe(source()));
                  return;
                }
                """, "pure-future-pipe-direct-argument.ores").trim());
    }

    @Test
    void trapAwaitCoversFailedAsynchronousProducerWithoutReclassifyingResult() throws Exception {
        assertEquals("true", run("""
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                async fnc failed(): int {
                  rt cooperate;
                  val Animal animal = new Cat();
                  val Dog dog = animal as Dog;
                  return 1;
                }
                fnc increment(int value): int { return value + 1; }

                pub fnc main(): void {
                  val Option<int> none = trap await (failed() |> increment());
                  stdio.stdout.write(none.is_none());
                  return;
                }
                """, "pure-future-pipe-upstream-failure.ores"));
    }

    @Test
    void scalarPipeStillRunsEagerlyAndProducesScalarValue() throws Exception {
        assertEquals("22", run("""
                fnc increment(int value): int { return value + 1; }
                pub fnc main(): void {
                  val int value = 21 |> increment();
                  stdio.stdout.write(value);
                  return;
                }
                """, "pure-future-pipe-scalar.ores"));
    }

    @Test
    void compilerAdmissionIncludesOwnershipPassForPureFuturePipeline() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                        async fnc source(): int { return 21; }
                        fnc increment(int value): int { return value + 1; }
                        pub fnc main(): void {
                          val Future<int> pending = source() |> increment();
                          val Option<int> result = trap await (pending);
                          return;
                        }
                        """));
    }

    @Test
    void checkedPlanDoesNotTransferToReconstructedCall() {
        Ast.Program typed = TypeChecker.check(Parser.parse("""
                async fnc source(): int { return 1; }
                fnc increment(int value): int { return value + 1; }
                fnc main(): void {
                  val Future<int> pending = source() |> increment();
                  return;
                }
                """));
        Ast.FunctionDecl main = (Ast.FunctionDecl) typed.modules().getFirst()
                .declarations().get(2);
        Ast.CallExpr pipe = (Ast.CallExpr) ((Ast.BindingStmt) main.body().getFirst()).initializer();
        assertTrue(TypeChecker.CheckedPureFuturePipe.lookup(pipe));
        Ast.CallExpr rebuilt = pipe.rewrite(pipe.callee(), pipe.typeArguments(),
                pipe.typeArgumentsPresent(), pipe.arguments());
        assertFalse(TypeChecker.CheckedPureFuturePipe.lookup(rebuilt));
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, name)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
