package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.InvertedArray;
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
    void executableAdmissionPinsTheTypecheckedPhysicalSchema() {
        Ast.Program typed = TypeChecker.check(Parser.parse(READ_ONLY_SOURCE));
        Ast.MemberExpr member = returnedMember(typed);
        Ast.InvertedIndexTypeEvidence evidence =
                Ast.CheckedInvertedIndexTypes.lookup(member);
        assertNotNull(evidence);

        long epoch = Ast.CheckedInvertedIndexes.requireProgramEpoch(typed);
        Ast.InvertedIndexAdmission admission = Ast.CheckedInvertedIndexes.require(
                typed, member, epoch, Ast.InvertedIndexAccess.READ);

        Ast.ClassDecl particle = typed.modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .filter(klass -> klass.name().equals("Particle"))
                .findFirst()
                .orElseThrow();
        var expected =
                InvertedArray.Schema.fromClass(particle).physicalFingerprint();

        assertEquals(expected, evidence.physicalSchemaFingerprint());
        assertEquals(expected, admission.physicalSchemaFingerprint());
        assertThrows(UnsupportedOperationException.class,
                () -> admission.physicalSchemaFingerprint().add("forged"));
    }


    /**
     * Inject a host-created array into the *actual checked evaluator frame*.
     * Guest source cannot construct this substitution, so this test deliberately
     * uses reflection without adding a production host-injection capability.
     * The evaluator must consult the exact Program/MemberExpr admission before
     * invoking bindField(), index(), or any column read.
     */
    @Test
    void evaluatorRejectsSameNamePhysicalSchemaSubstitutionBeforeColumnAccess()
            throws ReflectiveOperationException {
        Ast.Program typed = TypeChecker.check(Parser.parse(READ_ONLY_SOURCE));
        Ast.MemberExpr site = returnedMember(typed);
        Ast.ClassDecl checkedRow = typed.modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.ClassDecl substitutedRow = (Ast.ClassDecl) Parser.parse("""
                define inverted class Particle as
                  val i64 x;
                  val f64 y;
                  val bool ready;
                end
                """).modules().getFirst().declarations().getFirst();

        try (InvertedArray valid = InvertedArray.fromClass(checkedRow, 0);
                InvertedArray substituted = InvertedArray.fromClass(substitutedRow, 0)) {
            valid.pushValues(1.5, 2.5, true);
            assertEquals(1.5, evaluateCheckedInvertedField(typed, site, valid));

            // With no rows, an attempted index/column read would instead fail
            // out of bounds. The fingerprint error establishes the guard order.
            assertEquals(0, substituted.size());
            java.lang.reflect.InvocationTargetException rejected = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> evaluateCheckedInvertedField(typed, site, substituted));
            IllegalStateException failure = assertInstanceOf(
                    IllegalStateException.class, rejected.getCause());
            assertTrue(
                    failure.getMessage().contains("schema fingerprint changed after admission"),
                    failure::getMessage);
            assertEquals(0, substituted.size());
        }
    }


    @Test
    void evaluatorRejectsWrongLayoutOnAdmittedWriteBeforeMutatingColumns()
            throws ReflectiveOperationException {
        Ast.Program typed = TypeChecker.check(Parser.parse("""
                define inverted class Particle as
                  let f64 x;
                  val f64 y;
                  val bool ready;
                end
                fnc write(): void {
                  let mut InvertedArray<Particle> particles =
                      new InvertedArray<Particle>();
                  particles.push(1.5, 2.5, true);
                  particles[0].x = 9.25;
                  return;
                }
                """));
        Ast.FunctionDecl function = (Ast.FunctionDecl) typed.modules().getFirst()
                .declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .findFirst().orElseThrow();
        Ast.MemberExpr assignmentSite = function.body().stream()
                .filter(Ast.ExprStmt.class::isInstance)
                .map(Ast.ExprStmt.class::cast)
                .map(Ast.ExprStmt::expression)
                .filter(Ast.AssignExpr.class::isInstance)
                .map(Ast.AssignExpr.class::cast)
                .map(Ast.AssignExpr::target)
                .map(Ast.MemberExpr.class::cast)
                .findFirst().orElseThrow();
        Ast.ClassDecl checkedRow = typed.modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
        Ast.ClassDecl substitutedRow = (Ast.ClassDecl) Parser.parse("""
                define inverted class Particle as
                  let i64 x;
                  val f64 y;
                  val bool ready;
                end
                """).modules().getFirst().declarations().getFirst();
        try (InvertedArray valid = InvertedArray.fromClass(checkedRow, 0);
                InvertedArray substituted = InvertedArray.fromClass(substitutedRow, 0)) {
            var validRow = valid.pushValues(1.5, 2.5, true);
            assertEquals(9.25,
                    assignCheckedInvertedField(typed, assignmentSite, valid, 9.25));
            assertEquals(9.25, valid.read(validRow, "x"));

            var substitutedRowHandle = substituted.pushValues(17L, 2.5, true);
            java.lang.reflect.InvocationTargetException rejected = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> assignCheckedInvertedField(
                            typed, assignmentSite, substituted, 9.25));
            IllegalStateException failure = assertInstanceOf(
                    IllegalStateException.class, rejected.getCause());
            assertTrue(
                    failure.getMessage().contains("schema fingerprint changed after admission"),
                    failure::getMessage);
            assertEquals(17L, substituted.read(substitutedRowHandle, "x"));
            assertEquals(1, substituted.size());
        }
    }


    @Test
    void genericEvaluatorIndexDispatchNeverExposesHostRowProjection()
            throws ReflectiveOperationException {
        Ast.Program typed = TypeChecker.check(Parser.parse(READ_ONLY_SOURCE));
        Ast.IndexExpr bareIndex = (Ast.IndexExpr) returnedMember(typed).receiver();
        Ast.ClassDecl checkedRow = typed.modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
        try (InvertedArray array = InvertedArray.fromClass(checkedRow, 0)) {
            var row = array.pushValues(1.5, 2.5, true);

            java.lang.reflect.InvocationTargetException read = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> evaluateCheckedInvertedField(typed, bareIndex, array));
            IllegalStateException readFailure = assertInstanceOf(
                    IllegalStateException.class, read.getCause());
            assertTrue(readFailure.getMessage().contains(
                    "InvertedArray indexing requires an admitted"));

            java.lang.reflect.InvocationTargetException write = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> assignCheckedInvertedField(typed, bareIndex, array, 9.25));
            IllegalStateException writeFailure = assertInstanceOf(
                    IllegalStateException.class, write.getCause());
            assertTrue(writeFailure.getMessage().contains(
                    "InvertedArray indexed writes require an admitted"));

            assertEquals(1.5, array.read(row, "x"));
            assertEquals(1, array.size());
        }
    }

    private static Object assignCheckedInvertedField(
            Ast.Program typed, Ast.Expr site, InvertedArray array, Object value)
            throws ReflectiveOperationException {
        return executeCheckedInvertedField(typed, site, array, value, true);
    }

    private static Object evaluateCheckedInvertedField(
            Ast.Program typed, Ast.Expr site, InvertedArray array)
            throws ReflectiveOperationException {
        return executeCheckedInvertedField(typed, site, array, null, false);
    }

    private static Object executeCheckedInvertedField(
            Ast.Program typed, Ast.Expr site, InvertedArray array,
            Object value, boolean writing)
            throws ReflectiveOperationException {
        Class<?> evaluatorClass = Class.forName(
                "dev.oreslang.nodes.OresEvalRootNode$Evaluator");
        Class<?> envClass = Class.forName(
                "dev.oreslang.nodes.OresEvalRootNode$Env");

        var constructor = evaluatorClass.getDeclaredConstructor(
                Ast.Program.class,
                dev.oreslang.runtime.OresContext.class,
                String.class,
                long.class,
                long.class);
        constructor.setAccessible(true);
        Object evaluator = constructor.newInstance(
                typed,
                null,
                "<inverted-substitution-test>",
                Ast.CheckedTaskCaptures.requireProgramEpoch(typed),
                Ast.CheckedInvertedIndexes.requireProgramEpoch(typed));

        var envConstructor = envClass.getDeclaredConstructor(envClass);
        envConstructor.setAccessible(true);
        Object env = envConstructor.newInstance((Object) null);
        var define = envClass.getDeclaredMethod(
                "define", String.class, Object.class, Ast.BindingKind.class);
        define.setAccessible(true);
        define.invoke(env, "particles", array, Ast.BindingKind.LET);

        if (writing) {
            var assign = evaluatorClass.getDeclaredMethod(
                    "assignEvaluated", Ast.Expr.class, Object.class, envClass);
            assign.setAccessible(true);
            return assign.invoke(evaluator, site, value, env);
        }
        var eval = evaluatorClass.getDeclaredMethod("eval", Ast.Expr.class, envClass);
        eval.setAccessible(true);
        return eval.invoke(evaluator, site, env);
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
