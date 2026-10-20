package dev.oreslang;

import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.Context;
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
    void borrowedTaskCaptureStillFailsClosedInsideScope() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 8;
                            val ref = rt borrow value;
                            nb scope {
                                let worker = nb {
                                    stdio.stdout.write(ref);
                                };
                            }
                            return;
                        }
                        """));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"));
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
