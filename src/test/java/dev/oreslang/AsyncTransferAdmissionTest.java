package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.AsyncTransferProvenance;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncTransferAdmissionTest {
    private static Ast.FunctionDecl function(
            Ast.Program program, String name) {
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Ast.CallExpr bindingCall(
            Ast.Program program, String functionName, int bodyIndex) {
        Ast.BindingStmt binding = (Ast.BindingStmt)
                function(program, functionName).body().get(bodyIndex);
        Ast.Expr initializer = binding.initializer();
        if (initializer instanceof Ast.AwaitExpr awaited) {
            initializer = awaited.expression();
        }
        return (Ast.CallExpr) initializer;
    }

    @Test
    void combinedAdmissionRecordsUniqueMoveAndCopyAtExactAsyncCall() {
        Ast.Program program = Parser.parse("""
                define module app
                  define class Box as
                    pub let int value = 1;
                  end

                  async fnc consume(Box box, int n): int {
                    return n;
                  }

                  pub fnc main(): void {
                    let original = new Box();
                    val pending = consume(original, 7);
                    return;
                  }
                end
                """);
        Ast.CallExpr call = bindingCall(program, "main", 1);

        OresCompiler.AsyncAdmissionResult checked =
                OresCompiler.analyzeForAsyncLowering(program);
        AsyncTransferProvenance proof = checked.asyncTransfers();

        assertEquals(
                OresCompiler.AsyncReleaseBarrier.TASK_SCOPE_GET_DONE,
                checked.releaseBarrier());
        assertTrue(proof.sealed());
        assertEquals(1, proof.callCount());
        assertEquals(2, proof.transferCount());
        assertEquals(
                AsyncTransferProvenance.TransferKind.UNIQUE_MOVE,
                proof.transfersFor(call).get(0).kind());
        assertEquals(
                AsyncTransferProvenance.TransferKind.COPY,
                proof.transfersFor(call).get(1).kind());

        Ast.CallExpr lookalike =
                new Ast.CallExpr(call.callee(), call.arguments());
        assertFalse(proof.contains(lookalike));
        assertTrue(proof.transfersFor(lookalike).isEmpty());
    }

    @Test
    void freshConstructionCanMintOwnedRvalueAdmission() {
        Ast.Program program = Parser.parse("""
                define module app
                  define class Box as
                    pub let int value = 1;
                  end

                  async fnc consume(Box box): int {
                    return box.value;
                  }

                  pub fnc main(): void {
                    val pending = consume(new Box());
                    return;
                  }
                end
                """);
        Ast.CallExpr call = bindingCall(program, "main", 0);

        OresCompiler.AsyncAdmissionResult checked =
                OresCompiler.analyzeForAsyncLowering(program);

        assertEquals(
                AsyncTransferProvenance.TransferKind.OWNED_RVALUE,
                checked.asyncTransfers().transfersFor(call).getFirst().kind());
    }

    @Test
    void staticAsyncMethodMintsTheSameExactCallProof() {
        Ast.Program program = Parser.parse("""
                define module app
                  define class Box as
                    pub let int value = 1;
                  end

                  define class Ops as
                    pub static async fnc consume(Box box): int {
                      return box.value;
                    }
                  end

                  pub fnc main(): void {
                    let original = new Box();
                    val pending = Ops.consume(original);
                    return;
                  }
                end
                """);
        Ast.CallExpr call = bindingCall(program, "main", 1);

        OresCompiler.AsyncAdmissionResult checked =
                OresCompiler.analyzeForAsyncLowering(program);

        assertEquals(1, checked.asyncTransfers().callCount());
        assertEquals(
                AsyncTransferProvenance.TransferKind.UNIQUE_MOVE,
                checked.asyncTransfers().transfersFor(call).getFirst().kind());
    }

    @Test
    void copyCallResultUsesResolvedAsyncParameterType() {
        Ast.Program program = Parser.parse("""
                define module app
                  fnc make_int(): int {
                    return 9;
                  }

                  async fnc consume(int n): int {
                    return n;
                  }

                  pub fnc main(): void {
                    val pending = consume(make_int());
                    return;
                  }
                end
                """);
        Ast.CallExpr call = bindingCall(program, "main", 0);

        OresCompiler.AsyncAdmissionResult checked =
                OresCompiler.analyzeForAsyncLowering(program);

        assertEquals(
                AsyncTransferProvenance.TransferKind.COPY,
                checked.asyncTransfers().transfersFor(call).getFirst().kind());
        assertEquals(
                "int",
                checked.asyncTransfers().transfersFor(call).getFirst()
                        .type().name());
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void hostileFreshRvalueDepthFailsClosed() {
        String type = "int";
        String expression = "1";
        for (int i = 0; i < 70; i++) {
            type = "Array<" + type + ">";
            expression = "arr[" + expression + "]";
        }
        String source = """
                define module app
                  async fnc consume(TYPE value): int {
                    return 1;
                  }

                  pub fnc main(): void {
                    val pending = consume(EXPR);
                    return;
                  }
                end
                """
                .replace("TYPE", type)
                .replace("EXPR", expression);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(
                failure.getMessage().contains("rvalue provenance"),
                failure::getMessage);
    }

    @Test
    void arbitraryCallResultCannotSelfMintAsyncOwnership() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          define class Box as
                            pub let int value = 1;
                          end

                          fnc make(): Box {
                            return new Box();
                          }

                          async fnc consume(Box box): int {
                            return box.value;
                          }

                          pub fnc main(): void {
                            val pending = consume(make());
                            return;
                          }
                        end
                        """));
        assertTrue(
                failure.getMessage().contains("rvalue provenance"),
                failure::getMessage);
    }

    @Test
    void arbitraryCallHiddenInsideFreshAggregateStillFailsClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          define class Box as
                            pub let int value = 1;
                          end

                          fnc make(): Box {
                            return new Box();
                          }

                          async fnc consume(Array<Box> boxes): int {
                            return 1;
                          }

                          pub fnc main(): void {
                            val pending = consume(arr[make()]);
                            return;
                          }
                        end
                        """));
        assertTrue(
                failure.getMessage().contains("rvalue provenance"),
                failure::getMessage);
    }

    @Test
    void firstClassAsyncCallFailsClosedWithoutOwnershipCallableMetadata() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          define class Box as
                            pub let int value = 1;
                          end

                          async fnc consume(Box box): int {
                            return box.value;
                          }

                          pub fnc main(): void {
                            let original = new Box();
                            val action = consume;
                            val pending = action(original);
                            return;
                          }
                        end
                        """));
        assertTrue(
                failure.getMessage().contains("async lowering admission"),
                failure::getMessage);
    }

    @Test
    void directTypeCheckerFullAdmissionAlsoRejectsProofMismatch() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Box as
                            pub let int value = 1;
                          end

                          async fnc consume(Box box): int {
                            return box.value;
                          }

                          pub fnc main(): void {
                            let original = new Box();
                            val action = consume;
                            val pending = action(original);
                            return;
                          }
                        end
                        """)));
        assertTrue(
                failure.getMessage().contains("async lowering admission"),
                failure::getMessage);
    }

    @Test
    void semanticAsyncBoundaryFailureProducesNoAdmissionResult() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.analyzeForAsyncLowering(
                        Parser.parse("""
                            define module app
                              async fnc bad(Future<int> pending): int {
                                return 1;
                              }
                            end
                            """)));
        assertTrue(
                failure.getMessage().contains("async callable"),
                failure::getMessage);
    }
}
