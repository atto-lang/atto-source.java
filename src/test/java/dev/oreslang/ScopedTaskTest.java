package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ScopedTaskTest {
    @Test
    void taskScopeJoinsUnawaitedChildBeforeParentContinues() throws Exception {
        assertEquals("child:parent", run("""
                pub routine main(): void {
                    task scope {
                        do nb {
                            stdio.stdout.write("child");
                        };
                    }
                    stdio.stdout.write(":parent");
                    return;
                }
                """));
    }

    @Test
    void nbScopeAliasHasIdenticalJoinSemantics() throws Exception {
        assertEquals("first:after", run("""
                pub routine main(): void {
                    nb scope {
                        let Task<int> worker = task {
                            stdio.stdout.write("first");
                            return 42;
                        };
                    }
                    stdio.stdout.write(":after");
                    return;
                }
                """));
    }

    @Test
    void nestedChildrenJoinEvenWhenSpawnedAsScopeCloses() throws Exception {
        assertEquals("nested:after", run("""
                pub routine main(): void {
                    task scope {
                        do nb {
                            do nb {
                                stdio.stdout.write("nested");
                            };
                        };
                    }
                    stdio.stdout.write(":after");
                    return;
                }
                """));
    }

    @Test
    void nestedScopesPreserveLifetimes() throws Exception {
        assertEquals("inner:outer:end", run("""
                pub routine main(): void {
                    task scope {
                        task scope {
                            do nb { stdio.stdout.write("inner"); };
                        }
                        stdio.stdout.write(":outer");
                    }
                    stdio.stdout.write(":end");
                    return;
                }
                """));
    }

    @Test
    void childFailureIsNotSilentlyDiscarded() {
        String source = """
                pub routine main(): void {
                    task scope {
                        do nb { rt fail "scoped-child-failed"; };
                    }
                    stdio.stdout.write("MUST_NOT_PRINT");
                    return;
                }
                """;
        assertThrows(RuntimeException.class, () -> run(source));
    }

    @Test
    void borrowedCaptureRemainsRejectedUntilLifetimeProofExists() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = 42;
                            let borrowed = rt borrow value;
                            task scope {
                                let Task<void> child = task {
                                    stdio.stdout.write(borrowed);
                                };
                            }
                            return;
                        }
                        """));
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
    void actorScopesAreAdmittedAfterPhysicalRetirementIntegration() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                actor fnc worker(): void {
                    nb scope {
                        let child = nb {
                            return;
                        };
                    }
                    return;
                }
                """));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                untrusted actor fnc worker(): void {
                    nb scope {
                        let child = nb {
                            return;
                        };
                    }
                    return;
                }
                """));
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

    private static String run(String code) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "scoped-task.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
