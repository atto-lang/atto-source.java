package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ScopedTaskBorrowPermitTest {

    @Test
    void compilerEmitsOpaqueRegionProofForDirectSharedBorrow() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    let int value = 42;
                    val ref = rt borrow value;
                    nb scope {
                        let worker = nb {
                            stdio.stdout.write(ref);
                        };
                    }
                    return;
                }
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().getFirst();
        Ast.TaskScopeStmt scope = (Ast.TaskScopeStmt) main.body().get(2);
        Ast.BindingStmt worker = (Ast.BindingStmt) scope.body().getFirst();
        Ast.TaskExpr task = (Ast.TaskExpr) worker.initializer();

        assertEquals(1, task.captureProofs().size());
        Ast.TaskCaptureProof proof = task.captureProofs().getFirst();
        assertEquals("ref", proof.name());
        assertEquals(Ast.TaskCaptureMode.SCOPED_READ_BORROW, proof.mode());
        assertTrue(proof.regionId() > 0L);
        assertEquals(0L, proof.regionGeneration());
        assertEquals("$", proof.accessPath());
    }


    @Test
    void rebindingValueKeepsTheSameLexicalStorageRegionGeneration() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    let int value = 40;
                    nb scope {
                        val ref = rt borrow value;
                        let first = nb {
                            stdio.stdout.write(ref);
                        };
                    }
                    value = 41;
                    nb scope {
                        val ref = rt borrow value;
                        let second = nb {
                            stdio.stdout.write(ref);
                        };
                    }
                    return;
                }
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().getFirst();
        Ast.TaskScopeStmt firstScope = (Ast.TaskScopeStmt) main.body().get(1);
        Ast.TaskScopeStmt secondScope = (Ast.TaskScopeStmt) main.body().get(3);

        Ast.TaskExpr firstTask = (Ast.TaskExpr) ((Ast.BindingStmt)
                firstScope.body().get(1)).initializer();
        Ast.TaskExpr secondTask = (Ast.TaskExpr) ((Ast.BindingStmt)
                secondScope.body().get(1)).initializer();

        Ast.TaskCaptureProof first = firstTask.captureProofs().getFirst();
        Ast.TaskCaptureProof second = secondTask.captureProofs().getFirst();

        assertEquals(first.regionId(), second.regionId(),
                "rebinding a value does not replace its lexical storage region");
        assertEquals(first.regionGeneration(), second.regionGeneration(),
                "generation changes only when the underlying region is actually reused");
        assertEquals(0L, first.regionGeneration());
    }

    @Test
    void scopedReadBorrowSurvivesChildAwaitAndCarrierMigration() throws Exception {
        String source = """
                async fnc origin(): int {
                    return 2;
                }

                pub routine main(): void {
                    let int value = 40;
                    val ref = rt borrow value;
                    nb scope {
                        let worker = nb {
                            let int suffix = await origin();
                            stdio.stdout.write(ref + suffix);
                        };
                    }
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }


    @Test
    void scopedReadCaptureRequiresSyncReferent() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc origin(): int {
                            return 42;
                        }

                        pub routine main(): void {
                            val Future<int> pending = origin();
                            val ref = rt borrow pending;
                            nb scope {
                                let worker = nb {
                                    ref;
                                };
                            }
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("sync")
                || message.contains("thread-affine")
                || message.contains("future"), message);
    }

    @Test
    void borrowedCaptureOutsideStructuredScopeStillFailsClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 42;
                            val ref = rt borrow value;
                            let worker = nb {
                                stdio.stdout.write(ref);
                            };
                            await worker;
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("nb scope") || message.contains("borrow"), message);
    }

    @Test
    void mutableBorrowCaptureRemainsDisabled() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Box as
                          pub let int value = 42;
                        end

                        pub routine main(): void {
                            val Box box = new Box();
                            val ref = rt mut borrow box;
                            nb scope {
                                let worker = nb {
                                    stdio.stdout.write(ref.value);
                                };
                            }
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("read-only")
                || message.contains("mutable")
                || message.contains("borrow"), message);
    }

    @Test
    void projectedBorrowCaptureRemainsDisabledUntilPathProofsLand() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Item as
                          pub val int number;
                        end

                        define class Holder as
                          pub let mut List<Item> items;
                        end

                        pub routine main(): void {
                            val List<Item> values = new List<Item>();
                            values.add(new Item(42));
                            val Holder holder = new Holder(values);
                            val borrow Item view = rt borrow holder.items[0];
                            nb scope {
                                let worker = nb {
                                    stdio.stdout.write(view.number);
                                };
                            }
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("projected")
                || message.contains("path-disjoint"), message);
    }

    @Test
    void borrowedTaskResultCannotEscapeTheStructuredScope() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 42;
                            val ref = rt borrow value;
                            nb scope {
                                let worker = nb {
                                    return ref;
                                };
                            }
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("return")
                || message.contains("borrow")
                || message.contains("outlive"), message);
    }

    @Test
    void ownedCaptureStillCarriesNoRegionAuthority() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    val int value = 42;
                    nb scope {
                        let worker = nb {
                            stdio.stdout.write(value);
                        };
                    }
                    return;
                }
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().getFirst();
        Ast.TaskScopeStmt scope = (Ast.TaskScopeStmt) main.body().get(1);
        Ast.BindingStmt worker = (Ast.BindingStmt) scope.body().getFirst();
        Ast.TaskExpr task = (Ast.TaskExpr) worker.initializer();

        Ast.TaskCaptureProof proof = task.captureProofs().getFirst();
        assertEquals(Ast.TaskCaptureMode.OWNED_SEND, proof.mode());
        assertEquals(0L, proof.regionId());
        assertEquals(0L, proof.regionGeneration());
        assertEquals("", proof.accessPath());
    }


    @Test
    void uncapturedSiblingBorrowOfSameRegionCannotRideAnotherPermit() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 42;
                            val permitted = rt borrow value;
                            val ordinary = rt borrow value;
                            nb scope {
                                let worker = nb {
                                    stdio.stdout.write(permitted);
                                };
                            }
                            stdio.stdout.write(ordinary);
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("uncaptured")
                || message.contains("ordinary borrow")
                || message.contains("scope"), message);
    }

    @Test
    void parentCannotMutateRegionWhileScopedReadChildMayStillUseIt() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 41;
                            val ref = rt borrow value;
                            nb scope {
                                let worker = nb {
                                    stdio.stdout.write(ref);
                                };
                                value = 42;
                            }
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("borrow"), message);
    }

    @Test
    void deferredLambdaCannotInheritLexicalScopeBorrowAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            nb scope {
                                val (() => void) later = || -> {
                                    let int value = 42;
                                    val ref = rt borrow value;
                                    let worker = nb {
                                        stdio.stdout.write(ref);
                                    };
                                    return;
                                };
                            }
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("deferred")
                || message.contains("escaping")
                || message.contains("scope"), message);
    }

    @Test
    void regionIdsAreDeterministicPerCompilationTraversal() {
        String source = """
                pub routine main(): void {
                    let int value = 42;
                    val ref = rt borrow value;
                    nb scope {
                        let worker = nb {
                            stdio.stdout.write(ref);
                        };
                    }
                    return;
                }
                """;

        Ast.Program firstProgram = OresCompiler.parseAndTypeCheck(source);
        Ast.Program secondProgram = OresCompiler.parseAndTypeCheck(source);

        Ast.TaskCaptureProof first = firstCapture(firstProgram);
        Ast.TaskCaptureProof second = firstCapture(secondProgram);

        assertEquals(first.regionId(), second.regionId());
        assertEquals(1L, first.regionId());
        assertEquals(first.regionGeneration(), second.regionGeneration());
    }


    @Test
    void nestedTaskPreservesScopedReadRegionIdentityAcrossProjection() throws Exception {
        String source = """
                async fnc suffix(): int {
                    return 2;
                }

                pub routine main(): void {
                    let int value = 40;
                    val ref = rt borrow value;
                    nb scope {
                        let outer = nb {
                            let inner = nb {
                                let int tail = await suffix();
                                stdio.stdout.write(ref + tail);
                            };
                            await inner;
                        };
                    }
                    return;
                }
                """;

        Ast.Program program = OresCompiler.parseAndTypeCheck(source);
        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().get(1);
        Ast.TaskScopeStmt scope = (Ast.TaskScopeStmt) main.body().get(2);
        Ast.TaskExpr outer = (Ast.TaskExpr) ((Ast.BindingStmt)
                scope.body().getFirst()).initializer();
        Ast.TaskExpr inner = (Ast.TaskExpr) ((Ast.BindingStmt)
                outer.body().getFirst()).initializer();

        Ast.TaskCaptureProof outerProof = outer.captureProofs().stream()
                .filter(proof -> proof.name().equals("ref"))
                .findFirst()
                .orElseThrow();
        Ast.TaskCaptureProof innerProof = inner.captureProofs().getFirst();

        assertEquals(Ast.TaskCaptureMode.SCOPED_READ_BORROW, outerProof.mode());
        assertEquals(Ast.TaskCaptureMode.SCOPED_READ_BORROW, innerProof.mode());
        assertEquals(outerProof.regionId(), innerProof.regionId());
        assertEquals(outerProof.regionGeneration(), innerProof.regionGeneration());
        assertEquals("$", innerProof.accessPath());
        assertEquals("42", run(source));
    }



    @Test
    void inheritedScopedReadPermitCanCrossUnrelatedNestedScopeDrain()
            throws Exception {
        String source = """
                pub routine main(): void {
                    let int value = 42;
                    val ref = rt borrow value;
                    nb scope {
                        let outer = nb {
                            nb scope {
                            }
                            stdio.stdout.write(ref);
                        };
                    }
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }

    @Test
    void scopedReadLeaseCanFlowThroughNestedLexicalTaskScope() throws Exception {
        String source = """
                async fnc suffix(): int {
                    return 2;
                }

                pub routine main(): void {
                    let int value = 40;
                    val ref = rt borrow value;
                    nb scope {
                        let outer = nb {
                            nb scope {
                                let inner = nb {
                                    let int tail = await suffix();
                                    stdio.stdout.write(ref + tail);
                                };
                            }
                        };
                    }
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }

    @Test
    void scopedReadCaptureCannotBeReborrowedIntoDerivativeAuthority() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 42;
                            val ref = rt borrow value;
                            nb scope {
                                let outer = nb {
                                    val nested = rt borrow ref;
                                    stdio.stdout.write(nested);
                                };
                            }
                            return;
                        }
                        """));

        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("reborrow")
                || message.contains("scoped task read permit"), message);
    }

    private static Ast.TaskCaptureProof firstCapture(Ast.Program program) {
        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules()
                .getFirst().declarations().getFirst();
        Ast.TaskScopeStmt scope = (Ast.TaskScopeStmt) main.body().get(2);
        Ast.TaskExpr task = (Ast.TaskExpr) ((Ast.BindingStmt)
                scope.body().getFirst()).initializer();
        return task.captureProofs().getFirst();
    }

    private static String run(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, text, "scoped-task-borrow.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
