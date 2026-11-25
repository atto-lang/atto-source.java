package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Legacy explicit-async source lane only; strict Atto and full AOT remain gated. */
final class AsyncTrapResultForwardPortTest {
    private static long run(String guest) throws Exception {
        OresCompiler.parseAndTypeCheck(guest);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(Source.newBuilder(OresLanguage.ID, guest,
                    "async-trap-result.ores").build()).asLong();
        }
    }

    @Test void noAwaitAndAwaitBothSettleFutureOfResult() throws Exception {
        for (String spelling : new String[] { "trap", "trap!" }) {
            assertEquals(42L, run("""
                    async %1$s fnc ready(): int { return 20; }
                    async %1$s fnc later(): int {
                      val Result<int, Error> first = await ready();
                      return first.unwrap() + 2;
                    }
                    pub fnc main(): int {
                      val Future<Result<int, Error>> a = ready();
                      val Future<Result<int, Error>> b = later();
                      val Result<int, Error> av = await a;
                      val Result<int, Error> bv = await b;
                      return av.unwrap() + bv.unwrap();
                    }
                    """.formatted(spelling)), spelling);
        }
    }

    @Test void optionPayloadAndExistingResultAreNotFlattenedIncorrectly() throws Exception {
        assertEquals(42L, run("""
                async trap fnc absent(): Option<int> { return None; }
                async trap! fnc forward(): Result<int, String> { return Err("original"); }
                pub fnc main(): int {
                  val Result<Option<int>, Error> missing = await absent();
                  val Result<int, Error | String> failed = await forward();
                  if missing.is_ok() then
                    if missing.unwrap().is_none() then
                      if failed.is_err() then return 42; fi
                    fi
                  fi
                  return 1;
                }
                """));
    }

    @Test void recoverableCastBeforeAndAfterAwaitReturnsErr() throws Exception {
        assertEquals(42L, run("""
                define class Animal as end
                define class Dog extends Animal as end
                define class Cat extends Animal as end
                async fnc ready(): int { return 9; }
                async trap fnc before(): Dog {
                  val Animal animal = new Cat();
                  return animal as Dog;
                }
                async trap! fnc after(): Dog {
                  val n = await ready();
                  val Animal animal = new Cat();
                  return animal as Dog;
                }
                pub fnc main(): int {
                  val Result<Dog, Error> first = await before();
                  val Result<Dog, Error> second = await after();
                  if first.is_err() then
                    if second.is_err() then return 42; fi
                  fi
                  return 1;
                }
                """));
    }

    @Test void rejectWrongWrapperAndRetainUnimplementedProfiles() {
        for (String wrong : new String[] { "Result<Future<int>, Error>", "Future<Option<int>>" }) {
            assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                    async trap fnc ready(): int { return 1; }
                    pub fnc main(): void { val %s result = ready(); return; }
                    """.formatted(wrong)));
        }
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "generator trap fnc values(): int { yield 1; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "suspends trap fnc later(): int { return 1; }"));
    }
}
