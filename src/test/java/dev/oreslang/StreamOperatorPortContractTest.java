package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StreamOperatorPortContractTest {
    private static String stage(String name, String input, String output,
                                String emitExpression) {
        return """
                define actor %s implements StreamOperator<%s, %s> as
                  receive(ActorMail<%s> mail): void {
                    self.send(%s);
                    return;
                  }
                end
                """.formatted(name, input, output, input, emitExpression);
    }

    private static IllegalArgumentException rejected(String source) {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test void typeCompatibleMultiStageGraphFailsClosedUntilBoundedRuntimeExists() {
        String source = stage("Parse", "String", "int", "1")
                + stage("Validate", "int", "bool", "true")
                + stage("Enrich", "bool", "String", "\"done\"")
                + """
                  pub routine main(): void {
                    val ActorStream<String, String> graph =
                        spawn Parse() pipe spawn Validate() pipe spawn Enrich();
                    return;
                  }
                  """;
        assertTrue(rejected(source).getMessage().contains(
                "typed pipe ports are compatible"), "must not claim execution readiness");
    }

    @Test void rejectsMismatchedPortBeforeGraphAdmission() {
        String source = stage("Parse", "String", "int", "1")
                + stage("Validate", "String", "bool", "true")
                + """
                  pub routine main(): void {
                    val graph = spawn Parse() pipe spawn Validate();
                    return;
                  }
                  """;
        assertTrue(rejected(source).getMessage().contains(
                "pipe port type mismatch"));
    }

    @Test void rejectsMismatchedMiddleOfThreeStageGraph() {
        String source = stage("Parse", "String", "int", "1")
                + stage("Validate", "int", "bool", "true")
                + stage("Enrich", "int", "String", "\"done\"")
                + """
                  pub routine main(): void {
                    val graph = spawn Parse() pipe spawn Validate() pipe spawn Enrich();
                    return;
                  }
                  """;
        assertTrue(rejected(source).getMessage().contains(
                "pipe port type mismatch"));
    }

    @Test void rejectsActorWithoutExplicitStreamOperator() {
        String source = """
                define actor Plain as
                  receive(ActorMail<int> mail): void { return; }
                end
                """ + stage("Sink", "int", "bool", "true")
                + """
                  pub routine main(): void {
                    val graph = spawn Plain() pipe spawn Sink();
                    return;
                  }
                  """;
        assertTrue(rejected(source).getMessage().contains(
                "must explicitly implement StreamOperator"));
    }

    @Test void rejectsReceiveTypeMismatchAgainstDeclaredInput() {
        String source = """
                define actor Wrong implements StreamOperator<String, int> as
                  receive(ActorMail<int> mail): void {
                    self.send(1);
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        assertTrue(rejected(source).getMessage().contains(
                "but receive(ActorMail<T>) uses"));
    }

    @Test void rejectsOutboundValueIncompatibleWithContract() {
        String source = """
                define actor Wrong implements StreamOperator<int, String> as
                  receive(ActorMail<int> mail): void {
                    self.send(23);
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        assertTrue(rejected(source).getMessage().contains(
                "StreamOperator output"));
    }

    @Test void rejectsStreamOperatorMarkerOnOrdinaryClass() {
        String source = """
                define class Worker implements StreamOperator<int, String> as
                  pub process(int item): String { return "ok"; }
                end
                pub routine main(): void { return; }
                """;
        assertTrue(rejected(source).getMessage().contains(
                "only be implemented by actors"));
    }

    @Test void rejectsWrongNumberOfPortParameters() {
        String source = """
                define actor Wrong implements StreamOperator<int> as
                  receive(ActorMail<int> mail): void { return; }
                end
                pub routine main(): void { return; }
                """;
        assertTrue(rejected(source).getMessage().contains(
                "exactly two explicit port"));
    }

    @Test void rejectsSinkAttemptingToEmit() {
        String source = """
                define actor Sink implements StreamOperator<int, void> as
                  receive(ActorMail<int> mail): void {
                    self.send(1);
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        assertTrue(rejected(source).getMessage().contains(
                "cannot emit a value"));
    }

    @Test void preservesOriginalEagerFunctionalPiping() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc add(int a, int b): int { return a + b; }
                pub routine main(): void {
                  val int answer = 5 |> add(7);
                  return;
                }
                """)));
    }
}
