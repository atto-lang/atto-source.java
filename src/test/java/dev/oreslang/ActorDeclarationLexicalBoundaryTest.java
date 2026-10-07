package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorDeclarationLexicalBoundaryTest {

    @Test
    void defineActorAsParsesAtFileAndModuleScope() {
        Ast.Program program = Parser.parse("""
                define actor Worker as
                  pub receive(): void {
                    rt cooperate;
                    return;
                  }
                end

                define module services as
                  define isoactor Isolated as
                    pub process(): void { return; }
                  end

                  define untrusted actor Sandboxed as
                    pub run(): void { return; }
                  end
                end
                """);

        Ast.ClassDecl worker = (Ast.ClassDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("Worker"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.SHARED, worker.actorKind());

        Ast.ModuleDecl services = program.modules().stream()
                .filter(module -> module.name().equals("services"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.PRIVATE,
                ((Ast.ClassDecl) services.declarations().get(0)).actorKind());
        assertEquals(Ast.ActorKind.UNTRUSTED,
                ((Ast.ClassDecl) services.declarations().get(1)).actorKind());
    }

    @Test
    void defineActorStillForbidsConstructorsUntilRuntimeStartupLoweringExists() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Worker as
                          pub constructor() {}
                        end
                        """));
        assertTrue(failure.getMessage().contains("actors do not declare constructors"));
    }

    @Test
    void actorCannotCallEnclosingMainImplicitly() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub receive(): void {
                            main();
                            return;
                          }
                        end

                        pub routine main(): void { return; }
                        """)));
        assertTrue(failure.getMessage().contains("cannot implicitly access"), failure.getMessage());
    }

    @Test
    void OrdinaryClassCannotReadFileLevelCallableAsAmbientClosure() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc helper(): int { return 42; }

                        define class Box as
                          pub calculate(): int {
                            return helper();
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot implicitly access"), failure.getMessage());
    }

    @Test
    void CapturingUnrelatedFunctionValueFromClassIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc helper(): int { return 42; }

                        define class Box as
                          pub consume(): void {
                            val callback = helper;
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot implicitly access"), failure.getMessage());
    }

    @Test
    void ActorMethodMayStillUseItsOwnMethodsAndBuiltins() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  pub ping(): void {
                    rt cooperate;
                    stdio.println("ping");
                    return;
                  }

                  pub run(): void {
                    self.ping();
                    return;
                  }
                end
                """)));
    }

    @Test
    void siblingModulesCannotAccessEachOthersDeclarationsWithoutImport() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module internal as
                          pub fnc secret(): int { return 42; }
                        end

                        define module worker as
                          pub fnc wrong(): int {
                            return internal.secret();
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("explicit import"),
                failure.getMessage());
    }

    @Test
    void moduleCannotReadOtherModuleCallableEvenWhenUniqueUnqualified() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module internal as
                          pub fnc secret(): int { return 42; }
                        end

                        define module worker as
                          pub fnc wrong(): int {
                            return secret();
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("explicit import"),
                failure.getMessage());
    }

    @Test
    void moduleCanCallItsOwnMembersWithoutGlobalAccess() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module worker as
                  fnc local(): int { return 42; }
                  pub fnc run(): int { return local(); }
                end
                """)));
    }

    @Test
    void classCannotCaptureSiblingModuleOrClassNamespace() {
        IllegalArgumentException otherModule = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module other as
                          pub fnc value(): int { return 9; }
                        end

                        define class Worker as
                          pub run(): int { return other.value(); }
                        end
                        """)));
        assertTrue(otherModule.getMessage().contains("explicit import"));

        IllegalArgumentException siblingClass = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Other as
                          pub static fnc value(): int { return 9; }
                        end

                        define class Worker as
                          pub run(): int { return Other.value(); }
                        end
                        """)));
        assertTrue(siblingClass.getMessage().contains("explicit import"));
    }

    @Test
    void nonlexicalCallbackCannotLaunderClassAccessToOutsideCallable() {
        IllegalArgumentException escaped = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc globalHelper(): int { return 4; }

                        define class Worker as
                          pub callback(): (() => int) {
                            return nlex || -> { return globalHelper(); };
                          }
                        end
                        """)));
        assertTrue(escaped.getMessage().contains("cannot implicitly access"),
                escaped.getMessage());
    }

    @Test
    void lambdaSyntaxRequiresSlimArrow() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): void {
                  val create = || -> { return 42; };
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main(): void {
                  val create = || => { return 42; };
                  return;
                }
                """));
    }


    @Test
    void actorOnStartIsReservedActorLocalZeroArgVoidHook() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  on_start(): void {
                    return;
                  }
                end
                """)));

        IllegalArgumentException publicHook = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub on_start(): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(publicHook.getMessage().contains("actor-local"), publicHook.getMessage());

        IllegalArgumentException parameterizedHook = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(value: int): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(parameterizedHook.getMessage().contains("zero arguments"),
                parameterizedHook.getMessage());

        IllegalArgumentException nonVoidHook = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): int {
                            return 1;
                          }
                        end
                        """)));
        assertTrue(nonVoidHook.getMessage().contains("return void"),
                nonVoidHook.getMessage());
    }

    @Test
    void ordinaryClassCannotDeclareReservedActorOnStartHook() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Worker as
                          on_start(): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("only be declared by an actor class"),
                failure.getMessage());
    }


    @Test
    void spawnActorTypechecksAsActorRefWithReadyDoneAndMailboxSend() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  on_start(): void {
                    return;
                  }

                  pub work(): void {
                    self.send("hello");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("start");
                  await worker.done;
                  return;
                }
                """)));
    }

    @Test
    void spawnRejectsOrdinaryClassesAndConstructorStyleArguments() {
        IllegalArgumentException ordinary = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Worker as
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          return;
                        }
                        """)));
        assertTrue(ordinary.getMessage().contains("spawn requires an actor class"),
                ordinary.getMessage());

        IllegalArgumentException arguments = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                        end

                        pub routine main(): void {
                          val worker = spawn Worker(1);
                          return;
                        }
                        """)));
        assertTrue(arguments.getMessage().contains("does not accept constructor/startup arguments"),
                arguments.getMessage());
    }

    @Test
    void actorTransportMembersAreReservedAndMailboxRejectsClosures() {
        IllegalArgumentException overrideSend = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub send(value: String): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(overrideSend.getMessage().contains("reserved"),
                overrideSend.getMessage());

        IllegalArgumentException functionPayload = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send(|| -> { return 1; });
                          return;
                        }
                        """)));
        assertTrue(functionPayload.getMessage().contains("concrete owned/sendable"),
                functionPayload.getMessage());
    }

    @Test
    void onStartCannotBeInvokedDirectlyFromActorCode() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): void {
                            return;
                          }

                          pub run(): void {
                            self.on_start();
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot be called directly"),
                failure.getMessage());
    }

}
