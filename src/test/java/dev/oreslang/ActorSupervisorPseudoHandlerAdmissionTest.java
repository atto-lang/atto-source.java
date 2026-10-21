package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSupervisorPseudoHandlerAdmissionTest {

    private static final String COMPLETE_SUPERVISOR = """
            define supervisor actor Manager as
              on_throw(ChildFailure failure): void {
                failure.acknowledge();
                return;
              }
              on_raise(ChildFailure failure): void {
                failure.acknowledge();
                return;
              }
              on_panic(ChildFailure failure): void {
                failure.acknowledge();
                return;
              }
            end
            """;

    @Test
    void supervisorActorAdmitsExactlyTheCheckedControlHandlers() {
        assertDoesNotThrow(() -> Parser.parse(COMPLETE_SUPERVISOR));
    }

    @Test
    void ordinaryActorsCannotDeclarePrivilegedControlHandlers() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Worker as
                          on_panic(ChildFailure failure): void {
                            failure.acknowledge();
                            return;
                          }
                        end
                        """));
        assertTrue(error.getMessage().contains("supervisor actor"), error.getMessage());
    }

    @Test
    void supervisorHandlersAreExhaustiveAndHaveOneExactSignature() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define supervisor actor Manager as
                          on_throw(ChildFailure failure): void { return; }
                          on_raise(ChildFailure failure): void { return; }
                        end
                        """));
        assertTrue(missing.getMessage().contains("must exhaustively define on_panic"),
                missing.getMessage());

        IllegalArgumentException wrong = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define supervisor actor Manager as
                          on_throw(): void { return; }
                          on_raise(ChildFailure failure): void { return; }
                          on_panic(ChildFailure failure): void { return; }
                        end
                        """));
        assertTrue(wrong.getMessage().contains("on_throw(ChildFailure failure): void"),
                wrong.getMessage());

        IllegalArgumentException exported = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define supervisor actor Manager as
                          pub on_throw(ChildFailure failure): void { return; }
                          on_raise(ChildFailure failure): void { return; }
                          on_panic(ChildFailure failure): void { return; }
                        end
                        """));
        assertTrue(exported.getMessage().contains("private synchronous"),
                exported.getMessage());
    }

    @Test
    void legacyPseudoHandlersStayRejected() {
        for (String name : new String[] {"on_throws", "on_recover"}) {
            String source = "define supervisor actor Worker as\n"
                    + "  " + name + "(ChildFailure failure): void { return; }\n"
                    + "  on_throw(ChildFailure failure): void { return; }\n"
                    + "  on_raise(ChildFailure failure): void { return; }\n"
                    + "  on_panic(ChildFailure failure): void { return; }\n"
                    + "end\n";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), name);
            assertTrue(error.getMessage().contains("unsupported actor supervision pseudo-handler"),
                    () -> name + ": " + error.getMessage());
        }
    }

    @Test
    void ordinaryNonActorMethodsRemainUnaffected() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Example as
                  pub on_panic(): void { return; }
                end
                """));
    }
}
