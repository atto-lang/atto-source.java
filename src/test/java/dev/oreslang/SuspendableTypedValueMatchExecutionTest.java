package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class SuspendableTypedValueMatchExecutionTest {
    private static String run(String code) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "async-value-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    @Test
    void awaitedSubjectCompletesLexicalResultBeforeOuterReturn() throws Exception {
        assertEquals("arm-cleanup\n42", run("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  const answer: int = match<ret=int> await ready() over {
                    on 7 {
                      defer stdio.println("arm-cleanup");
                      return 42;
                    }
                    default { return 0; }
                  };
                  stdio.println(answer);
                }
                """));
    }

    @Test
    void awaitedArmResultRetainsOwnerAcrossResumeAndCleansOnce() throws Exception {
        assertEquals("last\nfirst\n7", run("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  const answer: int = match<ret=int> 7 over {
                    on 7 {
                      defer stdio.println("first");
                      defer stdio.println("last");
                      return await ready();
                    }
                    default { return 0; }
                  };
                  stdio.println(answer);
                }
                """));
    }

    @Test
    void nestedAwaitedValueMatchesPreserveDistinctCompletionOwners() throws Exception {
        assertEquals("inner\nouter\n8", run("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  const answer: int = match<ret=int> 7 over {
                    default {
                      defer stdio.println("outer");
                      const inner: int = match<ret=int> await ready() over {
                        default {
                          defer stdio.println("inner");
                          return 7;
                        }
                      };
                      return inner + 1;
                    }
                  };
                  stdio.println(answer);
                }
                """));
    }

    @Test
    void failedDeferPreventsPublishingAwaitedArmResult() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  const answer: int = match<ret=int> 7 over {
                    default {
                      defer stdio.println("earlier");
                      defer stdio.println(1 % 0);
                      defer stdio.println("later");
                      return await ready();
                    }
                  };
                  stdio.println("published");
                  stdio.println(answer);
                }
                """, "async-value-match-defer-failure.ores").mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            assertThrows(PolyglotException.class, () -> context.eval(source));
        }
        assertEquals("later\nearlier", out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void awaitedArmReturnThroughTryFinallyRetainsLexicalOwner() throws Exception {
        assertEquals("finally-once\nouter-cleanup\n42", run("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  const answer: int = match<ret=int> 7 over {
                    default {
                      defer stdio.println("outer-cleanup");
                      try {
                        return (await ready()) + 35;
                      } catch (err) {
                        return 0;
                      } finally {
                        stdio.println("finally-once");
                      }
                    }
                  };
                  stdio.println(answer);
                }
                """));
    }

    @Test
    void nestedIfAndAwaitedResultDoNotEscapeEnclosingCallable() throws Exception {
        assertEquals("selected\n42\nafter-match", run("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  const answer: int = match<ret=int> 7 over {
                    default {
                      if true {
                        defer stdio.println("selected");
                        return await ready() + 35;
                      } else {
                        return 0;
                      } fi
                    }
                  };
                  stdio.println(answer);
                  stdio.println("after-match");
                }
                """));
    }

    @Test
    void genuinelyPendingCooperativeFuturePreservesMatchFrameAndNoReplay() throws Exception {
        assertEquals("producer\narm-cleanup\n42", run("""
                async fnc ready(): int {
                  stdio.println("producer");
                  rt cooperate;
                  return 7;
                }
                pub async fnc main(): void {
                  const answer: int = match<ret=int> await ready() over {
                    on 7 {
                      defer stdio.println("arm-cleanup");
                      return 42;
                    }
                    default { return 0; }
                  };
                  stdio.println(answer);
                }
                """));
    }

    @Test
    void awaitedCallableAdmissionRequiresUnshadowedLocalAsyncDeclaration() {
        // A synchronous trampoline may return a Future, but is not covered
        // by the narrow direct-async declaration proof in this milestone.
        IllegalArgumentException trampoline = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                async fnc ready(): int { return 7; }
                fnc relay(): Future<int> { return ready(); }
                pub async fnc main(): void {
                  const answer: int = match<ret=int> await relay() over {
                    default { return 7; }
                  };
                  stdio.println(answer);
                }
                """));
        assertTrue(trampoline.getMessage().contains("pure scalar expression")
                        || trampoline.getMessage().contains("local callable"),
                trampoline.getMessage());
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  const ready: int = 7;
                  const answer: int = match<ret=int> await ready() over {
                    default { return 7; }
                  };
                  stdio.println(answer);
                }
                """));
    }

    @Test
    void rejectsImplicitAsyncEffectsAndSyncAwaitInTypedMatch() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async fnc ready(): int { return 7; }
                pub fnc main(): void {
                  const value: int = match<ret=int> await ready() over {
                    default { return 7; }
                  };
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async fnc ready(): int { return 7; }
                pub async fnc main(): void {
                  const value: int = match<ret=int> 7 over {
                    default { return ready(); }
                  };
                }
                """));
    }
}
