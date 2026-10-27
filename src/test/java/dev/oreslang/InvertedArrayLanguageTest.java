package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class InvertedArrayLanguageTest {
    private static final String READ_ONLY_SOURCE = """
            define inverted class Particle as
              val f64 x;
              val f64 y;
              val bool ready;
            end

            fnc read(): f64 {
              let mut InvertedArray<Particle> particles = new InvertedArray<Particle>();
              particles.push(1.5, 2.5, true);
              return particles[0].x;
            }
            """;

    @Test
    void typedBracketFieldsExecuteThroughExactOwnershipAdmission() throws Exception {
        String source = """
                define inverted class Particle as
                  let f64 x;
                  val f64 y;
                  val bool ready;
                end

                pub routine main(): void {
                  let mut InvertedArray<Particle> particles = new InvertedArray<Particle>();
                  particles.push(1.5, 2.5, true);
                  particles.push(3.5, 4.5, false);

                  stdio.stdout.write(particles[1].x);
                  stdio.stdout.write("|");
                  particles[0].x = 9.25;
                  stdio.stdout.write(particles[0].x);
                  stdio.stdout.write("|");
                  stdio.stdout.write(particles[0].y);
                  stdio.stdout.write("|");
                  stdio.stdout.write(particles[0].ready);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(
                        OresLanguage.ID, source, "inverted-array-language.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(program);
        }

        assertEquals(
                "3.5|9.25|2.5|true",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void standaloneRowProjectionCannotEscape() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          val row = particles[0];
                          return;
                        }
                        """));
        assertTrue(
                failure.getMessage().contains("row projection cannot escape"),
                failure::getMessage);
    }

    @Test
    void directInvertedRowsAreNotStandaloneValuesOrConstructibleObjects() {
        IllegalArgumentException parameter = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        fnc bad(Particle value): void { return; }
                        """));
        assertTrue(parameter.getMessage().contains("not a standalone value type"),
                parameter::getMessage);

        IllegalArgumentException construction = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        fnc bad(): void {
                          val value = new Particle();
                          return;
                        }
                        """));
        assertTrue(construction.getMessage().contains("cannot be constructed directly"),
                construction::getMessage);
    }

    @Test
    void closuresAndTasksCannotCaptureInvertedStorage() {
        IllegalArgumentException closure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          val (() => f64) read = || -> particles[0].x;
                          return;
                        }
                        """));
        assertTrue(closure.getMessage().contains("closure cannot capture InvertedArray"),
                closure::getMessage);

        IllegalArgumentException task = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        async fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          let worker = task {
                            return particles[0].x;
                          };
                          await worker;
                          return;
                        }
                        """));
        assertTrue(task.getMessage().contains("cannot capture InvertedArray"),
                task::getMessage);
    }

    @Test
    void bracketIndexCannotCallOrSuspendInV1() {
        for (String index : new String[] {"next()", "await nextAsync()"}) {
            String functions = index.startsWith("await")
                    ? "async fnc nextAsync(): int { return 0; }"
                    : "fnc next(): int { return 0; }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck("""
                            define inverted class Particle as
                              val f64 x;
                            end
                            %s
                            async fnc bad(): f64 {
                              let mut InvertedArray<Particle> particles =
                                  new InvertedArray<Particle>();
                              particles.push(1.0);
                              return particles[%s].x;
                            }
                            """.formatted(functions, index)));
            assertTrue(
                    failure.getMessage().contains("non-suspending scalar expression"),
                    failure::getMessage);
        }
    }

    @Test
    void checkedMetadataCopyDoesNotTransferInvertedLayoutAuthority() {
        Ast.Program typed = TypeChecker.check(Parser.parse(READ_ONLY_SOURCE));
        Ast.MemberExpr original = returnedMember(typed);

        assertNotNull(Ast.CheckedInvertedIndexTypes.lookup(original));
        long epoch = Ast.CheckedInvertedIndexes.requireProgramEpoch(typed);
        assertDoesNotThrow(() -> Ast.CheckedInvertedIndexes.require(
                typed, original, epoch, Ast.InvertedIndexAccess.READ));

        Ast.IndexExpr index = (Ast.IndexExpr) original.receiver();
        Ast.MemberExpr clone = new Ast.MemberExpr(
                new Ast.IndexExpr(index.receiver(), index.index()),
                original.member());
        Ast.copyCheckedMetadata(original, clone);

        assertNull(Ast.CheckedInvertedIndexTypes.lookup(clone));
        assertThrows(IllegalStateException.class, () ->
                Ast.CheckedInvertedIndexes.require(
                        typed, clone, epoch, Ast.InvertedIndexAccess.READ));
    }

    @Test
    void freshTypecheckRevokesPriorInvertedExecutionAuthorityUntilOwnershipRepublishes() {
        Ast.Program typed = TypeChecker.check(Parser.parse(READ_ONLY_SOURCE));
        Ast.MemberExpr member = returnedMember(typed);
        long first = Ast.CheckedInvertedIndexes.requireProgramEpoch(typed);

        TypeChecker.TypeCheckResult rechecked =
                TypeChecker.checkTypesWithDiagnostics(typed);
        assertSame(typed, rechecked.program());
        assertThrows(IllegalStateException.class, () ->
                Ast.CheckedInvertedIndexes.requireProgramEpoch(typed));
        assertThrows(IllegalStateException.class, () ->
                Ast.CheckedInvertedIndexes.require(
                        typed, member, first, Ast.InvertedIndexAccess.READ));

        OwnershipChecker.check(
                rechecked.program(), rechecked.shortInferredAliases());
        long second = Ast.CheckedInvertedIndexes.requireProgramEpoch(typed);
        assertTrue(second > first);
        assertDoesNotThrow(() -> Ast.CheckedInvertedIndexes.require(
                typed, member, second, Ast.InvertedIndexAccess.READ));
    }

    @Test
    void ownershipRecheckRevokesOlderInvertedExecutionEpoch() {
        Ast.Program typed = TypeChecker.check(Parser.parse(READ_ONLY_SOURCE));
        Ast.MemberExpr member = returnedMember(typed);
        long first = Ast.CheckedInvertedIndexes.requireProgramEpoch(typed);

        OwnershipChecker.check(typed);
        long second = Ast.CheckedInvertedIndexes.requireProgramEpoch(typed);

        assertTrue(second > first);
        assertThrows(IllegalStateException.class, () ->
                Ast.CheckedInvertedIndexes.require(
                        typed, member, first, Ast.InvertedIndexAccess.READ));
        assertDoesNotThrow(() -> Ast.CheckedInvertedIndexes.require(
                typed, member, second, Ast.InvertedIndexAccess.READ));
    }

    private static Ast.MemberExpr returnedMember(Ast.Program program) {
        Ast.FunctionDecl function = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned = (Ast.ReturnStmt) function.body().getLast();
        return (Ast.MemberExpr) returned.value();
    }

    @Test
    void invertedStorageCannotBeHiddenInAggregatesOrGlobalState() {
        for (String body : new String[] {
                """
                fnc bad(): void {
                  let mut InvertedArray<Particle> particles = new InvertedArray<Particle>();
                  val hidden = [particles];
                  return;
                }
                """,
                """
                fnc bad(): void {
                  let mut InvertedArray<Particle> particles = new InvertedArray<Particle>();
                  val hidden = (particles, 1);
                  return;
                }
                """,
                """
                fnc bad(): void {
                  let mut InvertedArray<Particle> particles = new InvertedArray<Particle>();
                  val hidden = infer struct { storage: particles };
                  return;
                }
                """,
                """
                fnc bad(): void {
                  let mut InvertedArray<Particle> particles = new InvertedArray<Particle>();
                  val hidden = Some(particles);
                  return;
                }
                """
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck("""
                            define inverted class Particle as
                              val f64 x;
                            end
                            %s
                            """.formatted(body)));
            assertTrue(
                    failure.getMessage().contains("InvertedArray"),
                    failure::getMessage);
        }

        IllegalArgumentException classField = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        define class Holder as
                          let InvertedArray<Particle> storage;
                        end
                        """));
        assertTrue(classField.getMessage().contains("InvertedArray"),
                classField::getMessage);

        IllegalArgumentException global = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        define module Store as
                          let InvertedArray<Particle> storage =
                              new InvertedArray<Particle>();
                        end
                        """));
        assertTrue(global.getMessage().contains("module/global")
                        || global.getMessage().contains("InvertedArray"),
                global::getMessage);
    }

    @Test
    void invertedStorageCannotEnterChannelOrSharedMutexState() {
        IllegalArgumentException channel = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        fnc bad(): void {
                          val Channel<InvertedArray<Particle>> ch =
                              Channel.new<InvertedArray<Particle>>(1);
                          return;
                        }
                        """));
        assertTrue(channel.getMessage().contains("InvertedArray"),
                channel::getMessage);

        IllegalArgumentException mutex = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          val guarded = SharedMutex.new(particles);
                          return;
                        }
                        """));
        assertTrue(mutex.getMessage().contains("InvertedArray"),
                mutex::getMessage);
    }

    @Test
    void actorCallableCannotTransportInvertedStorage() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        actor fnc bad(
                            InvertedArray<Particle> values): void {
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("InvertedArray")
                        || failure.getMessage().contains("actor boundary"),
                failure::getMessage);
    }

}
