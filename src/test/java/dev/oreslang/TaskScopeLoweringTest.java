package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TaskScopeLoweringTest {

    @Test
    void scopeImplicitlyDrainsOwnedChildWithoutExplicitAwait() throws Exception {
        String source = """
                pub routine main(): void {
                    nb scope {
                        let worker = nb {
                            stdio.stdout.write(42);
                        };
                    }
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }

    @Test
    void returnFromScopeDrainsChildrenBeforeLeavingCallable() throws Exception {
        String source = """
                pub routine main(): void {
                    nb scope {
                        let worker = nb {
                            stdio.stdout.write(42);
                        };
                        return;
                    }
                    stdio.stdout.write(99);
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }

    @Test
    void nestedScopeDrainsGrandchildTransitively() throws Exception {
        String source = """
                pub routine main(): void {
                    nb scope {
                        let outer = nb {
                            nb scope {
                                let inner = nb {
                                    stdio.stdout.write(42);
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
    void inferredTaskResultCanReturnThroughScopeAfterChildDrain() throws Exception {
        String source = """
                pub routine main(): void {
                    let worker = nb {
                        nb scope {
                            let child = nb {
                                stdio.stdout.write(4);
                            };
                            return 2;
                        }
                    };
                    let int result = await worker;
                    stdio.stdout.write(result);
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }


    @Test
    void scopedTaskMayInvokeSuspendsCallableFromSynchronousParent() throws Exception {
        String source = """
                suspends fnc operation(): int {
                    return 40;
                }

                pub routine main(): void {
                    nb scope {
                        let worker = nb {
                            stdio.stdout.write(operation() + 2);
                        };
                    }
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }


    @Test
    void actorScopeIsRejectedStaticallyUntilActorIntegrationIsProven() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        actor fnc worker(): void {
                            nb scope {
                                let child = nb {
                                    return;
                                };
                            }
                            return;
                        }
                        """));
        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("nb scope"));
        assertTrue(message.contains("actor"));
    }

    @Test
    void immutableBorrowCaptureIsAdmittedOnlyByStructuredScope() throws Exception {
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

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run(source));
    }

    @Test
    void mutableLoanCannotCrossAwaitInsideScope() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 8;
                            val ref = rt borrow mut value;
                            nb scope {
                                let worker = nb {
                                    stdio.stdout.write(1);
                                };
                                await worker;
                                stdio.stdout.write(ref);
                            }
                            return;
                        }
                        """));
        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("await") || message.contains("borrow"));
    }


    @Test
    void outerMutableLoanCannotCrossImplicitScopeDrain() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 8;
                            val ref = rt borrow mut value;
                            nb scope {
                                let worker = nb {
                                    stdio.stdout.write(1);
                                };
                            }
                            stdio.stdout.write(ref);
                            return;
                        }
                        """));
        String message = failure.getMessage().toLowerCase();
        assertTrue(message.contains("scope") || message.contains("borrow"));
    }

    private String run(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, text, "task-scope.ores")
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
