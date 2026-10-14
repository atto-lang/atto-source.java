package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Static and executable privacy regression cases for structural matching. */
final class StructuralPrivacyBoundaryTest {
    private static String execute(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "structural-privacy.ores")
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

    @Test
    void inlineStructuralPatternsMatchPublicFieldsButNotPrivateFields() throws Exception {
        String program = """
                define class Base as end

                define class Candidate extends Base as
                  private val int hidden = 19;
                  pub val int exposed = 7;
                end

                fnc check(Base value): int {
                  match value over
                    on structural { hidden: int } view -> { return 99; }
                    on structural { exposed: int } view -> { return view.exposed; }
                    on _ -> { return 0; }
                  end
                }

                pub fnc main(): void {
                  stdio.println(check(new Candidate()));
                  stdio.println(check(new Base()));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        String output = execute(program);
        assertEquals("7\n0\n", output.replace("\r\n", "\n"));
    }

    @Test
    void namedClassStructuralPatternsUsePublicClassShape() throws Exception {
        String program = """
                define class Contract as
                  pub val int id = 1;
                  private val int secret = 77;
                end

                define class Candidate as
                  pub val int id = 42;
                  private val int secret = 999;
                end

                define class HiddenCandidate as
                  private val int id = 42;
                end

                fnc test_candidate(Candidate value): int {
                  match value over
                    on structural Contract matched -> { return matched.id; }
                    on _ -> { return 0; }
                  end
                }

                fnc test_hidden(HiddenCandidate value): int {
                  match value over
                    on structural Contract matched -> { return 1; }
                    on _ -> { return 0; }
                  end
                }

                pub fnc main(): void {
                  stdio.println(test_candidate(new Candidate()));
                  stdio.println(test_hidden(new HiddenCandidate()));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        String output = execute(program);
        assertEquals("42\n0\n", output.replace("\r\n", "\n"));
    }

    @Test
    void plainRecordMaySatisfyNamedPublicClassShapeWithoutNominalIdentity() throws Exception {
        String program = """
                define class PublicShape as
                  pub val int id = 0;
                  private val int secret = 99;
                end

                pub fnc main(): void {
                  const row = struct{id: int}{id: 42};
                  match row over
                    on structural PublicShape shaped -> {
                      stdio.println(shaped.id);
                    }
                    on _ -> {
                      stdio.println(-1);
                    }
                  end
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("42\n", execute(program).replace("\r\n", "\n"));
    }

    @Test
    void recordWithoutMethodsCannotStaticallyClaimNamedClassMethodContract() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                        define class CallableShape as
                          pub val int id = 0;
                          pub read(): int { return 1; }
                        end

                        pub fnc main(): void {
                          const row = struct{id: int}{id: 42};
                          match row over
                            on structural CallableShape shaped -> {
                              stdio.println(99);
                            }
                            on _ -> { stdio.println(0); }
                          end
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("unreachable structural pattern")
                        || error.getMessage().contains("cannot satisfy"),
                error.getMessage());
    }

    @Test
    void inheritedPrivateFieldsNeverCountTowardRuntimeStructuralShape() throws Exception {
        String program = """
                define class Base as
                  private val int hidden = 31;
                  pub val int visible = 6;
                end

                define class Derived extends Base as end

                fnc classify(Base value): int {
                  match value over
                    on structural { hidden: int } view -> { return 99; }
                    on structural { visible: int } view -> { return view.visible; }
                    on _ -> { return 0; }
                  end
                }

                pub fnc main(): void {
                  stdio.println(classify(new Derived()));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("6\n", execute(program).replace("\r\n", "\n"));
    }

    @Test
    void privateClassFieldCannotEstablishPublicRecordReturnType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Vault as
                          private val int secret = 99;
                        end

                        fnc leak(): { secret: int } {
                          return new Vault();
                        }
                        """)));
        assertNotNull(failure.getMessage());
    }

    @Test
    void externalObjectDestructuringRejectsPrivateFieldEvenWithPublicPeer() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Vault as
                          private val int secret = 9;
                          pub val int exposed = 2;
                        end

                        fnc leak(Vault vault): int {
                          val { secret } = vault;
                          return secret;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("private field"), failure.getMessage());
    }
}
