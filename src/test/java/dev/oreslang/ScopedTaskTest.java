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
    void synchronousHelperThatOnlySpawnsTaskCannotDropScopeLineage() {
        String source = """
                fnc spawn_child_sync(): void {
                    do nb {
                        rt fail "sync-helper-child-failed";
                    };
                    return;
                }

                pub routine main(): void {
                    task scope {
                        spawn_child_sync();
                    }
                    stdio.stdout.write("MUST_NOT_PRINT");
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> run(source));
        assertTrue(
                failure.toString().contains("sync-helper-child-failed")
                        || (failure.getCause() != null
                                && failure.getCause().toString()
                                        .contains("sync-helper-child-failed")));
    }


    @Test
    void suspendingHelperChildFailureCannotEscapeCallingScope() {
        String source = """
                suspends fnc spawn_child(): void {
                    do nb {
                        rt fail "helper-child-failed";
                    };
                    return;
                }

                pub routine main(): void {
                    nb scope {
                        spawn_child();
                    }
                    stdio.stdout.write("MUST_NOT_PRINT");
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> run(source));
        assertTrue(
                failure.toString().contains("helper-child-failed")
                        || (failure.getCause() != null
                                && failure.getCause().toString()
                                        .contains("helper-child-failed")));
    }

    @Test
    void nestedSuspendingHelpersRetainLiveScopeParentPermit() {
        String source = """
                suspends fnc inner(): void {
                    do nb {
                        rt fail "nested-helper-child-failed";
                    };
                    return;
                }

                suspends fnc outer(): void {
                    inner();
                    return;
                }

                pub routine main(): void {
                    task scope {
                        outer();
                    }
                    stdio.stdout.write("MUST_NOT_PRINT");
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> run(source));
        assertTrue(
                failure.toString().contains("nested-helper-child-failed")
                        || (failure.getCause() != null
                                && failure.getCause().toString()
                                        .contains("nested-helper-child-failed")));
    }


    @Test
    void futureMapNamedCallbackCannotEscapeLexicalTaskScope() {
        String source = """
                async fnc base(): int {
                    return 41;
                }

                fnc spawn_from_map(int value): int {
                    do nb {
                        rt fail "future-map-child-failed";
                    };
                    return value + 1;
                }

                pub routine main(): void {
                    task scope {
                        val Future<int> mapped = base().map(spawn_from_map);
                    }
                    stdio.stdout.write("MUST_NOT_PRINT");
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> run(source));
        assertTrue(
                failure.toString().contains("future-map-child-failed")
                        || (failure.getCause() != null
                                && failure.getCause().toString()
                                        .contains("future-map-child-failed")));
    }

    @Test
    void futureOnSuccessLambdaCannotReuseStaleCapturedScopeParent() {
        String source = """
                async fnc base(): int {
                    return 7;
                }

                pub routine main(): void {
                    nb scope {
                        val Future<int> observed = base().onSuccess(|int value| -> {
                            do nb {
                                rt fail "future-lambda-child-failed";
                            };
                            return;
                        });
                    }
                    stdio.stdout.write("MUST_NOT_PRINT");
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> run(source));
        assertTrue(
                failure.toString().contains("future-lambda-child-failed")
                        || (failure.getCause() != null
                                && failure.getCause().toString()
                                        .contains("future-lambda-child-failed")));
    }


    @Test
    void partiallyConsumedGeneratorClosesAndPhysicallyRetiresBeforeScopeExit()
            throws Exception {
        String source = """
                async generator fnc values(): int {
                    try {
                        yield 1;
                        yield 2;
                    } finally {
                        stdio.stdout.write(":closed");
                    }
                    return;
                }

                pub async routine main(): void {
                    task scope {
                        val AsyncIterator<int> iterator = values();
                        val IteratorResult<int> first = await iterator.next();
                        stdio.stdout.write(first.value.unwrap());
                    }
                    stdio.stdout.write("|after");
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("1:closed|after", run(source));
    }

    @Test
    void asyncIteratorProtocolPreservesStructuredGeneratorRetirement()
            throws Exception {
        String source = """
                async generator fnc stream(): int {
                    try {
                        yield 1;
                        yield 2;
                    } finally {
                        stdio.stdout.write(":closed");
                    }
                    return;
                }

                define class Bag as
                    pub [Symbol.asyncIterator](): AsyncGenerator<int> {
                        return stream();
                    }
                end

                pub async routine main(): void {
                    task scope {
                        val bag = new Bag();
                        for await const value of bag {
                            stdio.stdout.write(value);
                            break;
                        }
                    }
                    stdio.stdout.write("|after");
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("1:closed|after", run(source));
    }

    @Test
    void generatorSpawnedChildFailureReachesLexicalScope() {
        String source = """
                async generator fnc values(): int {
                    do nb {
                        rt fail "generator-child-failed";
                    };
                    yield 1;
                    return;
                }

                pub async routine main(): void {
                    task scope {
                        val AsyncIterator<int> iterator = values();
                        val IteratorResult<int> first = await iterator.next();
                        stdio.stdout.write(first.value.unwrap());
                    }
                    stdio.stdout.write("MUST_NOT_PRINT");
                    return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> run(source));
        assertTrue(
                failure.toString().contains("generator-child-failed")
                        || (failure.getCause() != null
                                && failure.getCause().toString()
                                        .contains("generator-child-failed")));
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
        assertTrue(message.contains("scope"));
        assertTrue(message.contains("actor"));
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
