package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LanguageHardeningTest {
    @Test
    void parsesAllRequestedImportForms() {
        Ast.Program program = Parser.parse("""
                import module foo from "../xyz";
                import module {bar, baz} from '../xyz';
                import class {x} from '../xyz';
                import fnc * as funcs from '../xyz';
                import * as everything from './xyz';

                define module app
                  pub fnc main() => void { return; }
                end
                """);

        assertEquals(5, program.imports().size());
        assertEquals(Ast.ImportKind.MODULE, program.imports().getFirst().kind());
        assertEquals("foo", program.imports().getFirst().names().getFirst());
        assertEquals("funcs", program.imports().get(3).namespace());
        assertEquals(Ast.ImportKind.ALL, program.imports().get(4).kind());
        assertEquals("everything", program.imports().get(4).namespace());
    }

    @Test
    void fiIsARealDistinctTokenAndEndDoesNotCloseIf() {
        var tokens = new Lexer("if true; do return; fi end").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FI));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.END));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc main() => void {
                    if true; do
                      return;
                    end
                  }
                end
                """));
    }

    @Test
    void modulesAreTypedNamespacesAndCanAdhereToInterfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module contracts
                  define interface MathApi
                    fnc add(int a, int b) => int;
                  end
                end

                @AdheresTo(contracts.MathApi)
                define module math
                  pub fnc add(int a, int b) => int { return a + b; }
                end

                define module app
                  pub fnc main() => void {
                    val answer = math.add(40, 2);
                    stdio.println(answer);
                    return;
                  }
                end
                """)));
    }

    @Test
    void moduleAdherenceRejectsMissingExports() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module contracts
                  define interface Api
                    fnc ping() => int;
                  end
                end

                @AdheresTo(contracts.Api)
                define module broken
                  pub fnc pong() => int { return 1; }
                end
                """)));
        assertTrue(error.getMessage().contains("does not adhere"));
    }

    @Test
    void classesSupportMultipleParentsAndMultipleInterfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model
                  define interface AApi
                    fnc a() => int;
                  end
                  define interface BApi
                    fnc b() => int;
                  end

                  define class A
                    pub a() => int { return 1; }
                  end
                  define class B
                    pub b() => int { return 2; }
                  end

                  define class Combined extends A, B implements AApi, BApi
                  end

                  define class ObjectChild extends Object
                  end
                  define class ListChild extends List
                  end
                end
                """)));
    }

    @Test
    void inheritanceCyclesAndConflictingDiamondsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module m
                  define class A extends B
                  end
                  define class B extends A
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module m
                  define class A
                    pub val int id = 1;
                  end
                  define class B
                    pub val String id = "b";
                  end
                  define class C extends A, B
                  end
                end
                """)));
    }

    @Test
    void objArrTupleIndexAndLetAssignmentAreStaticallyChecked() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    val person = obj{name: "ore", age: 1};
                    val values = arr[10, 20, 30];
                    val first = values[0];
                    [const left, let right] = (1, "two");
                    let n = first;
                    n = 99;
                    stdio.println(person.name);
                    stdio.println(right);
                    return;
                  }
                end
                """)));
    }

    @Test
    void valAndConstCannotBeReassigned() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc f() => void {
                    val x = 1;
                    x = 2;
                    return;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc f() => void {
                    const x = 1;
                    x = 2;
                    return;
                  }
                end
                """)));
    }

    @Test
    void nullIsForbiddenAsAValueOrStandaloneTypeButOptionNullIsExplicitlyAllowed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc bad() => String { return null; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(null x) => void { return; }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc keep(Option<String> x) => Option<String> { return x; }
                  fnc explicit_marker(Option<null> x) => Option<null> { return x; }
                  fnc some_value() => Option<int> { return Some(1); }
                  fnc no_value() => Option<int> { return None; }
                end
                """)));
    }

    @Test
    void nonVoidFunctionsMustReturnOnEveryControlFlowPath() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc incomplete(bool flag) => int {
                    if flag; do
                      return 1;
                    fi
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc complete(bool flag) => int {
                    if flag; do
                      return 1;
                    else
                      return 2;
                    fi
                  }
                end
                """)));
    }

    @Test
    void duplicateImportBindingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                import module foo from './a';
                import class {foo} from './b';
                define module app
                end
                """)));
    }
    @Test
    void strictFaasRejectsSharedActorDeclarationsAtAdmission() {
        Ast.Program sharedActor = TypeChecker.check(Parser.parse("""
                shared actor Account {
                  let balance = 100;

                  pub fnc current() => int {
                    return self.balance;
                  }
                }
                """));

        assertThrows(SecurityException.class, () ->
                CapabilityChecker.check(sharedActor, IsolatePolicy.strictFaas()));
        assertDoesNotThrow(() ->
                CapabilityChecker.check(sharedActor, IsolatePolicy.developer()));
    }



@Test
    void destructureDiscardNeverBecomesAReadableBinding() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc f() => int {
                    [_, const value] = (1, 2);
                    return _;
                  }
                end
                """)));

        assertTrue(error.getMessage().contains("unknown name '_'"));
    }

@Test
    void explicitBindingKindOnUnderscoreIsAlsoDiscarded() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc f() => int {
                    [const _, let value] = (1, 2);
                    [let _, const next] = (3, 4);
                    return value + next;
                  }
                end
                """)));
    }


    @Test
    void awaitTypingMatchesJvmAsyncAdaptersUnionsAndPassThroughValues() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  async fnc from_future(Future<int> value) => int {
                    return await value;
                  }

                  async fnc from_stage(CompletionStage<int> value) => int {
                    return await value;
                  }

                  async fnc from_completable(CompletableFuture<int> value) => int {
                    return await value;
                  }

                  async fnc from_awaitable(Awaitable<int> value) => int {
                    return await value;
                  }

                  async fnc from_union(Future<int> | int value) => int {
                    return await value;
                  }

                  async fnc from_sync(int value) => int {
                    return await value;
                  }

                  async fnc wait_thread(Thread thread) => void {
                    await thread;
                    return;
                  }
                end
                """)));
    }

    @Test
    void awaitTypingDoesNotCollapseInteropCarriersToUnknown() {
        var program = Parser.parse("""
                define module app
                  async fnc wrong(CompletableFuture<int> value) => string {
                    return await value;
                  }
                end
                """);

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
    }

    @Test
    void sharedStateRejectsEveryPendingAsyncCarrierFamily() {
        for (String carrier : java.util.List.of(
                "Future<int>",
                "CompletionStage<int>",
                "CompletableFuture<int>",
                "Awaitable<int>",
                "Thread")) {
            String source = """
                    define module app
                      fnc bad(%s value) => void {
                        val shared = SharedMutex.new(value);
                        return;
                      }
                    end
                    """.formatted(carrier);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    carrier + " must remain non-shareable pending/runtime state");
        }
    }


    @Test
    void awaitRequiresAsyncOrActorDispatchedCallableContext() {
        var ordinary = Parser.parse("""
                define module app
                  fnc bad(Future<int> value) => int {
                    return await value;
                  }
                end
                """);
        IllegalArgumentException ordinaryError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(ordinary));
        assertTrue(ordinaryError.getMessage().contains("await is only valid"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  actor fnc worker(Future<int> value) => void {
                    val result = await value;
                    stdio.println(result);
                    return;
                  }
                end
                """)));

        var nestedLambda = Parser.parse("""
                define module app
                  async fnc outer(Future<int> value) => void {
                    val callback = || -> {
                      val result = await value;
                      stdio.println(result);
                      return;
                    };
                    stdio.println(callback);
                    return;
                  }
                end
                """);
        IllegalArgumentException lambdaError = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(nestedLambda));
        assertTrue(lambdaError.getMessage().contains("await is only valid"));
    }


    @Test
    void gcRuntimeNamespacesAreTypedAndActorGcIsActorScoped() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc sweep_process() => int {
                  return process.gc();
                }

                pub actor fnc sweep_actor() => int {
                  return actor.gc();
                }
                """)));

        IllegalArgumentException outsideActor = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc bad() => int {
                          return actor.gc();
                        }
                        """)));
        assertTrue(outsideActor.getMessage().contains("only inside actor"));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc bad_arity() => int {
                          return process.gc(1);
                        }
                        """)));
    }

}
