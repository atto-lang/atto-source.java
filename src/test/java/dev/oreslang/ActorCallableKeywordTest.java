package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCallableKeywordTest {

    @Test
    void actorIsSharedAndIsoactorIsPrivateForFunctionsRoutinesAndClasses() {
        List<Token> tokens = new Lexer("actor isoactor").scan();
        assertEquals(Token.Type.ACTOR, tokens.get(0).type());
        assertEquals(Token.Type.ISOACTOR, tokens.get(1).type());

        Ast.Program program = Parser.parse("""
                actor fnc shared_fnc() => void { return; }
                actor routine shared_routine() => void { return; }
                isoactor fnc private_fnc() => void { return; }
                isoactor routine private_routine() => void { return; }

                actor SharedBox {
                  let int value = 1;
                }

                isoactor PrivateBox {
                  let int value = 1;
                }
                """);

        List<Ast.Decl> declarations = program.modules().getFirst().declarations();

        assertEquals(Ast.ActorKind.SHARED, ((Ast.FunctionDecl) declarations.get(0)).actorKind());
        assertEquals(Ast.CallableKind.FNC, ((Ast.FunctionDecl) declarations.get(0)).kind());

        assertEquals(Ast.ActorKind.SHARED, ((Ast.FunctionDecl) declarations.get(1)).actorKind());
        assertEquals(Ast.CallableKind.ROUTINE, ((Ast.FunctionDecl) declarations.get(1)).kind());

        assertEquals(Ast.ActorKind.PRIVATE, ((Ast.FunctionDecl) declarations.get(2)).actorKind());
        assertEquals(Ast.CallableKind.FNC, ((Ast.FunctionDecl) declarations.get(2)).kind());

        assertEquals(Ast.ActorKind.PRIVATE, ((Ast.FunctionDecl) declarations.get(3)).actorKind());
        assertEquals(Ast.CallableKind.ROUTINE, ((Ast.FunctionDecl) declarations.get(3)).kind());

        assertEquals(Ast.ActorKind.SHARED, ((Ast.ClassDecl) declarations.get(4)).actorKind());
        assertEquals(Ast.ActorKind.PRIVATE, ((Ast.ClassDecl) declarations.get(5)).actorKind());
    }

    @Test
    void actorCallablesSpawnFreshActorsAndPreserveDeclaredResults() throws Exception {
        String output = run("""
                actor fnc add_one(int value) => int {
                  return value + 1;
                }

                actor routine shared_emit(String value) => void {
                  stdio.stdout.write(value);
                  return;
                }

                isoactor fnc double_it(int value) => int {
                  return value * 2;
                }

                isoactor routine private_emit(String value) => void {
                  stdio.stdout.write(value);
                  return;
                }

                pub routine main() => void {
                  stdio.stdout.write(await spawn add_one(41));
                  stdio.stdout.write(":");
                  await spawn shared_emit("shared");
                  stdio.stdout.write(":");
                  stdio.stdout.write(await spawn double_it(21));
                  stdio.stdout.write(":");
                  await spawn private_emit("private");
                  return;
                }
                """);

        assertEquals("42:shared:42:private", output);
    }

    @Test
    void actorKeywordsRemainReservedButActorBuiltinNamespaceStillParses() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main() => void {
                  val int isoactor = 1;
                  return;
                }
                """));

        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main() => void {
                  actor.gc();
                  return;
                }
                """));
    }

    @Test
    void actorMainRemainsForbidden() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub isoactor routine main() => void { return; }
                """)));
    }

    @Test
    void actorBoundaryTypesAreCheckedStatically() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(Mutex<int> value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(MutexGuard<int> value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor fnc invalid(SharedMutex<int> value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(Future<int> value) => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc invalid(&int value) => void { return; }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc valid(SharedMutex<int> value) => void { return; }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                isoactor fnc valid(int value) => int { return value; }
                """)));
    }

    @Test
    void actorTurnsCannotSynchronouslyInvokeAnotherActorCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc child(int value) => int {
                          return value + 1;
                        }

                        actor fnc parent(int value) => int {
                          return child(value);
                        }
                        """)));

        assertTrue(failure.getMessage().contains("must be invoked with 'spawn"));
    }

    @Test
    void actorClassEntrypointsOmitFncAndRequireSpawn() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                shared actor Counter {
                  let int value = 0;

                  increment() => int {
                    self.value = self.value + 1;
                    return self.value;
                  }

                  pub parallel actor add() => int {
                    return self.increment();
                  }

                  pub exclusive actor reset() => void {
                    self.value = 0;
                    return;
                  }
                }

                pub routine main() => void {
                  val counter = spawn Counter();
                  val first = spawn counter.add();
                  val second = spawn counter.add();
                  stdio.stdout.write(await first);
                  stdio.stdout.write(await second);
                  await spawn counter.reset();
                  return;
                }
                """));

        Ast.ClassDecl counter = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorMethodKind.NONE, counter.methods().get(0).actorMethodKind());
        assertEquals(Ast.ActorMethodKind.PARALLEL, counter.methods().get(1).actorMethodKind());
        assertEquals(Ast.ActorMethodKind.EXCLUSIVE, counter.methods().get(2).actorMethodKind());

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared actor Broken {
                  pub fnc bad() => void { return; }
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor Broken {
                  pub parallel actor bad() => void { return; }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Broken {
                  let int value = 0;
                  pub parallel actor bad() => void {
                    self.value = self.value + 1;
                    return;
                  }
                }
                """)));
    }

    @Test
    void actorClassSpawnRunsPersistentSharedActor() throws Exception {
        String output = run("""
                shared actor Counter {
                  let int value = 0;

                  increment() => int {
                    self.value = self.value + 1;
                    return self.value;
                  }

                  pub parallel actor add() => int {
                    return self.increment();
                  }

                  pub exclusive actor reset() => void {
                    self.value = 0;
                    return;
                  }

                  pub actor read() => int {
                    return self.value;
                  }
                }

                pub routine main() => void {
                  val counter = spawn Counter();
                  val first = spawn counter.add();
                  val second = spawn counter.add();
                  await first;
                  await second;
                  stdio.stdout.write(await spawn counter.read());
                  await spawn counter.reset();
                  stdio.stdout.write(":");
                  stdio.stdout.write(await spawn counter.read());
                  return;
                }
                """);

        assertEquals("2:0", output);
    }

    @Test
    void sharedActorConstructionAcceptsFrozenDataButRejectsIdentityBearingState() throws Exception {
        String output = run("""
                shared actor Holder {
                  let Array<int> values;

                  pub actor first() => int {
                    return self.values[0];
                  }
                }

                pub routine main() => void {
                  val holder = spawn Holder(arr[7, 8]);
                  stdio.stdout.write(await spawn holder.first());
                  return;
                }
                """);
        assertEquals("7", output);

        IllegalArgumentException objectCapture = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end

                        shared actor Holder {
                          let Box box;
                          pub actor read() => int { return self.box.value; }
                        }

                        pub routine main() => void {
                          val holder = spawn Holder(new Box());
                          return;
                        }
                        """)));
        assertTrue(objectCapture.getMessage().contains("cannot capture")
                || objectCapture.getMessage().contains("actor constructor field"));

        IllegalArgumentException capabilityCapture = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        shared actor Holder {
                          let SharedMutex<int> state;
                          pub actor read() => int { return 1; }
                        }

                        pub routine main() => void {
                          val state = SharedMutex.new(1);
                          val holder = spawn Holder(state);
                          return;
                        }
                        """)));
        assertTrue(capabilityCapture.getMessage().contains("cannot capture")
                || capabilityCapture.getMessage().contains("actor constructor field"));
    }

    @Test
    void actorMailboxSurfaceRejectsOpaqueIdentityAndCapabilityReturns() {
        IllegalArgumentException sourceObject = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end

                        shared actor Worker {
                          pub actor accept(Box box) => int {
                            return box.value;
                          }
                        }
                        """)));
        assertTrue(sourceObject.getMessage().contains("cannot transport source class"));

        IllegalArgumentException optionWithoutCodec = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        shared actor Worker {
                          pub actor accept(Option<int> value) => int {
                            return 1;
                          }
                        }
                        """)));
        assertTrue(optionWithoutCodec.getMessage().contains("does not yet have an explicit actor transport codec"));

        IllegalArgumentException sharedMutexReturn = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        shared actor Worker {
                          pub actor leak() => SharedMutex<int> {
                            return SharedMutex.new(1);
                          }
                        }
                        """)));
        assertTrue(sharedMutexReturn.getMessage().contains("cannot return SharedMutex"));
    }

    @Test
    void parallelActorCannotReachNestedMutableStateThroughStableField() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 0;
                        end

                        shared actor Worker {
                          val Box box = new Box();

                          pub parallel actor bad() => void {
                            self.box.value = self.box.value + 1;
                            return;
                          }
                        }
                        """)));

        assertTrue(failure.getMessage().contains("parallel actor method cannot directly read mutable actor field 'box'"));
    }

    @Test
    void parallelActorRequiresSharedMutexRatherThanActorLocalMutex() {
        IllegalArgumentException localMutex = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        shared actor Worker {
                          val Mutex<int> state = Mutex.new(0);

                          pub parallel actor bad() => int {
                            val guard = self.state.try_lock();
                            return 1;
                          }
                        }
                        """)));
        assertTrue(localMutex.getMessage().contains("parallel actor method cannot directly read mutable actor field 'state'"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Worker {
                  val SharedMutex<int> state = SharedMutex.new(0);

                  pub parallel actor try_read() => int {
                    val maybe_guard = self.state.try_lock();
                    if maybe_guard.is_none(); do
                      return -1;
                    fi
                    val guard = maybe_guard.unwrap();
                    val value = guard.value;
                    guard.release();
                    return value;
                  }
                }
                """)));
    }

    @Test
    void actorCapabilityReturnCannotHideInsideContainers() {
        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        shared actor Worker {
                          pub actor leak() => Array<SharedMutex<int>> {
                            return arr[SharedMutex.new(1)];
                          }
                        }
                        """)));
        assertTrue(nested.getMessage().contains("cannot return SharedMutex"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-callables.ores")
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
