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
                            val ref = rt borrow mut box;
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
