package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TaskOwnershipPhase1Test {
    @Test
    void ownedPrimitiveCapturedAndAwaitedInRealScheduler() throws Exception {
        String src = """
                pub routine main(): void {
                    val int base = 40;
                    let Task<void> task = nb {
                        stdio.println(base + 2);
                    };
                    await task;
                    return;
                }
                """;
        OresCompiler.parseAndTypeCheck(src);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, src, "task-capture.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(program);
        }
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("42"));
    }

    @Test
    void capturedOwnedClassMovesAndCannotBeUsedByCaller() {
        String common = """
                define class Box as
                    pub let int count = 7;
                end
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(common + """
                pub routine main(): void {
                    let Box box = new Box();
                    let Task<void> work = nb {
                        stdio.println(box.count);
                    };
                    await work;
                    return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(common + """
                pub routine main(): void {
                    let Box box = new Box();
                    let Task<void> work = nb {
                        stdio.println(box.count);
                    };
                    stdio.println(box.count);
                    await work;
                    return;
                }
                """));
    }

    @Test
    void taskCaptureCannotBorrowEvenWhenCallerStillLives() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    let int value = 8;
                    val ref = rt borrow value;
                    let Task<void> worker = nb {
                        stdio.println(ref);
                    };
                    await worker;
                    return;
                }
                """));
    }

    @Test
    void twoTaskCapturesCannotBothOwnOneMoveOnlyValue() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Box as
                    pub let int value = 1;
                end
                pub routine main(): void {
                    let Box box = new Box();
                    let Task<void> first = nb {
                        stdio.println(box.value);
                    };
                    let Task<void> second = nb {
                        stdio.println(box.value);
                    };
                    await first;
                    await second;
                    return;
                }
                """));
    }

    @Test
    void unsupportedMutexGuardOrActorLikeCaptureFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    val Mutex<int> lock = Mutex.new<int>(4);
                    let Task<void> task = nb {
                        stdio.println(lock);
                    };
                    await task;
                    return;
                }
                """));
    }

    @Test
    void doNbStartsWithoutHandleButRequiresNoImplicitBorrow() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                    do nb {
                        stdio.println(1);
                    }
                    return;
                }
                """)));
    }

    @Test
    void taskBodyCannotReturnNonVoidValue() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                    let Task<void> task = nb {
                        return 5;
                    };
                    await task;
                    return;
                }
                """));
    }
}
