package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ResultMustUseProofTest {
    private static void accepts(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }
    private static void rejects(String source) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("must-use Result"), failure.getMessage());
    }

    @Test
    void rejectBareConstructorAndNeverHandledLocal() {
        rejects("""
                fnc bad(): void {
                  Ok();
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  val Result<void, String> result = Ok();
                  return;
                }
                """);
    }

    @Test
    void checkingStatusAndLoggingAreNotHandling() {
        rejects("""
                fnc bad(): void {
                  val Result<void, String> result = Ok();
                  val bool ok = result.is_ok();
                  stdio.println(result);
                  return;
                }
                """);
    }

    @Test
    void propagationAndExhaustiveMatchDischarge() {
        accepts("""
                fnc good(): Result<void, String> {
                  val Result<void, String> result = Ok();
                  return result;
                }
                """);
        accepts("""
                fnc good(): void {
                  val Result<void, String> result = Ok();
                  match result over
                    on Ok() -> { stdio.println("ok"); }
                    on Err(message) -> { stdio.println(message); }
                  end
                  return;
                }
                """);
    }

    @Test
    void pathsMustHandleEvenWhenAnotherPathMatches() {
        rejects("""
                fnc bad(bool flag): void {
                  val Result<void, String> result = Ok();
                  if flag; do
                    match result over
                      on Ok() -> { stdio.println("ok"); }
                      on Err(message) -> { stdio.println(message); }
                    end
                  else
                    stdio.println("not handled");
                  fi
                  return;
                }
                """);
        rejects("""
                fnc bad(bool flag): Result<void, String> {
                  val Result<void, String> result = Ok();
                  if flag; do
                    return Ok();
                  else
                    return result;
                  fi
                }
                """);
    }

    @Test
    void resultCannotBeOverwrittenAfterEarlierHandling() {
        rejects("""
                fnc bad(): void {
                  let Result<void, String> result = Ok();
                  result.unwrap();
                  result = Ok();
                  return;
                }
                """);
    }

    @Test
    void nameShadowingCannotDischargeOuterResult() {
        rejects("""
                fnc bad(bool flag): void {
                  val Result<void, String> result = Ok();
                  if flag; do
                    val Result<void, String> result = Ok();
                    match result over
                      on Ok() -> { stdio.println("inner ok"); }
                      on Err(message) -> { stdio.println(message); }
                    end
                  else
                    match result over
                      on Ok() -> { stdio.println("outer ok"); }
                      on Err(message) -> { stdio.println(message); }
                    end
                  fi
                  return;
                }
                """);
    }

    @Test
    void checkedExtractionIsExplicitHandling() {
        accepts("""
                fnc good(): void {
                  val Result<void, String> result = Ok();
                  result.unwrap();
                  return;
                }
                """);
    }

    @Test
    void functionParametersRequireHandling() {
        rejects("""
                fnc bad(Result<void, String> result): void {
                  return;
                }
                """);
        accepts("""
                fnc good(Result<void, String> result): void {
                  match result over
                    on Ok() -> { stdio.println("ok"); }
                    on Err(error) -> { stdio.println(error); }
                  end
                  return;
                }
                """);
    }

    @Test
    void classMethodParametersRequireHandling() {
        rejects("""
                define class Handler as
                  pub accept(Result<void, String> result): void {
                    return;
                  }
                end
                """);
        accepts("""
                define class Handler as
                  pub accept(Result<void, String> result): void {
                    match result over
                      on Ok() -> { stdio.println("ok"); }
                      on Err(error) -> { stdio.println(error); }
                    end
                    return;
                  }
                end
                """);
    }

    @Test
    void wildcardMatchIsNotAResultHandlingProof() {
        rejects("""
                fnc bad(): void {
                  val Result<void, String> result = Ok();
                  match result over
                    on _ -> { stdio.println("discard"); }
                  end
                  return;
                }
                """);
        rejects("""
                fnc bad(Result<void, String> result): void {
                  match result over
                    on Ok() -> { stdio.println("ok"); }
                    on _ -> { stdio.println("ignored error"); }
                  end
                  return;
                }
                """);
    }

    @Test
    void cannotDiscardUnboundResultThroughUntypedCall() {
        rejects("""
                fnc bad(): void {
                  stdio.println(Ok());
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  stdio.stdout.log(Ok());
                  return;
                }
                """);
    }

    @Test
    void nonConsumingLoggingOfNamedResultStillRequiresHandling() {
        accepts("""
                fnc good(): void {
                  val Result<void, String> result = Ok();
                  stdio.println(result);
                  match result over
                    on Ok() -> { stdio.println("ok"); }
                    on Err(error) -> { stdio.println(error); }
                  end
                  return;
                }
                """);
    }

    @Test
    void nestedResultMustUseRequiresRecursiveProof() {
        rejects("""
                fnc bad(): void {
                  val wrapped = Some(Ok());
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  Some(Ok());
                  return;
                }
                """);
        rejects("""
                fnc bad(Option<Result<void, String>> wrapped): void {
                  return;
                }
                """);
        rejects("""
                fnc bad(): Option<Result<void, String>> {
                  return Some(Ok());
                }
                """);
        rejects("""
                fnc bad(): void {
                  stdio.stdout.log(Some(Ok()));
                  return;
                }
                """);
    }

    @Test
    void everyBranchMayPropagateOrHandle() {
        accepts("""
                fnc good(bool flag): Result<void, String> {
                  val Result<void, String> result = Ok();
                  if flag; do
                    return result;
                  else
                    match result over
                      on Ok() -> { stdio.println("ok"); }
                      on Err(message) -> { stdio.println(message); }
                    end
                  fi
                  return Ok();
                }
                """);
    }
}
