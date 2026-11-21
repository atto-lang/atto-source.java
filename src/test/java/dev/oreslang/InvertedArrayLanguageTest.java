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
    void directTypeAliasPreservesInvertedOwnershipAndExecutionProof() throws Exception {
        String source = """
                define inverted class Particle as
                  let f64 x;
                end
                type ParticleStore = InvertedArray<Particle>;

                pub routine main(): void {
                  let mut ParticleStore particles = new InvertedArray<Particle>();
                  particles.push(1.5);
                  particles[0].x = 8.25;
                  stdio.stdout.write(particles[0].x);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(
                        OresLanguage.ID, source, "inverted-array-alias.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(program);
        }

        assertEquals(
                "8.25",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void invertedBracketFieldsRemainProofGatedInsideResumableExpressions() throws Exception {
        String source = """
                define inverted class Particle as
                  let f64 x;
                end

                async fnc delayed(): f64 {
                  return 2.0;
                }

                pub async fnc main(): void {
                  let mut InvertedArray<Particle> particles =
                      new InvertedArray<Particle>();
                  particles.push(4.0);

                  val first = (await delayed()) + particles[0].x;
                  particles[0].x = await delayed();
                  val second = (await delayed()) + particles[0].x;

                  stdio.stdout.write(first);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(
                        OresLanguage.ID, source, "inverted-array-resume.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(program);
        }

        assertEquals(
                "6.0|4.0",
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
    void invertedStorageCannotEscapeThroughReturnOrTaskResult() {
        IllegalArgumentException directReturn = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): InvertedArray<Particle> {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          return particles;
                        }
                        """));
        assertTrue(
                directReturn.getMessage().contains(
                        "InvertedArray storage is lexical in v1"),
                directReturn::getMessage);

        IllegalArgumentException wrappedReturn = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        type HiddenStore = InvertedArray<Particle>;

                        fnc bad(): Option<HiddenStore> {
                          let mut HiddenStore particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          return Some(particles);
                        }
                        """));
        assertTrue(
                wrappedReturn.getMessage().contains(
                        "InvertedArray storage is lexical in v1"),
                wrappedReturn::getMessage);

        IllegalArgumentException taskResult = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        async fnc bad(): void {
                          let worker = task {
                            let mut InvertedArray<Particle> particles =
                                new InvertedArray<Particle>();
                            particles.push(1.0);
                            return particles;
                          };
                          await worker;
                          return;
                        }
                        """));
        assertTrue(
                taskResult.getMessage().contains(
                        "InvertedArray storage is lexical in v1"),
                taskResult::getMessage);
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
    void wrappedInvertedStorageCannotBypassCaptureOrChannelBoundaries() {
        IllegalArgumentException wrappedClosure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          val wrapped = Some(particles);
                          val (() => int) read = || -> {
                            val hidden = wrapped;
                            return 1;
                          };
                          return;
                        }
                        """));
        assertTrue(
                wrappedClosure.getMessage().contains(
                        "closure cannot capture InvertedArray storage"),
                wrappedClosure::getMessage);

        IllegalArgumentException channel = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          val channel =
                              Channel.new<InvertedArray<Particle>>(1);
                          val pending = nb writech channel, particles;
                          return;
                        }
                        """));
        assertTrue(
                channel.getMessage().contains(
                        "cannot transport InvertedArray storage"),
                channel::getMessage);

        IllegalArgumentException channelRead = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(Channel<InvertedArray<Particle>> channel): void {
                          val received = readch channel;
                          return;
                        }
                        """));
        assertTrue(
                channelRead.getMessage().contains(
                        "cannot transport InvertedArray storage"),
                channelRead::getMessage);

        IllegalArgumentException aliasedChannelRead = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        type HiddenStore = InvertedArray<Particle>;
                        type HiddenChannel = Channel<HiddenStore>;

                        fnc bad(HiddenChannel channel): void {
                          val received = readch channel;
                          return;
                        }
                        """));
        assertTrue(
                aliasedChannelRead.getMessage().contains(
                        "cannot transport InvertedArray storage"),
                aliasedChannelRead::getMessage);
    }

    @Test
    void typeAliasesCannotLaunderInvertedStorageAcrossClosureOrChannelBoundaries() {
        IllegalArgumentException aliasClosure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        type HiddenStore = InvertedArray<Particle>;

                        fnc bad(): void {
                          let mut HiddenStore particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          val (() => int) capture = || -> {
                            val hidden = particles;
                            return 1;
                          };
                          return;
                        }
                        """));
        assertTrue(
                aliasClosure.getMessage().contains(
                        "closure cannot capture InvertedArray storage"),
                aliasClosure::getMessage);

        IllegalArgumentException wrapperAliasClosure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        type HiddenStore = InvertedArray<Particle>;
                        type HiddenOption = Option<HiddenStore>;

                        fnc bad(): void {
                          let mut HiddenStore particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          val HiddenOption wrapped = Some(particles);
                          val (() => int) capture = || -> {
                            val hidden = wrapped;
                            return 1;
                          };
                          return;
                        }
                        """));
        assertTrue(
                wrapperAliasClosure.getMessage().contains(
                        "closure cannot capture InvertedArray storage"),
                wrapperAliasClosure::getMessage);

        IllegalArgumentException genericWrapperAlias = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        type HiddenStore = InvertedArray<Particle>;
                        type Wrapped<T> = Option<T>;

                        fnc bad(): void {
                          let mut HiddenStore particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          val Wrapped<HiddenStore> wrapped = Some(particles);
                          val (() => int) capture = || -> {
                            val hidden = wrapped;
                            return 1;
                          };
                          return;
                        }
                        """));
        assertTrue(
                genericWrapperAlias.getMessage().contains(
                        "closure cannot capture InvertedArray storage"),
                genericWrapperAlias::getMessage);

        IllegalArgumentException aliasChannel = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end
                        type HiddenStore = InvertedArray<Particle>;

                        fnc bad(): void {
                          let mut HiddenStore particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          val channel = Channel.new<HiddenStore>(1);
                          val pending = nb writech channel, particles;
                          return;
                        }
                        """));
        assertTrue(
                aliasChannel.getMessage().contains(
                        "cannot transport InvertedArray storage"),
                aliasChannel::getMessage);
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
}
