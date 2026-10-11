package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SourceActorSchedulingPolicyTest {
    @Test void linkedSerialActorCannotBeDowngradedByExecutorClaim() {
        Ast.Program serial = Parser.parse("""
                define serial actor Counter as
                    receive(ActorMail<String> mail): void { return; }
                end
                """);
        assertTrue(SourceActorSchedulingPolicy.isSerialActor(serial, "Counter"));
        assertTrue(SourceActorSchedulingPolicy.verifyClaim(serial, "Counter", true));
        assertThrows(SecurityException.class,
                () -> SourceActorSchedulingPolicy.verifyClaim(serial, "Counter", false));
    }

    @Test void ordinaryActorCannotBeUpgradedByExecutorClaim() {
        Ast.Program ordinary = Parser.parse("""
                define actor Counter as
                    receive(ActorMail<String> mail): void { return; }
                end
                """);
        assertFalse(SourceActorSchedulingPolicy.verifyClaim(ordinary, "Counter", false));
        assertThrows(SecurityException.class,
                () -> SourceActorSchedulingPolicy.verifyClaim(ordinary, "Counter", true));
    }

    @Test void missingDuplicateAndNonactorTypesFailClosed() {
        Ast.Program serial = Parser.parse("define serial actor Counter as end");
        assertThrows(SecurityException.class,
                () -> SourceActorSchedulingPolicy.isSerialActor(serial, "Unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> SourceActorSchedulingPolicy.isSerialActor(serial, ""));

        Ast.ModuleDecl copy = new Ast.ModuleDecl(
                "another", serial.modules().getFirst().declarations());
        Ast.Program duplicate = new Ast.Program(
                List.of(serial.modules().getFirst(), copy));
        assertThrows(SecurityException.class,
                () -> SourceActorSchedulingPolicy.isSerialActor(duplicate, "Counter"));

        Ast.Program notActor = Parser.parse("define class Counter as end");
        assertThrows(SecurityException.class,
                () -> SourceActorSchedulingPolicy.isSerialActor(notActor, "Counter"));
    }
}
